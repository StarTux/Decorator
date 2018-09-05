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
    private Set<Vec> todo;
    private int total;
    private boolean paused, debug;
    private final Map<UUID, Vec> anchors = new HashMap<>();
    private final Set<UUID> populateDidHappen = new HashSet<>();
    private World world;
    private String worldName;
    private int interval;
    private int fakePlayers, fakeCount = (int)System.nanoTime() % 10000, fakeCooldown;
    private int tickCooldown;
    private int memoryThreshold;

    @Value
    final class Vec {
        private final int x, z;
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
        fakePlayers = getConfig().getInt("fake-players");
        memoryThreshold = getConfig().getInt("memory-threshold");
        getLogger().info("Interval: " + interval);
        getLogger().info("Fake Players: " + fakePlayers);
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
            if (args.length == 1 || args.length == 2) {
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
                Chunk min = world.getWorldBorder().getCenter().add(-radius, 0, -radius).getChunk();
                Chunk max = world.getWorldBorder().getCenter().add(radius, 0, radius).getChunk();
                todo = new HashSet<>();
                for (int z = min.getZ() + 1; z < max.getZ(); z += 1) {
                    for (int x = min.getX() + 1; x < max.getX(); x += 1) {
                        todo.add(new Vec(x, z));
                    }
                }
                total = todo.size();
                sender.sendMessage("" + todo.size() + " chunks scheduled.");
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
                todo = null;
                saveTodo();
                sender.sendMessage("Canceled");
                return true;
            }
            break;
        case "info":
            if (args.length == 1) {
                if (todo == null) {
                    sender.sendMessage("Not active");
                } else {
                    int done = total - todo.size();
                    int percent = done * 100 / total;
                    sender.sendMessage(String.format("%d/%d Chunks done (%d%%)", done, total, percent));
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
        getLogger().info("Loading todos. This may take a while...");
        todo = null;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "todo.yml"));
        if (!config.isSet("todo")) return;
        todo = new HashSet<>();
        Iterator<Integer> iter = config.getIntegerList("todo").iterator();
        while (iter.hasNext()) {
            todo.add(new Vec(iter.next(), iter.next()));
        }
        worldName = config.getString("world");
        total = config.getInt("total");
        getLogger().info("..." + todo.size() + " todos loaded");
    }

    void saveTodo() {
        YamlConfiguration config = new YamlConfiguration();
        if (todo != null) {
            config.set("world", world.getName());
            config.set("total", total);
            ArrayList<Integer> ls = new ArrayList<>();
            for (Vec vec: todo) {
                ls.add(vec.x);
                ls.add(vec.z);
            }
            config.set("todo", ls);
        }
        try {
            config.save(new File(getDataFolder(), "todo.yml"));
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
    }

    void onTick() {
        if (paused || todo == null) return;
        if (tickCooldown > 0) {
            tickCooldown -= 1;
            return;
        }
        tickCooldown = interval;
        if (Runtime.getRuntime().freeMemory() < (long)(1024 * 1024 * memoryThreshold)) {
            getLogger().info("Low on memory. Waiting 10 seconds...");
            tickCooldown = 200;
            getLogger().info("Garbage collecting...");
            Runtime.getRuntime().gc();
            getLogger().info("Done");
            return;
        }
        if (world == null && worldName == null) return;
        if (world == null) world = getServer().getWorld(worldName);
        if (fakeCooldown <= 0 && getServer().getOnlinePlayers().size() < fakePlayers) {
            spawnFakePlayer("fake" + fakeCount++);
            fakeCooldown = 20;
        }
        if (fakeCooldown > 0) fakeCooldown -= 1;
        for (Player player: getServer().getOnlinePlayers()) {
            if (todo.isEmpty()) {
                todo = null;
                getLogger().info("Done!");
                return;
            }
            if (populateDidHappen.remove(player.getUniqueId())) continue;
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
            for (Vec vec: todo) {
                int dist = Math.max(Math.abs(vec.x - chunkX), Math.abs(vec.z - chunkZ));
                if (nextVec == null || minDist > dist) {
                    nextVec = vec;
                    minDist = dist;
                }
            }
            if (minDist > 4) {
                anchors.put(player.getUniqueId(), nextVec);
            }
            todo.remove(nextVec);
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
            if (todo.size() % 1000 == 0) {
                printTodoProgressReport();
                if (todo.size() % 10000 == 0) {
                    getLogger().info("Saving todo...");
                    saveTodo();
                    getLogger().info("Saving world...");
                    world.save();
                    getLogger().info("Garbage collecting...");
                    Runtime.getRuntime().gc();
                    getLogger().info("Done");
                }
            }
        }
    }

    @EventHandler
    public void onChunkPopulate(ChunkPopulateEvent event) {
        if (todo == null) return;
        if (world == null && worldName == null) return;
        if (world == null) world = getServer().getWorld(worldName);
        if (!world.equals(event.getWorld())) return;
        Vec vec = new Vec(event.getChunk().getX(), event.getChunk().getZ());
        if (!todo.remove(vec)) return;
        Player causingPlayer = null;
        int minDist = 0;
        for (Player player: world.getPlayers()) {
            Chunk chunk = player.getLocation().getChunk();
            int dist = Math.max(Math.abs(vec.x - chunk.getX()), Math.abs(vec.z - chunk.getZ()));
            if (causingPlayer == null || dist < minDist) {
                causingPlayer = player;
                minDist = dist;
            }
        }
        if (causingPlayer != null) populateDidHappen.add(causingPlayer.getUniqueId());
        if (debug) {
            String playerName = causingPlayer == null ? "Unknown" : causingPlayer.getName();
            getLogger().info("POPULATE " + vec.x + " " + vec.z + " " + playerName);
        }
        if (todo.size() % 10000 == 0) {
            printTodoProgressReport();
            saveTodo();
            world.save();
        }
    }

    void printTodoProgressReport() {
        int done = total - todo.size();
        int percent = done * 100 / total;
        getLogger().info(String.format("%d/%d Chunks done (%d%%)", done, total, percent));
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
