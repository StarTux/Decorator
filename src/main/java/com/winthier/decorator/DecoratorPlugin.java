package com.winthier.decorator;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

public class DecoratorPlugin extends JavaPlugin {
    final Map<UUID, BukkitRunnable> tasks = new HashMap<>();

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        final Player player = sender instanceof Player ? (Player)sender : null;
        if (player == null) return false;
        if (args.length == 0) {
            return false;
        } else if (args.length == 1 && ("cancel".equalsIgnoreCase(args[0]) ||
                                        "stop".equalsIgnoreCase(args[0]))) {
            BukkitRunnable task = tasks.remove(player.getUniqueId());
            if (task == null) {
                sender.sendMessage("No job running");
            } else {
                try {
                    task.cancel();
                } catch (IllegalStateException ise) {}
                sender.sendMessage("Job cancelled");
            }
        } else if (args.length >= 2 && args.length <= 5) {
            final int size = Integer.parseInt(args[0]);
            final int interval = Integer.parseInt(args[1]);
            final int stepX = args.length >= 3 ? Integer.parseInt(args[2]) : 128;
            final int stepZ = args.length >= 4 ? Integer.parseInt(args[3]) : 128;
            final DecoratorPlugin plugin = this;
            final boolean down;
            final int STEP;
            if (stepX < 0) {
                down = true;
                STEP = -stepX;
            } else {
                down = false;
                STEP = stepX;
            }
            final int STEPZ = Math.abs(stepZ);
            final int sz = args.length >= 4 ? Integer.parseInt(args[4]) : (down ? size - STEPZ : -size + STEPZ);
            BukkitRunnable task;
            task = tasks.remove(player.getUniqueId());
            if (task != null) {
                try {
                    task.cancel();
                } catch (IllegalStateException ise) {}
            }
            task = new BukkitRunnable() {
                int x = -size + STEP;
                int z = sz;
                @Override public void run() {
                    if (!player.isValid()) {
                        cancel();
                        return;
                    }
                    World world = player.getWorld();
                    Block block = world.getHighestBlockAt(x, z);
                    player.teleport(block.getLocation().add(0.5, 0.5, 0.5));
                    x += STEP;
                    if (x > size - STEP) {
                        x = -size + STEP;
                        world.save();
                        if (down) {
                            z -= STEPZ;
                            if (z < -size + STEPZ) {
                                cancel();
                                plugin.getLogger().info("Finis.");
                                return;
                            }
                        } else {
                            z += STEPZ;
                            if (z > size - STEPZ) {
                                cancel();
                                plugin.getLogger().info("Finis.");
                                return;
                            }
                        }
                        plugin.getLogger().info(player.getName() + " z=" + z);
                    }
                }
            };
            tasks.put(player.getUniqueId(), task);
            task.runTaskTimer(this, interval, interval);
            sender.sendMessage("Job started. Size=" + size + " Interval=" + interval + " StepX=" + stepX + " StepZ=" + stepZ);
        }
        return true;
    }
}
