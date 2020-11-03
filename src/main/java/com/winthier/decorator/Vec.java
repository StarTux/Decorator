package com.winthier.decorator;

import lombok.Value;

/**
 * JSONable.
 */
@Value
final class Vec {
    public static final Vec ZERO = new Vec(0, 0);
    public final int x;
    public final int z;

    @Override
    public String toString() {
        return x + "," + z;
    }
}
