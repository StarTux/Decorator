package com.winthier.decorator;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Called when the decorator considers a chunk for decoration.
 * Every chunk in the generated world is intended to be called exactly
 * once, after it has been fully populated.
 *
 * Invocation should go along with chunks.remove() in DecoratorPlugin.
 */
@RequiredArgsConstructor
public final class DecoratorEvent extends Event {
    @Getter private final Chunk chunk;
    @Getter private final int pass;

    static void call(Chunk chunk, int pass) {
        DecoratorEvent event = new DecoratorEvent(chunk, pass);
        Bukkit.getServer().getPluginManager().callEvent(event);
    }

    // Event Stuff

    @Getter private static HandlerList handlerList = new HandlerList();

    @Override
    public HandlerList getHandlers() {
        return handlerList;
    }
}
