package com.winthier.decorator;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.Value;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class DecoratorPlugin extends JavaPlugin {
    private Set<Vec> todo;
    private int total;
    private boolean paused;
    private final Map<UUID, Vec> anchors = new HashMap<>();

    @Value
    final class Vec {
        private final int x, z;
    }

    @Override
    public void onEnable() {
        reloadConfig();
        int interval = getConfig().getInt("interval");
        getLogger().info("Interval " + interval);
        getServer().getScheduler().runTaskTimer(this, () -> onTick(), interval, interval);
        loadTodo();
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
        if (cmd.equals("init") && args.length == 1) {
            World world = getServer().getWorlds().get(0);
            Location center = world.getWorldBorder().getCenter();
            double size = world.getWorldBorder().getSize();
            if (size > 100000) {
                sender.sendMessage("World border too large!");
                return true;
            }
            Chunk min = center.add(size * -0.5, 0, size * -0.5).getChunk();
            Chunk max = center.add(size * 0.5, 0, size * 0.5).getChunk();
            todo = new HashSet<>();
            for (int z = min.getZ() + 1; z < max.getZ(); z += 1) {
                for (int x = min.getX() + 1; x < max.getX(); x += 1) {
                    todo.add(new Vec(x, z));
                }
            }
            total = todo.size();
            sender.sendMessage("" + todo.size() + " chunks scheduled.");
        } else if (cmd.equals("pause") && args.length == 1) {
            paused = !paused;
            if (paused) {
                sender.sendMessage("Decorator paused");
            } else {
                sender.sendMessage("Decorator unpaused");
            }
        } else if (cmd.equals("reload") && args.length == 1) {
            loadTodo();
            sender.sendMessage("Reloaded");
        } else if (cmd.equals("cancel") && args.length == 1) {
            todo = null;
            saveTodo();
            sender.sendMessage("Canceled");
        } else if (cmd.equals("info") && args.length == 1) {
            if (todo == null) {
                sender.sendMessage("Not active");
            } else {
                int done = total - todo.size();
                int percent = done * 100 / total;
                sender.sendMessage(String.format("%d/%d Chunks done (%d%%)", done, total, percent));
            }
            if (paused) sender.sendMessage("Paused");
        } else if (cmd.equals("save") && args.length == 1) {
            saveTodo();
            sender.sendMessage("Saved");
        } else {
            return false;
        }
        return true;
    }

    void loadTodo() {
        todo = null;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "todo.yml"));
        if (!config.isSet("todo")) return;
        todo = new HashSet<>();
        Iterator<Integer> iter = config.getIntegerList("todo").iterator();
        while (iter.hasNext()) {
            todo.add(new Vec(iter.next(), iter.next()));
        }
        total = config.getInt("total");
        getLogger().info("" + todo.size() + " todos loaded");
    }

    void saveTodo() {
        YamlConfiguration config = new YamlConfiguration();
        if (todo != null) {
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
        if (paused || todo == null || todo.isEmpty()) return;
        World world = getServer().getWorlds().get(0);
        for (Player player: getServer().getOnlinePlayers()) {
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
                int dist = Math.max(Math.abs(vec.x - chunkX),
                                    Math.abs(vec.z - chunkZ));
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
            player.teleport(location);
            if (todo.size() % 1000 == 0) {
                int done = total - todo.size();
                int percent = done * 100 / total;
                getLogger().info(String.format("%d/%d Chunks done (%d%%)", done, total, percent));
                saveTodo();
                world.save();
            }
        }
        if (todo.isEmpty()) {
            todo = null;
            getLogger().info("Done!");
        }
    }
}
