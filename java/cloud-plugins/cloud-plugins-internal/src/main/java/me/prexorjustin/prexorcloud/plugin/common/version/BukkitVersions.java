package me.prexorjustin.prexorcloud.plugin.common.version;

import org.bukkit.Bukkit;

/**
 * Parses and exposes the running Minecraft/Bukkit version as numeric
 * major/minor components, evaluated once at class-load time.
 * <p>
 * Example: for {@code "1.20.4"}, {@code major()==20}, {@code minor()==4}.
 * <p>
 * Usage in module plugins:
 *
 * <pre>{@code
 * if (BukkitVersions.atLeast(20, 4)) { // 1.20.4+ code }
 * }</pre>
 */
public final class BukkitVersions {

    private static final int MAJOR;
    private static final int MINOR;

    static {
        String ver = Bukkit.getMinecraftVersion(); // e.g. "1.21.4"
        String[] parts = ver.split("\\.");
        int major = 0, minor = 0;
        try {
            if (parts.length >= 2) {
                major = Integer.parseInt(parts[1]);
                minor = parts.length >= 3 ? Integer.parseInt(parts[2]) : 0;
            }
        } catch (NumberFormatException ignored) {
        }
        MAJOR = major;
        MINOR = minor;
    }

    private BukkitVersions() {}

    /** Minor version number. For {@code 1.20.4}, returns {@code 20}. */
    public static int major() {
        return MAJOR;
    }

    /** Patch version number. For {@code 1.20.4}, returns {@code 4}. */
    public static int minor() {
        return MINOR;
    }

    /** Returns true if the running server is at least the given version. */
    public static boolean atLeast(int major, int minor) {
        return MAJOR > major || (MAJOR == major && MINOR >= minor);
    }

    /** Returns the full version string as parsed from Bukkit. */
    public static String version() {
        return "1." + MAJOR + "." + MINOR;
    }
}
