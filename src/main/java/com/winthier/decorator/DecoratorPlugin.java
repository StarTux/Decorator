package com.winthier.decorator;

import com.google.gson.Gson;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class DecoratorPlugin extends JavaPlugin {
    // Configuration values (config.yml)
    private int playerPopulateInterval;
    private int memoryThreshold;
    private int memoryWaitTime;
    private int fakePlayers;
    private boolean batchMode;
    // State information (todo.yml)
    private Set<Vec> chunks;
    private Set<Vec> regions;
    private int total;
    private int done;
    private boolean paused;
    private boolean debug;
    private boolean allChunks;
    private String worldName;
    private int tickCooldown;
    private int lboundx;
    private int lboundz;
    private int uboundx;
    private int uboundz;
    // Non-persistent state
    private World world;
    private transient Vec currentRegion = new Vec(0, 0);
    private transient Vec pivotRegion = new Vec(0, 0);
    private int fakeCount = (int) System.nanoTime() % 10000;
    private int fakeCooldown;
    private int previousChunks = 0;
    private List<Runnable> runQueue = new ArrayList<>();
    // Components
    static final String META = "decorator:meta";
    MCProtocolLib mcProtocolLib = new MCProtocolLib();
    Metadata metadata = new Metadata(this);
    EventListener listener = new EventListener(this);

    class Batch {
        List<String> worlds;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        importConfig();
        loadTodo();
        getServer().getPluginManager().registerEvents(listener, this);
        getServer().getScheduler().runTaskTimer(this, () -> onTick(), 1, 1);
        if (batchMode && regions == null) {
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
            if (theWorld == null) {
                throw new IllegalStateException("World not found: " + theWorldName + "!");
            }
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
        playerPopulateInterval = getConfig().getInt("player-populate-interval");
        fakePlayers = getConfig().getInt("fake-players");
        memoryThreshold = getConfig().getInt("memory-threshold");
        memoryWaitTime = getConfig().getInt("memory-wait-time");
        batchMode = getConfig().getBoolean("batch-mode");
        getLogger().info("Player Populate Interval: " + playerPopulateInterval + " ticks");
        getLogger().info("Fake Players: " + fakePlayers);
        getLogger().info("Memory Threshold: " + memoryThreshold + " MiB");
        getLogger().info("Memory Wait Time: " + memoryWaitTime + " seconds");
        getLogger().info("Batch mode: " + (batchMode ? "enabled" : "disabled"));
    }

    @Override
    public void onDisable() {
        saveTodo();
        List<Runnable> copy = new ArrayList<>(runQueue);
        runQueue.clear();
        for (Runnable run : copy) {
            try {
                run.run();
            } catch (Throwable t) {
                getLogger().log(Level.SEVERE, "Clearning RunQueue onDisable", t);
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        final Player player = sender instanceof Player ? (Player) sender : null;
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
                    initWorld(theWorld, all);
                } catch (IllegalStateException ise) {
                    sender.sendMessage("Error: " + ise.getMessage());
                    return true;
                }
                sender.sendMessage("" + total + " regions scheduled.");
                saveTodo();
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
                    sender.sendMessage("World=" + worldName
                                       + " AllChunks="
                                       + allChunks
                                       + " Bounds=(" + lboundx + "," + lboundz
                                       + ")-(" + uboundx + "," + uboundz + ")");
                    String fmt = String.format("%d/%d Regions done (%d%%), %d chunks.",
                                               d, total, percent, done);
                    sender.sendMessage(fmt);
                    if (tickCooldown > 0) sender.sendMessage("TickCooldown=" + tickCooldown);
                    if (currentRegion != null) {
                        fmt = String.format("Current region: %d,%d with %d chunks",
                                            currentRegion.x, currentRegion.z, chunks.size());
                        sender.sendMessage(fmt);
                    }
                }
                sender.sendMessage("Free: " + (freeMem() / 1024 / 1024) + " MiB");
                sender.sendMessage("Run Queue: " + runQueue.size() + " task(s)");
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
                    Meta meta = metaOf(p);
                    Location l = p.getLocation();
                    int x = l.getBlockX();
                    int z = l.getBlockZ();
                    float yaw = l.getYaw();
                    Chunk c = l.getChunk();
                    int cx = c.getX();
                    int cz = c.getZ();
                    sender.sendMessage("" + ++i + "] " + p.getName()
                                       + " loc=(" + x + "," + z + ", " + yaw + ")"
                                       + " chunk=(" + cx + "," + cz + ")"
                                       + " anchor=(" + meta.anchor + ")"
                                       + " cd=" + meta.populateCooldown
                                       + " warping=" + meta.warping);
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
                mcProtocolLib.spawnFakePlayer(args[1]);
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
        world = theWorld;
        worldName = theWorld.getName();
        double radius = theWorld.getWorldBorder().getSize() * 0.5;
        if (radius > 100000) {
            throw new IllegalStateException("World border radius too large: " + radius + "!");
        }
        allChunks = all;
        Chunk min = theWorld.getWorldBorder().getCenter().add(-radius, 0, -radius).getChunk();
        Chunk max = theWorld.getWorldBorder().getCenter().add(radius, 0, radius).getChunk();
        lboundx = min.getX();
        lboundz = min.getZ();
        uboundx = max.getX();
        uboundz = max.getZ();
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
        if (batchMode) {
            getLogger().info("World " + theWorld.getName() + ": "
                             + total + " regions scheduled.");
        }
    }

    void loadTodo() {
        File file = new File(getDataFolder(), "todo.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
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
            getLogger().info("" + regions.size() + " regions and "
                             + chunks.size() + " chunks loaded");
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

    void fetchNewChunks() {
        if (previousChunks > 0) {
            previousChunks = 0;
            world.save();
            Runtime.getRuntime().gc();
        }
        if (regions.isEmpty()) {
            regions = null;
            chunks = null;
            saveTodo();
            getLogger().info("Done!");
            if (batchMode) runQueue.add(() -> getServer().shutdown());
            return;
        }
        Vec nextRegion = null;
        int nextRegionDist = 0;
        for (Vec vec: regions) {
            int dist = Math.max(Math.abs(pivotRegion.x - vec.x),
                                Math.abs(pivotRegion.z - vec.z));
            if (nextRegion == null || (dist < nextRegionDist)) {
                nextRegion = vec;
                nextRegionDist = dist;
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
        int dist = Math.max(Math.abs(currentRegion.x - pivotRegion.x),
                            Math.abs(currentRegion.z - pivotRegion.z));
        if (dist > 1) {
            pivotRegion = currentRegion;
        }
        currentRegion = nextRegion;
        if (chunks.size() > 0) {
            getLogger().info("New region: " + filename + ", " + chunks.size() + " chunks.");
            previousChunks = chunks.size();
        }
    }

    Meta metaOf(Player player) {
        return metadata.get(player, META, Meta.class, Meta::new);
    }

    void tickPlayer(Player player) {
        Meta meta = metaOf(player);
        if (meta.warping) return;
        // Wait cooldown
        if (meta.populateCooldown > 0) {
            meta.populateCooldown -= 1;
            return;
        }
        // Rotating players did not populate more chunks.
        Vec anchor = meta.anchor;
        if (anchor == null) {
            Chunk chunk = player.getLocation().getChunk();
            anchor = new Vec(chunk.getX(), chunk.getZ());
            meta.anchor = anchor;
        }
        Vec nextChunk = null;
        int nextChunkDist = 0;
        for (Vec vec: chunks) {
            int dist = Math.max(Math.abs(vec.x - anchor.x), Math.abs(vec.z - anchor.z));
            if (nextChunk == null || dist < nextChunkDist) {
                nextChunk = vec;
                nextChunkDist = dist;
            }
        }
        if (nextChunkDist > 4) {
            meta.anchor = nextChunk;
        }
        chunks.remove(nextChunk);
        meta.warping = true;
        final int x = (nextChunk.x << 4) + 8;
        final int z = (nextChunk.z << 4) + 8;
        world.getChunkAtAsync(nextChunk.x, nextChunk.z, true, chunk -> {
                runQueue.add(() -> DecoratorEvent.call(chunk));
                player.setGameMode(GameMode.CREATIVE);
                player.setAllowFlight(true);
                player.setFlying(true);
                final int y = world.getHighestBlockYAt(x, z) + 4;
                Location location = new Location(world,
                                                 x, y, z,
                                                 0.0f, 0.0f);
                player.teleport(location);
                meta.populateCooldown = playerPopulateInterval;
                meta.warpLocation = location;
                meta.warping = false;
            });
        done += 1;
    }

    void onTick() {
        if (paused) return;
        // Validate world
        if (world == null) {
            if (worldName == null) return;
            world = getServer().getWorld(worldName);
            return;
        }
        // Work run queue
        long start = System.currentTimeMillis();
        if (!runQueue.isEmpty()) {
            do {
                Runnable run = runQueue.remove(0);
                run.run();
                if (System.currentTimeMillis() - start >= 50) return;
            } while (!runQueue.isEmpty());
        }
        if (regions == null || chunks == null) return;
        if (tickCooldown > 0) {
            tickCooldown -= 1;
            return;
        }
        // Set tickCooldown
        if (freeMem() < (long) (1024 * 1024 * memoryThreshold)) {
            getLogger().info("Low on memory. Waiting " + memoryWaitTime + " seconds...");
            tickCooldown = 20 * memoryWaitTime;
            System.gc();
            return;
        }
        // Spawn fake players
        final int playerCount = getServer().getOnlinePlayers().size();
        if (mcProtocolLib != null && fakeCooldown <= 0 && playerCount < fakePlayers) {
            mcProtocolLib.spawnFakePlayer("fake" + fakeCount++);
            fakeCooldown = 20;
        }
        if (fakeCooldown > 0) fakeCooldown -= 1;
        if (chunks.isEmpty()) {
            fetchNewChunks();
            saveTodo();
            return;
        }
        if (System.currentTimeMillis() - start >= 50) return;
        //
        for (Player player: getServer().getOnlinePlayers()) {
            if (chunks.isEmpty()) break;
            tickPlayer(player);
            if (System.currentTimeMillis() - start >= 50) break;
        }
    }

    void onChunkPopulate(Chunk chunk) {
        if (regions == null || chunks == null) return;
        if (world == null) return;
        if (!world.equals(chunk.getWorld())) return;
        Vec vec = new Vec(chunk.getX(), chunk.getZ());
        if (!chunks.contains(vec)) return;
        runQueue.add(() -> DecoratorEvent.call(chunk));
        // Find causing player
        Player causingPlayer = null;
        int causingPlayerDist = 0;
        int viewDistance = world.getViewDistance();
        for (Player player: world.getPlayers()) {
            Chunk pc = player.getLocation().getChunk();
            int dist = Math.max(Math.abs(vec.x - pc.getX()), Math.abs(vec.z - pc.getZ()));
            if (dist > viewDistance) {
                continue;
            }
            if (causingPlayer == null || dist < causingPlayerDist) {
                causingPlayer = player;
                causingPlayerDist = dist;
            }
        }
        if (causingPlayer == null) return;
        metaOf(causingPlayer).populateCooldown = playerPopulateInterval;
        chunks.remove(vec);
        // Update state
        done += 1;
        if (done % 10000 == 0) {
            printTodoProgressReport();
            saveTodo();
            world.save();
        }
        // Debug
        if (debug) {
            String playerName = causingPlayer == null ? "N/A" : causingPlayer.getName();
            getLogger().info("Populate"
                             + " chunk=" + vec
                             + " player=" + playerName
                             + " dist=" + causingPlayerDist);
        }
    }

    void printTodoProgressReport() {
        int d = total - regions.size();
        int percent = total > 0 ? d * 100 / total : 0;
        getLogger().info(String.format("%d/%d Regions done (%d%%), %d chunks",
                                       d, total, percent, done));
    }

    void onPluginDisable(Plugin plugin) {
        if (plugin.equals(this)) return;
        List<Runnable> copy = new ArrayList<>(runQueue);
        runQueue.clear();
        for (Runnable run : copy) {
            try {
                run.run();
            } catch (Throwable t) {
                getLogger().log(Level.SEVERE, "Clearing RunQueue onPluginDisable", t);
            }
        }
    }
}
