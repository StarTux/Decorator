package com.winthier.decorator;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class DecoratorPlugin extends JavaPlugin {
    public final Json json = new Json(this);
    // Configuration values (config.yml)
    int playerPopulateInterval = 20;
    int memoryThreshold = 32;
    int memoryWaitTime = 30;
    int fakePlayers = 5;
    long millisecondsPerTick = 50;
    boolean batchMode = false;
    // State information
    Todo todo;
    boolean paused;
    boolean debug;
    int tickCooldown;
    long start; // timing
    // Non-persistent state
    World world;
    PrintStream biomesFile;
    transient Vec currentRegion = new Vec(0, 0);
    transient Vec pivotRegion = new Vec(0, 0);
    int fakeCount = (int) System.nanoTime() % 10000;
    int fakeCooldown;
    int previousChunks = 0;
    boolean doShutdown;
    int chunksPending = 0;
    long chunksPendingCooldown;
    // Components
    static final String META = "decorator:meta";
    MCProtocolLib mcProtocolLib = new MCProtocolLib();
    EventListener listener = new EventListener(this);
    DecoratorCommand command;
    final Map<UUID, Meta> metaMap = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        command = new DecoratorCommand(this).enable();
        importConfig();
        loadTodo();
        getServer().getPluginManager().registerEvents(listener, this);
        getServer().getScheduler().runTaskTimer(this, this::tick, 1, 1);
    }

    void importConfig() {
        reloadConfig();
        playerPopulateInterval = getConfig().getInt("player-populate-interval");
        fakePlayers = getConfig().getInt("fake-players");
        memoryThreshold = getConfig().getInt("memory-threshold");
        memoryWaitTime = getConfig().getInt("memory-wait-time");
        millisecondsPerTick = getConfig().getLong("milliseconds-per-tick");
        batchMode = getConfig().getBoolean("batch-mode");
    }

    @Override
    public void onDisable() {
        saveTodo();
        closeAllFiles();
    }

    void closeAllFiles() {
        if (biomesFile != null) {
            biomesFile.close();
            biomesFile = null;
        }
    }

    void initWorld(TodoWorld todoWorld, World theWorld) {
        Objects.requireNonNull(theWorld, "theWorld cannot be null");
        getLogger().info("Initializing world " + theWorld.getName() + ".");
        double radius = theWorld.getWorldBorder().getSize() * 0.5;
        if (radius > 100000) {
            throw new IllegalStateException("World border radius too large: " + radius + "!");
        }
        Chunk min = theWorld.getWorldBorder().getCenter().add(-radius, 0, -radius).getChunk();
        Chunk max = theWorld.getWorldBorder().getCenter().add(radius, 0, radius).getChunk();
        todoWorld.lboundx = min.getX();
        todoWorld.lboundz = min.getZ();
        todoWorld.uboundx = max.getX();
        todoWorld.uboundz = max.getZ();
        todoWorld.regions = new HashSet<>();
        int minX = min.getX() >> 5;
        int minZ = min.getZ() >> 5;
        int maxX = max.getX() >> 5;
        int maxZ = max.getZ() >> 5;
        for (int z = minZ; z <= maxZ; z += 1) {
            for (int x = minX; x <= maxX; x += 1) {
                todoWorld.regions.add(new Vec(x, z));
            }
        }
        todoWorld.chunks = new HashSet<>();
        todoWorld.totalRegions = todoWorld.regions.size();
        todoWorld.totalChunks = 0;
        todoWorld.pass += 1;
        todoWorld.initialized = true;
        if (batchMode) {
            getLogger().info("World " + theWorld.getName() + ": " + todoWorld.totalRegions + " regions scheduled.");
        }
    }

    void loadTodo() {
        File file = new File(getDataFolder(), "todo.json");
        if (!file.exists() && batchMode) {
            saveResource("todo.json", true);
        }
        todo = json.load("todo.json", Todo.class, Todo::new);
    }

    void saveTodo() {
        json.save("todo.json", todo, true);
    }

    long freeMem() {
        Runtime rt = Runtime.getRuntime();
        return rt.freeMemory() + rt.maxMemory() - rt.totalMemory();
    }

    void fetchNewChunks(TodoWorld todoWorld) {
        if (previousChunks > 0) {
            previousChunks = 0;
            world.save();
            Runtime.getRuntime().gc();
        }
        if (todoWorld.regions.isEmpty()) {
            if (todoWorld.pass == 1 && !todoWorld.structures) {
                todoWorld.structures = true;
                saveTodo();
                try (PrintStream out = new PrintStream(new FileOutputStream("ProcessStructures", true))) {
                    out.println(todoWorld.world);
                } catch (IOException ioe) {
                    ioe.printStackTrace();
                }
                getLogger().info(todoWorld.world + ": ProcessStructures scheduled");
                doShutdown = true;
            } else if (todoWorld.postWorld < todoWorld.pass) {
                if (new DecoratorPostWorldEvent(world, todoWorld.pass).callEvent()) {
                    todoWorld.postWorld = todoWorld.pass;
                    saveTodo();
                }
            } else if (todoWorld.pass < todoWorld.passes) {
                initWorld(todoWorld, world);
                getLogger().info(todoWorld.world + ": Pass " + todoWorld.pass);
            } else {
                todoWorld.done = true;
                getLogger().info(todoWorld.world + ": World complete");
            }
            return;
        }
        Vec nextRegion = null;
        int nextRegionDist = 0;
        for (Vec vec: todoWorld.regions) {
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
        if (todoWorld.allChunks || !file.exists()) {
            for (int z = 0; z < 32; z += 1) {
                for (int x = 0; x < 32; x += 1) {
                    int cx = minX + x;
                    int cz = minZ + z;
                    if (cx < todoWorld.lboundx || cx > todoWorld.uboundx || cz < todoWorld.lboundz || cz > todoWorld.uboundz) continue;
                    todoWorld.chunks.add(new Vec(cx, cz));
                }
            }
        } else {
            try (FileInputStream fis = new FileInputStream(file)) {
                for (int z = 0; z < 32; z += 1) {
                    for (int x = 0; x < 32; x += 1) {
                        int cx = minX + x;
                        int cz = minZ + z;
                        if (cx < todoWorld.lboundx || cx > todoWorld.uboundx || cz < todoWorld.lboundz || cz > todoWorld.uboundz) continue;
                        int o1 = fis.read();
                        int o2 = fis.read();
                        int o3 = fis.read();
                        int sc = fis.read();
                        if ((o1 == 0 && o2 == 0 && o3 == 0) || sc == 0) {
                            todoWorld.chunks.add(new Vec(cx, cz));
                        }
                    }
                }
            } catch (FileNotFoundException nfne) {
                getLogger().warning("File not found: " + file);
                nfne.printStackTrace();
                paused = true;
                return;
            } catch (IOException ioe) {
                getLogger().warning("Exception reading " + file + ":");
                ioe.printStackTrace();
                paused = true;
                return;
            }
        }
        todoWorld.totalChunks = todoWorld.chunks.size();
        todoWorld.regions.remove(nextRegion);
        int dist = Math.max(Math.abs(currentRegion.x - pivotRegion.x),
                            Math.abs(currentRegion.z - pivotRegion.z));
        if (dist > 1) {
            pivotRegion = currentRegion;
        }
        currentRegion = nextRegion;
        if (todoWorld.chunks.size() > 0) {
            getLogger().info("New region: " + filename + ", " + todoWorld.chunks.size() + " chunks.");
            previousChunks = todoWorld.chunks.size();
        }
        saveTodo();
    }

    Meta metaOf(Player player) {
        return metaMap.computeIfAbsent(player.getUniqueId(), u -> new Meta());
    }

    /**
     * Here we pick a new chunk and warp the player there.
     */
    void tickPlayer(Player player, TodoWorld todoWorld) {
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
        for (Vec vec: todoWorld.chunks) {
            int dist = Math.max(Math.abs(vec.x - anchor.x), Math.abs(vec.z - anchor.z));
            if (nextChunk == null || dist < nextChunkDist) {
                nextChunk = vec;
                nextChunkDist = dist;
            }
        }
        if (nextChunkDist > 4) {
            meta.anchor = nextChunk;
        }
        todoWorld.chunks.remove(nextChunk);
        meta.warping = true;
        final Vec theChunk = nextChunk;
        chunksPending += 1;
        chunksPendingCooldown = System.currentTimeMillis() + 10_000L;
        world.getChunkAtAsync(nextChunk.x, nextChunk.z, true, chunk -> {
                chunksPending -= 1;
                DecoratorEvent.call(chunk, todoWorld.pass);
                player.setGameMode(GameMode.CREATIVE);
                player.setAllowFlight(true);
                player.setFlying(true);
                final int cx = (theChunk.x << 4);
                final int cz = (theChunk.z << 4);
                final int px = cx + 8;
                final int pz = cz + 8;
                final int py = 128;
                Location location = new Location(world, px, py, pz, 0.0f, 0.0f);
                player.teleport(location);
                meta.populateCooldown = playerPopulateInterval;
                meta.warpLocation = location;
                meta.warping = false;
                // Biomes
                if (todoWorld.pass == 1 && biomesFile != null) {
                    Map<Biome, Integer> biomes = new EnumMap<>(Biome.class);
                    for (int dz = 0; dz < 16; dz += 1) {
                        for (int dx = 0; dx < 16; dx += 1) {
                            final int bx = cx + dx;
                            final int bz = cz + dz;
                            final int by = world.getHighestBlockYAt(bx, bz) + 1;
                            biomes.compute(chunk.getBlock(dx, by, dz).getBiome(),
                                           (b, i) -> (i != null ? i : 0) + 1);
                        }
                    }
                    biomesFile.println(chunk.getX() + "," + chunk.getZ() + ","
                                       + (biomes.entrySet().stream()
                                          .map(e -> e.getKey() + ":" + e.getValue())
                                          .collect(Collectors.joining(","))));
                }
            });
    }

    void tick() {
        if (todo == null) return;
        if (paused) return;
        if (tickCooldown > 0) {
            tickCooldown -= 1;
            System.gc();
            return;
        }
        // Work run queue
        start = System.currentTimeMillis();
        if (doShutdown) {
            if (chunksPending > 0) {
                if (start > chunksPendingCooldown) {
                    chunksPending = 0;
                } else {
                    return;
                }
            }
            Bukkit.shutdown();
            return;
        }
        // Set tickCooldown
        if (freeMem() < (long) (1024 * 1024 * memoryThreshold)) {
            getLogger().info("Low on memory. Waiting " + memoryWaitTime + " seconds...");
            tickCooldown = 20 * memoryWaitTime;
            if (world != null) world.save();
            System.gc();
            return;
        }
        // Find unfinished world
        for (TodoWorld todoWorld : todo.worlds) {
            if (!todoWorld.done) {
                tickWorld(todoWorld);
                return;
            }
        }
        closeAllFiles();
        // All done.
        touch(new File("DONE"));
        doShutdown = true;
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

    void tickWorld(TodoWorld todoWorld) {
        if (world == null || !world.getName().equals(todoWorld.world)) {
            world = Bukkit.getWorld(todoWorld.world);
            if (biomesFile != null) {
                biomesFile.close();
                biomesFile = null;
            }
            File file = new File(world.getWorldFolder(), "biomes.txt");
            try {
                biomesFile = new PrintStream(new FileOutputStream(file, true)); // true=append
            } catch (FileNotFoundException nfe) {
                throw new IllegalStateException(nfe);
            }
        }
        if (world == null) throw new IllegalStateException("world = null");
        if (!todoWorld.initialized) {
            initWorld(todoWorld, world);
        }
        // Spawn fake players
        final int playerCount = getServer().getOnlinePlayers().size();
        if (mcProtocolLib != null && fakeCooldown <= 0 && playerCount < fakePlayers) {
            mcProtocolLib.spawnFakePlayer(this, "fake" + fakeCount++);
            fakeCooldown = 20;
        }
        if (fakeCooldown > 0) fakeCooldown -= 1;
        if (todoWorld.chunks.isEmpty()) {
            fetchNewChunks(todoWorld);
            return;
        }
        //
        for (Player player: getServer().getOnlinePlayers()) {
            if (System.currentTimeMillis() - start >= millisecondsPerTick) return;
            if (todoWorld.chunks.isEmpty()) break;
            tickPlayer(player, todoWorld);
        }
    }

    /**
     * When a chunk is populated near a player, we refresh their
     * cooldown, assuming that very soon, they may generate more
     * chunks, thus should not move away from their current location.
     */
    void onChunkPopulate(Chunk chunk) {
        if (paused) return;
        if (tickCooldown > 0) return;
        Vec vec = new Vec(chunk.getX(), chunk.getZ());
        // Find causing player
        Player causingPlayer = null;
        int causingPlayerDist = 0;
        World theWorld = chunk.getWorld();
        int viewDistance = theWorld.getViewDistance();
        for (Player player: theWorld.getPlayers()) {
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
        // Debug
        if (debug) {
            String playerName = causingPlayer == null ? "N/A" : causingPlayer.getName();
            getLogger().info("Populate"
                             + " chunk=" + vec
                             + " player=" + playerName
                             + " dist=" + causingPlayerDist);
        }
    }
}
