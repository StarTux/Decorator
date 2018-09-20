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
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
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
    private Set<Vec> chunks, regions;
    private Vec currentRegion;
    private int total, done;
    private boolean paused, debug;
    private boolean allChunks;
    private final Map<UUID, Vec> anchors = new HashMap<>();
    private final Map<UUID, Integer> playerPopulateCooldown = new HashMap<>();
    private World world;
    private String worldName;
    private int interval, playerPopulateInterval;
    private int fakePlayers, fakeCount = (int)System.nanoTime() % 10000, fakeCooldown;
    private int tickCooldown;
    private int memoryThreshold, memoryWaitTime;

    @Value
    final class Vec {
        public final int x, z;
    }

    @Override
    public void onEnable() {
        importConfig();
        loadTodo();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, () -> onTick(), 1, 1);
    }

    void importConfig() {
        reloadConfig();
        saveDefaultConfig();
        interval = getConfig().getInt("interval");
        playerPopulateInterval = getConfig().getInt("player-populate-interval");
        fakePlayers = getConfig().getInt("fake-players");
        memoryThreshold = getConfig().getInt("memory-threshold");
        memoryWaitTime = getConfig().getInt("memory-wait-time");
        getLogger().info("Interval: " + interval + " ticks");
        getLogger().info("Player Populate Interval: " + playerPopulateInterval + " ticks");
        getLogger().info("Fake Players: " + fakePlayers);
        getLogger().info("Memory Threshold: " + memoryThreshold + " MiB");
        getLogger().info("Memory Wait Time: " + memoryWaitTime + " seconds");
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
                if (args.length >= 2) {
                    world = getServer().getWorld(args[1]);
                    worldName = world.getName();
                } else {
                    world = getServer().getWorlds().get(0);
                    worldName = world.getName();
                }
                double radius = world.getWorldBorder().getSize() * 0.5;
                if (radius > 100000) {
                    sender.sendMessage("World border too large!");
                    return true;
                }
                allChunks = args.length >= 3 && args[2].equals("all");
                Chunk min = world.getWorldBorder().getCenter().add(-radius, 0, -radius).getChunk();
                Chunk max = world.getWorldBorder().getCenter().add(radius, 0, radius).getChunk();
                regions = new HashSet<>();
                int minX = min.getX() >> 5;
                int minZ = min.getZ() >> 5;
                int maxX = max.getX() >> 5;
                int maxZ = max.getZ() >> 5;
                for (int z = minZ + 1; z <= maxZ; z += 1) {
                    for (int x = minX; x <= maxX; x += 1) {
                        regions.add(new Vec(x, z));
                    }
                }
                chunks = new HashSet<>();
                total = regions.size();
                done = 0;
                sender.sendMessage("" + total + " regions scheduled.");
                return true;
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
                    sender.sendMessage(String.format("%d/%d Regions done (%d%%), %d chunks. All chunks=%s.", d, total, percent, done, allChunks));
                    if (currentRegion != null) sender.sendMessage(String.format("Current region: %d,%d with %d chunks", currentRegion.x, currentRegion.z, chunks.size()));
                }
                sender.sendMessage("Free: " + (Runtime.getRuntime().freeMemory() / 1024 / 1024) + " MiB");
                if (paused) sender.sendMessage("Paused");
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
        try {
            config.save(new File(getDataFolder(), "todo.yml"));
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
    }

    void onTick() {
        if (paused || regions == null || chunks == null) return;
        if (tickCooldown > 0) {
            tickCooldown -= 1;
            return;
        }
        tickCooldown = interval;
        if (Runtime.getRuntime().freeMemory() < (long)(1024 * 1024 * memoryThreshold)) {
            getLogger().info("Low on memory. Waiting " + memoryWaitTime + " seconds...");
            tickCooldown = 20 * memoryWaitTime;
            collectGarbage();
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
        for (Player player: getServer().getOnlinePlayers()) {
            // Fetch new chunks if necessary.
            if (chunks.isEmpty()) {
                if (regions.isEmpty()) {
                    regions = null;
                    chunks = null;
                    getLogger().info("Done!");
                    return;
                }
                Vec nextRegion = null;
                for (Vec vec: regions) {
                    if (nextRegion == null
                        || (Math.abs(vec.x) < Math.abs(nextRegion.x)
                            && Math.abs(vec.z) < Math.abs(nextRegion.z))) {
                        nextRegion = vec;
                    }
                }
                String filename = "r." + nextRegion.x + "." + nextRegion.z + ".mca";
                File file = new File(world.getWorldFolder(), "region");
                file = new File(file, filename);
                int minX = nextRegion.x * 32;
                int minZ = nextRegion.z * 32;
                if (allChunks || !file.exists()) {
                    for (int z = 0; z < 32; z += 1) {
                        for (int x = 0; x < 32; x += 1) {
                            chunks.add(new Vec(minX + x, minZ + z));
                        }
                    }
                } else {
                    try {
                        FileInputStream fis = new FileInputStream(file);
                        for (int z = 0; z < 32; z += 1) {
                            for (int x = 0; x < 32; x += 1) {
                                int o1 = fis.read();
                                int o2 = fis.read();
                                int o3 = fis.read();
                                int sc = fis.read();
                                if ((o1 == 0 && o2 == 0 && o3 == 0) || sc == 0) {
                                    chunks.add(new Vec(minX + x, minZ + z));
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
                getLogger().info("New region: " + filename + ", " + chunks.size() + " chunks. Saving todo and " + world.getName() + ".");
                saveTodo();
                world.save();
                Runtime.getRuntime().gc();
            }
            if (chunks.isEmpty()) return;
            Integer popCooldown = playerPopulateCooldown.get(player.getUniqueId());
            if (popCooldown != null) {
                popCooldown -= Math.max(1, interval);
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
            int chunkX = anchor.x;
            int chunkZ = anchor.z;
            Vec nextVec = null;
            int minDist = 0;
            for (Vec vec: chunks) {
                int dist = Math.max(Math.abs(vec.x - chunkX), Math.abs(vec.z - chunkZ));
                if (nextVec == null || minDist > dist) {
                    nextVec = vec;
                    minDist = dist;
                }
            }
            if (minDist > 4) {
                anchors.put(player.getUniqueId(), nextVec);
            }
            chunks.remove(nextVec);
            currentRegion = nextVec;
            int x = nextVec.x * 16 + 8;
            int z = nextVec.z * 16 + 8;
            Location location = world.getHighestBlockAt(x, z).getLocation().add(0.5, 0.1, 0.5);
            Location playerLocation = player.getLocation();
            location.setYaw(playerLocation.getYaw());
            location.setPitch(playerLocation.getPitch());
            location.getBlock().getType();
            player.setGameMode(GameMode.CREATIVE);
            player.setAllowFlight(true);
            player.setFlying(true);
            player.teleport(location);
            playerPopulateCooldown.put(player.getUniqueId(), playerPopulateInterval);
            done += 1;
            if (done % 10000 == 0) {
                printTodoProgressReport();
                collectGarbage();
            }
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

    void collectGarbage() {
        getLogger().info("" + (Runtime.getRuntime().freeMemory() / 1024 / 1024) + " MiB free. Collecing garbage..." );
        Runtime.getRuntime().gc();
        getLogger().info("" + (Runtime.getRuntime().freeMemory() / 1024 / 1024) + " MiB free." );
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
