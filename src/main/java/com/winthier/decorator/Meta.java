package com.winthier.decorator;

import org.bukkit.Location;

final class Meta {
    int populateCooldown = 0;
    Vec anchor = null;
    // DecoratorPlugin::tickPlayer
    boolean warping;
    Location warpLocation;
}
