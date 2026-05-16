package me.prexorjustin.prexorcloud.controller.group;

import java.util.Locale;
import java.util.Set;

public enum GroupRuntimeFamily {
    SERVER,
    PROXY,
    UNKNOWN;

    private static final Set<String> PROXY_PLATFORMS = Set.of("BUNGEECORD", "WATERFALL", "VELOCITY");

    public static GroupRuntimeFamily fromPlatform(String platform) {
        if (platform == null || platform.isBlank()) {
            return UNKNOWN;
        }
        return PROXY_PLATFORMS.contains(platform.toUpperCase(Locale.ROOT)) ? PROXY : SERVER;
    }
}
