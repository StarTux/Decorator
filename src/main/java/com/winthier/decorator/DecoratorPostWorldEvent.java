package com.winthier.decorator;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.bukkit.World;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Will be called for every in the todo.postWorlds list, once per
 * tick, until not cancelled.
 */
@RequiredArgsConstructor
public final class DecoratorPostWorldEvent extends Event implements Cancellable {
    @Getter private final World world;
    @Getter private final int pass;
    @Getter @Setter private boolean cancelled;

    @Getter private static HandlerList handlerList = new HandlerList();

    @Override
    public HandlerList getHandlers() {
        return handlerList;
    }
}
