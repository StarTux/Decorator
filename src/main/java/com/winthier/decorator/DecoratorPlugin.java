package com.winthier.decorator;

import com.github.steveice10.mc.protocol.MinecraftConstants;
import com.github.steveice10.mc.protocol.MinecraftProtocol;
import com.github.steveice10.mc.protocol.data.message.Message;
import com.github.steveice10.mc.protocol.packet.ingame.client.ClientChatPacket;
import com.github.steveice10.mc.protocol.packet.ingame.server.ServerJoinGamePacket;
import com.github.steveice10.packetlib.Client;
import com.github.steveice10.packetlib.event.session.DisconnectedEvent;
import com.github.steveice10.packetlib.event.session.PacketReceivedEvent;
import com.github.steveice10.packetlib.event.session.SessionAdapter;
import com.github.steveice10.packetlib.tcp.TcpSessionFactory;
import com.google.gson.Gson;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.Value;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkPopulateEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class DecoratorPlugin extends JavaPlugin implements Listener {
    // Configuration values (config.yml)
    private int playerPopulateInterval;
    private int memoryThreshold, memoryWaitTime;
    private int fakePlayers;
    private int lowMemRestartThreshold;
    private boolean batchMode;
    // State information (todo.yml)
    private Set<Vec> chunks, regions;
    private int total, done;
    private boolean paused, debug;
    private boolean allChunks;
    private final Map<UUID, Integer> playerPopulateCooldown = new HashMap<>();
    private String worldName;
    private int tickCooldown;
    private int lboundx, lboundz, uboundx, uboundz;
    // Non-persistent state
    private World world;
    private transient Vec currentRegion = new Vec(0, 0), pivotRegion = new Vec(0, 0);
    private final Map<UUID, Vec> anchors = new HashMap<>();
    private int fakeCount = (int)System.nanoTime() % 10000, fakeCooldown;
    private int previousChunks = 0;
    private int lowMemCount = 0;

    @Value
    final class Vec {
        public final int x, z;
    }

    class Batch {
        List<String> worlds;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        importConfig();
        loadTodo();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, () -> onTick(), 1, 1);
        if (this.batchMode && this.regions == null) {
            // Run this on the next tick so other plugins can do their setup.
            getServer().getScheduler().runTask(this, this::batchEnable);
        }
    }

    /*
     * Batch mode:
     * If the current job is empty, try and read a new one from
     * batch.json in the plugin folder.  If there is no such
     * thing, shut the server down.
     * An outside script needs to recognize the meaning of the
     * DONE file.
     */
    private void batchEnable() {
        getLogger().info("batchEnable()");
        File file = new File(getDataFolder(), "batch.json");
        if (file.exists()) {
            Gson gson = new Gson();
            Batch batch;
            try (FileReader fr = new FileReader(file)) {
                batch = gson.fromJson(fr, Batch.class);
            } catch (IOException ioe) {
                throw new IllegalStateException(ioe);
            }
            String theWorldName = batch.worlds.remove(0);
            if (batch.worlds.isEmpty()) {
                file.delete();
                touch(new File("DONE"));
            } else {
                try (FileWriter fw = new FileWriter(file)) {
                    gson.toJson(batch, fw);
                } catch (IOException ioe) {
                    throw new IllegalStateException(ioe);
                }
            }
            World theWorld = getServer().getWorld(theWorldName);
            // Consider creating world?
            if (theWorld == null) throw new IllegalStateException("World not found: " + theWorldName + "!");
            initWorld(theWorld, true);
        } else {
            touch(new File("DONE"));
            getServer().shutdown();
            return;
        }
    }

    void touch(File file) {
        if (!file.exists()) {
            try {
                new FileOutputStream(file).close();
            } catch (IOException ioe) {
                ioe.printStackTrace();
            }
        }
        file.setLastModified(System.currentTimeMillis());
    }

    void importConfig() {
        reloadConfig();
        this.playerPopulateInterval = getConfig().getInt("player-populate-interval");
        this.fakePlayers = getConfig().getInt("fake-players");
        this.memoryThreshold = getConfig().getInt("memory-threshold");
        this.memoryWaitTime = getConfig().getInt("memory-wait-time");
        this.lowMemRestartThreshold = getConfig().getInt("low-mem-restart-threshold");
        this.batchMode = getConfig().getBoolean("batch-mode");
        getLogger().info("Player Populate Interval: " + playerPopulateInterval + " ticks");
        getLogger().info("Fake Players: " + fakePlayers);
        getLogger().info("Memory Threshold: " + memoryThreshold + " MiB");
        getLogger().info("Memory Wait Time: " + memoryWaitTime + " seconds");
        getLogger().info("Low Memory Restart Threshold: " + lowMemRestartThreshold + " times");
        getLogger().info("Batch mode: " + (this.batchMode ? "enabled" : "disabled"));
    }

    @Override
    public void onDisable() {
        saveTodo();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        final Player player = sender instanceof Player ? (Player)sender : null;
        String cmd = args.length == 0 ? null : args[0].toLowerCase();
        if (args.length == 0) {
            return false;
        }
        switch (cmd) {
        case "init":
            if (args.length >= 1 && args.length <= 3) {
                final World theWorld;
                if (args.length >= 2) {
                    theWorld = getServer().getWorld(args[1]);
                    if (theWorld == null) {
                        sender.sendMessage("World not found: " + args[1] + "!");
                        return true;
                    }
                } else {
                    theWorld = getServer().getWorlds().get(0);
                }
                boolean all = args.length >= 3 && args[2].equals("all");
                try {
                    initWorld(world, all);
                } catch (IllegalStateException ise) {
                    sender.sendMessage("Error: " + ise.getMessage());
                    return true;
                }
                sender.sendMessage("" + this.total + " regions scheduled.");
                saveTodo();
            }
            break;
        case "pause":
            if (args.length == 1) {
                paused = !paused;
                if (paused) {
                    sender.sendMessage("Decorator paused");
                } else {
                    sender.sendMessage("Decorator unpaused");
                }
                return true;
            }
            break;
        case "debug":
            if (args.length == 1) {
                debug = !debug;
                if (debug) {
                    sender.sendMessage("Debug mode enabled");
                } else {
                    sender.sendMessage("Debug mode disabled");
                }
                return true;
            }
            break;
        case "reload":
            if (args.length == 1) {
                importConfig();
                sender.sendMessage("Config Reloaded");
                return true;
            }
            break;
        case "cancel":
            if (args.length == 1) {
                regions = null;
                chunks = null;
                total = 0;
                done = 0;
                world = null;
                saveTodo();
                sender.sendMessage("Cancelled");
                return true;
            }
            break;
        case "info":
            if (args.length == 1) {
                if (chunks == null || regions == null) {
                    sender.sendMessage("Not active");
                } else {
                    int d = total - regions.size();
                    int percent = total > 0 ? d * 100 / total : 0;
                    sender.sendMessage("World=" + worldName + " AllChunks=" + allChunks + " Bounds=(" + lboundx + "," + lboundz + ")-(" + uboundx + "," + uboundz + ")");
                    sender.sendMessage(String.format("%d/%d Regions done (%d%%), %d chunks.", d, total, percent, done));
                    if (tickCooldown > 0) sender.sendMessage("TickCooldown=" + tickCooldown);
                    if (currentRegion != null) sender.sendMessage(String.format("Current region: %d,%d with %d chunks", currentRegion.x, currentRegion.z, chunks.size()));
                }
                sender.sendMessage("Free: " + (freeMem() / 1024 / 1024) + " MiB");
                if (paused) sender.sendMessage("Paused");
                return true;
            }
            break;
        case "players":
            if (args.length == 1) {
                List<Player> ps = new ArrayList<>(getServer().getOnlinePlayers());
                sender.sendMessage("" + ps.size() + " Players:");
                int i = 0;
                for (Player p: ps) {
                    Location l = p.getLocation();
                    Integer cooldown = playerPopulateCooldown.get(p.getUniqueId());
                    int cd = cooldown == null ? 0 : cooldown;
                    int x = l.getBlockX();
                    int z = l.getBlockZ();
                    int cx = x >> 4;
                    int cz = z >> 4;
                    Vec anchor = anchors.get(p.getUniqueId());
                    int ax = anchor == null ? 0 : anchor.x;
                    int az = anchor == null ? 0 : anchor.z;
                    sender.sendMessage("" + ++i + "] " + p.getName() + " loc=(" + x + "," + z + ") chunk=(" + cx + "," + cz + ") anchor=(" + ax + "," + az + ") cd=" + cd);
                }
                return true;
            }
            break;
        case "save":
            if (args.length == 1) {
                saveTodo();
                if (world != null) world.save();
                sender.sendMessage("Todo and world saved");
                return true;
            }
            break;
        case "fake":
            if (args.length == 2) {
                spawnFakePlayer(args[1]);
                sender.sendMessage("Fake user logged in.");
                return true;
            }
            break;
        default:
            break;
        }
        return false;
    }

    void initWorld(World theWorld, boolean all) {
        Objects.requireNonNull(theWorld, "theWorld cannot be null");
        getLogger().info("Initializing world " + theWorld.getName() + ".");
        this.world = theWorld;
        this.worldName = theWorld.getName();
        double radius = theWorld.getWorldBorder().getSize() * 0.5;
        if (radius > 100000) throw new IllegalStateException("World border radius too large: " + radius + "!");
        this.allChunks = all;
        Chunk min = theWorld.getWorldBorder().getCenter().add(-radius, 0, -radius).getChunk();
        Chunk max = theWorld.getWorldBorder().getCenter().add(radius, 0, radius).getChunk();
        lboundx = min.getX();
        lboundz = min.getZ();
        uboundx = max.getX();
        uboundz = max.getZ();
        this.regions = new HashSet<>();
        int minX = min.getX() >> 5;
        int minZ = min.getZ() >> 5;
        int maxX = max.getX() >> 5;
        int maxZ = max.getZ() >> 5;
        for (int z = minZ + 1; z <= maxZ; z += 1) {
            for (int x = minX; x <= maxX; x += 1) {
                regions.add(new Vec(x, z));
            }
        }
        this.chunks = new HashSet<>();
        this.total = regions.size();
        this.done = 0;
        if (this.batchMode) getLogger().info("World " + theWorld.getName() + ": " + this.total + " regions scheduled.");
    }

    void loadTodo() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "todo.yml"));
        regions = null;
        if (config.isSet("regions")) {
            regions = new HashSet<>();
            Iterator<Integer> iter = config.getIntegerList("regions").iterator();
            while (iter.hasNext()) {
                regions.add(new Vec(iter.next(), iter.next()));
            }
        }
        chunks = null;
        if (config.isSet("chunks")) {
            chunks = new HashSet<>();
            Iterator<Integer> iter = config.getIntegerList("chunks").iterator();
            while (iter.hasNext()) {
                chunks.add(new Vec(iter.next(), iter.next()));
            }
        }
        worldName = config.getString("world");
        total = config.getInt("total");
        done = config.getInt("done");
        allChunks = config.getBoolean("all");
        List<Integer> bounds = config.getIntegerList("bounds");
        if (bounds.size() == 4) {
            lboundx = bounds.get(0);
            lboundz = bounds.get(1);
            uboundx = bounds.get(2);
            uboundz = bounds.get(3);
        }
        if (chunks != null && regions != null) {
            getLogger().info("" + regions.size() + " regions and " + chunks.size() + " chunks loaded");
        }
    }

    void saveTodo() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("world", worldName);
        config.set("total", total);
        config.set("done", done);
        config.set("all", allChunks);
        if (chunks != null) {
            ArrayList<Integer> ls = new ArrayList<>();
            for (Vec vec: chunks) {
                ls.add(vec.x);
                ls.add(vec.z);
            }
            config.set("chunks", ls);
        }
        if (regions != null) {
            ArrayList<Integer> ls = new ArrayList<>();
            for (Vec vec: regions) {
                ls.add(vec.x);
                ls.add(vec.z);
            }
            config.set("regions", ls);
        }
        config.set("bounds", Arrays.asList(lboundx, lboundz, uboundx, uboundz));
        try {
            config.save(new File(getDataFolder(), "todo.yml"));
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
    }

    long freeMem() {
        Runtime rt = Runtime.getRuntime();
        return rt.freeMemory() + rt.maxMemory() - rt.totalMemory();
    }

    void onTick() {
        if (paused || regions == null || chunks == null) return;
        if (tickCooldown > 0) {
            tickCooldown -= 1;
            return;
        }
        if (freeMem() < (long)(1024 * 1024 * memoryThreshold)) {
            lowMemCount += 1;
            if (lowMemCount == lowMemRestartThreshold) {
                getLogger().info("Restarting due to " + lowMemRestartThreshold + " times low memory.");
                getServer().shutdown();
            } else {
                getLogger().info("Low on memory. Waiting " + memoryWaitTime + " seconds...");
                tickCooldown = 20 * memoryWaitTime;
                System.gc();
            }
            return;
        }
        if (world == null && worldName == null) return;
        if (world == null) world = getServer().getWorld(worldName);
        if (world == null) return;
        if (fakeCooldown <= 0 && getServer().getOnlinePlayers().size() < fakePlayers) {
            spawnFakePlayer("fake" + fakeCount++);
            fakeCooldown = 20;
        }
        if (fakeCooldown > 0) fakeCooldown -= 1;
        long now = System.nanoTime();
        // Fetch new chunks if necessary.
        while (chunks.isEmpty()) {
            if (System.nanoTime() - now > 1000000000) {
                return;
            }
            if (previousChunks > 0) {
                previousChunks = 0;
                world.save();
                Runtime.getRuntime().gc();
            }
            if (regions.isEmpty()) {
                regions = null;
                chunks = null;
                getLogger().info("Done!");
                if (this.batchMode) getServer().shutdown();
                return;
            }
            Vec nextRegion = null;
            for (Vec vec: regions) {
                if (nextRegion == null
                    || (Math.max(Math.abs(pivotRegion.x - vec.x), Math.abs(pivotRegion.z - vec.z)) < Math.max(Math.abs(pivotRegion.x - nextRegion.x), Math.abs(pivotRegion.z - nextRegion.z)))) {
                    nextRegion = vec;
                }
            }
            String filename = "r." + nextRegion.x + "." + nextRegion.z + ".mca";
            File file = world.getWorldFolder();
            switch (world.getEnvironment()) {
            case NETHER:
                file = new File(file, "DIM-1");
                break;
            case THE_END:
                file = new File(file, "DIM1");
                break;
            default:
                break;
            }
            file = new File(file, "region");
            file = new File(file, filename);
            int minX = nextRegion.x * 32;
            int minZ = nextRegion.z * 32;
            if (allChunks || !file.exists()) {
                for (int z = 0; z < 32; z += 1) {
                    for (int x = 0; x < 32; x += 1) {
                        int cx = minX + x;
                        int cz = minZ + z;
                        if (cx < lboundx || cx > uboundx || cz < lboundz || cz > uboundz) continue;
                        chunks.add(new Vec(cx, cz));
                    }
                }
            } else {
                try {
                    FileInputStream fis = new FileInputStream(file);
                    for (int z = 0; z < 32; z += 1) {
                        for (int x = 0; x < 32; x += 1) {
                            int cx = minX + x;
                            int cz = minZ + z;
                            if (cx < lboundx || cx > uboundx || cz < lboundz || cz > uboundz) continue;
                            int o1 = fis.read();
                            int o2 = fis.read();
                            int o3 = fis.read();
                            int sc = fis.read();
                            if ((o1 == 0 && o2 == 0 && o3 == 0) || sc == 0) {
                                chunks.add(new Vec(cx, cz));
                            }
                        }
                    }
                    fis.close();
                } catch (FileNotFoundException nfne) {
                    System.err.println("File not found: " + file);
                    nfne.printStackTrace();
                    paused = true;
                    return;
                } catch (IOException ioe) {
                    System.err.println("Exception reading " + file + ":");
                    ioe.printStackTrace();
                    paused = true;
                    return;
                }
            }
            regions.remove(nextRegion);
            if (Math.max(Math.abs(currentRegion.x - pivotRegion.x), Math.abs(currentRegion.z - pivotRegion.z)) > 1) pivotRegion = currentRegion;
            currentRegion = nextRegion;
            if (chunks.size() > 0) {
                getLogger().info("New region: " + filename + ", " + chunks.size() + " chunks.");
                previousChunks = chunks.size();
            }
            saveTodo();
        }
        if (chunks.isEmpty()) return;
        for (Player player: getServer().getOnlinePlayers()) {
            if (chunks.isEmpty()) break;
            Integer popCooldown = playerPopulateCooldown.get(player.getUniqueId());
            if (popCooldown != null) {
                popCooldown -= 1;
                if (popCooldown <= 0) {
                    playerPopulateCooldown.remove(player.getUniqueId());
                } else {
                    playerPopulateCooldown.put(player.getUniqueId(), popCooldown);
                }
                continue;
            }
            Vec anchor = anchors.get(player.getUniqueId());
            if (anchor == null) {
                Chunk chunk = player.getLocation().getChunk();
                anchor = new Vec(chunk.getX(), chunk.getZ());
                anchors.put(player.getUniqueId(), anchor);
            }
            Vec nextChunk = null;
            int minDist = 0;
            for (Vec vec: chunks) {
                int dist = Math.max(Math.abs(vec.x - anchor.x), Math.abs(vec.z - anchor.z));
                if (nextChunk == null || minDist > dist) {
                    nextChunk = vec;
                    minDist = dist;
                }
            }
            if (minDist > 4) {
                anchors.put(player.getUniqueId(), nextChunk);
            }
            chunks.remove(nextChunk);
            int x = nextChunk.x * 16 + 8;
            int z = nextChunk.z * 16 + 8;
            world.loadChunk(nextChunk.x, nextChunk.z, true);
            Location location = world.getHighestBlockAt(x, z).getLocation().add(0.5, 0.1, 0.5);
            Location playerLocation = player.getLocation();
            location.setYaw(playerLocation.getYaw());
            location.setPitch(playerLocation.getPitch());
            player.setGameMode(GameMode.CREATIVE);
            player.setAllowFlight(true);
            player.setFlying(true);
            player.teleport(location);
            done += 1;
        }
    }

    @EventHandler
    public void onChunkPopulate(ChunkPopulateEvent event) {
        if (regions == null || chunks == null) return;
        if (world == null && worldName == null) return;
        if (world == null) world = getServer().getWorld(worldName);
        if (!world.equals(event.getWorld())) return;
        Vec vec = new Vec(event.getChunk().getX(), event.getChunk().getZ());
        if (!chunks.remove(vec)) return;
        Player causingPlayer = null;
        int minDist = 0;
        for (Player player: world.getPlayers()) {
            Chunk chunk = player.getLocation().getChunk();
            int dist = Math.max(Math.abs(vec.x - chunk.getX()), Math.abs(vec.z - chunk.getZ()));
            if (dist < 5 && (causingPlayer == null || dist < minDist)) {
                causingPlayer = player;
                minDist = dist;
            }
        }
        if (causingPlayer != null) playerPopulateCooldown.put(causingPlayer.getUniqueId(), playerPopulateInterval);
        if (debug) {
            String playerName = causingPlayer == null ? "Unknown" : causingPlayer.getName();
            getLogger().info("POPULATE " + vec.x + " " + vec.z + " " + playerName);
        }
        done += 1;
        if (done % 10000 == 0) {
            printTodoProgressReport();
            saveTodo();
            world.save();
        }
    }

    void printTodoProgressReport() {
        int d = total - regions.size();
        int percent = total > 0 ? d * 100 / total : 0;
        getLogger().info(String.format("%d/%d Regions done (%d%%), %d chunks", d, total, percent, done));
    }

    // --- MCProtocolLib stuff

    private void spawnFakePlayer(final String username) {
        MinecraftProtocol protocol = new MinecraftProtocol(username);
        Client client = new Client("localhost", Bukkit.getPort(), protocol, new TcpSessionFactory(Proxy.NO_PROXY));
        client.getSession().setFlag(MinecraftConstants.AUTH_PROXY_KEY, Proxy.NO_PROXY);
        client.getSession().addListener(new SessionAdapter() {
            @Override
            public void packetReceived(PacketReceivedEvent event) {
                if (event.getPacket() instanceof ServerJoinGamePacket) {
                    event.getSession().send(new ClientChatPacket(username + " says hello."));
                }
            }
            @Override
            public void disconnected(DisconnectedEvent event) {
                System.out.println("Disconnected: " + Message.fromString(event.getReason()).getFullText());
                if (event.getCause() != null) {
                    event.getCause().printStackTrace();
                }
            }
        });

        client.getSession().connect();
    }
}
