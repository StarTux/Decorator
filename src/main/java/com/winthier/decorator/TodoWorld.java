package com.winthier.decorator;

import java.util.HashSet;
import java.util.Set;

/**
 * Represents the progress of decorating one world.
 * JSONable.
 */
public final class TodoWorld {
    Set<Vec> regions = new HashSet<>();
    Set<Vec> chunks = new HashSet<>();
    String world;
    int totalRegions;
    int totalChunks;
    boolean initialized;
    boolean done;
    int pass;
    int passes;
    boolean allChunks;
    // WorldBorder
    int lboundx;
    int lboundz;
    int uboundx;
    int uboundz;
}
