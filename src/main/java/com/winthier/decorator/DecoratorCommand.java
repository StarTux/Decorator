package com.winthier.decorator;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

@RequiredArgsConstructor
public final class DecoratorCommand implements CommandExecutor {
    private final DecoratorPlugin plugin;

    public DecoratorCommand enable() {
        plugin.getCommand("decorator").setExecutor(this);
        return this;
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
                    theWorld = Bukkit.getWorld(args[1]);
                    if (theWorld == null) {
                        sender.sendMessage("World not found: " + args[1] + "!");
                        return true;
                    }
                } else {
                    theWorld = Bukkit.getWorlds().get(0);
                }
                boolean all = args.length >= 3 && args[2].equals("all");
                plugin.todo = new Todo();
                TodoWorld todoWorld = new TodoWorld();
                todoWorld.world = theWorld.getName();
                todoWorld.allChunks = all;
                plugin.todo.worlds.add(todoWorld);
                try {
                    plugin.initWorld(todoWorld, theWorld);
                } catch (IllegalStateException ise) {
                    sender.sendMessage("Error: " + ise.getMessage());
                    return true;
                }
                sender.sendMessage("" + todoWorld.totalRegions + " regions scheduled.");
                plugin.saveTodo();
                return true;
            }
            break;
        case "pause":
            if (args.length == 1) {
                plugin.paused = !plugin.paused;
                if (plugin.paused) {
                    sender.sendMessage("Decorator paused");
                } else {
                    sender.sendMessage("Decorator unpaused");
                }
                return true;
            }
            break;
        case "debug":
            if (args.length == 1) {
                plugin.debug = !plugin.debug;
                if (plugin.debug) {
                    sender.sendMessage("Debug mode enabled");
                } else {
                    sender.sendMessage("Debug mode disabled");
                }
                return true;
            }
            break;
        case "reload":
            if (args.length == 1) {
                plugin.importConfig();
                sender.sendMessage("Config Reloaded");
                return true;
            }
            break;
        case "cancel":
            if (args.length == 1) {
                plugin.todo.worlds.clear();
                plugin.saveTodo();
                sender.sendMessage("Cancelled");
                return true;
            }
            break;
        case "info":
            if (args.length != 1) return false;
            printInfo(sender);
            return true;
        case "players":
            if (args.length == 1) {
                List<Player> ps = new ArrayList<>(Bukkit.getOnlinePlayers());
                sender.sendMessage("" + ps.size() + " Players:");
                int i = 0;
                for (Player p: ps) {
                    Meta meta = plugin.metaOf(p);
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
                plugin.saveTodo();
                if (plugin.world != null) plugin.world.save();
                sender.sendMessage("Todo and world saved");
                return true;
            }
            break;
        case "fake":
            if (args.length == 2) {
                plugin.mcProtocolLib.spawnFakePlayer(plugin, args[1]);
                sender.sendMessage("Fake user logged in.");
                return true;
            }
            break;
        case "shutdown": {
            if (args.length != 1)  return false;
            plugin.doShutdown = true;
            sender.sendMessage("Shutdown scheduled");
            return true;
        }
        default:
            break;
        }
        return false;
    }

    void printInfo(CommandSender sender) {
        for (TodoWorld todoWorld : plugin.todo.worlds) {
            int total = todoWorld.totalRegions;
            int done = total - todoWorld.regions.size();
            int percent = total > 0 ? done * 100 / total : 0;
            String fmt = String.format("%s pass:%d/%d region:%d/%d (%d%%) chunks:%d/%d",
                                       todoWorld.world,
                                       todoWorld.pass, todoWorld.passes,
                                       done, total, percent,
                                       todoWorld.totalChunks - todoWorld.chunks.size(),
                                       todoWorld.totalChunks);
            sender.sendMessage(fmt);
        }
        if (plugin.currentRegion != null) {
            String fmt = String.format("Current region: %s %d %d",
                                       (plugin.world != null ? plugin.world.getName() : "?"),
                                       plugin.currentRegion.x, plugin.currentRegion.z);
            sender.sendMessage(fmt);
        }
        sender.sendMessage("TickCooldown=" + plugin.tickCooldown
                           + " PlayerPopulateInterval=" + plugin.playerPopulateInterval
                           + " FakePlayers=" + plugin.fakePlayers
                           + " MemoryThreshold=" + plugin.memoryThreshold + "MiB"
                           + " MemoryWaitTime=" + plugin.memoryWaitTime + "s"
                           + " BatchMode=" + (plugin.batchMode ? "enabled" : "disabled")
                           + " Millis/Tick=" + plugin.millisecondsPerTick
                           + " Batch=" + plugin.batchMode
                           + " Free=" + (plugin.freeMem() / 1024 / 1024) + "MiB"
                           + " Paused=" + plugin.paused
                           + " Pending=" + plugin.chunksPending
                           + "/" + (plugin.chunksPendingCooldown - System.currentTimeMillis()) + "ms"
                           + " Shutdown=" + plugin.doShutdown);
    }
}
