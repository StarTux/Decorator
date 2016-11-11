package com.winthier.decorator;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

public class DecoratorPlugin extends JavaPlugin {
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 2 || args.length > 4) return false;
        final int size = Integer.parseInt(args[0]);
        final int interval = Integer.parseInt(args[1]);
        final Player player = (Player)sender;
        final int step = args.length >= 3 ? Integer.parseInt(args[2]) : 128;
        final DecoratorPlugin plugin = this;
        final boolean down;
        final int STEP;
        if (step < 0) {
            down = true;
            STEP = -step;
        } else {
            down = false;
            STEP = step;
        }
        final int sz = args.length >= 4 ? Integer.parseInt(args[3]) : (down ? size - STEP : -size + STEP);
        new BukkitRunnable() {
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
                        z -= STEP;
                        if (z < -size + STEP) {
                            cancel();
                            plugin.getLogger().info("Finis.");
                            return;
                        }
                    } else {
                        z += STEP;
                        if (z > size - STEP) {
                            cancel();
                            plugin.getLogger().info("Finis.");
                            return;
                        }
                    }
                    plugin.getLogger().info(player.getName() + " z=" + z);
                }
            }
        }.runTaskTimer(this, interval, interval);
        return true;
    }
}
