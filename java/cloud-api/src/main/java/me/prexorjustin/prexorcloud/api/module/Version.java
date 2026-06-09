package me.prexorjustin.prexorcloud.api.module;

import java.util.Objects;

/**
 * A lenient semver-flavored version. Accepts 1-3 numeric parts and an optional
 * pre-release suffix after a dash. Build metadata after a plus sign is ignored
 * for comparison but preserved in {@link #raw()}.
 *
 * <p>Designed to be forgiving of Minecraft version strings like {@code "1.20"},
 * which normalize to {@code 1.20.0}.
 */
public final class Version implements Comparable<Version> {

    private final int major;
    private final int minor;
    private final int patch;
    private final String preRelease;
    private final String raw;

    private Version(int major, int minor, int patch, String preRelease, String raw) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.preRelease = preRelease;
        this.raw = raw;
    }

    public static Version of(int major, int minor, int patch) {
        return new Version(major, minor, patch, null, major + "." + minor + "." + patch);
    }

    public static Version parse(String input) {
        if (input == null) {
            throw new IllegalArgumentException("version must not be null");
        }
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("version must not be empty");
        }

        String core = trimmed;
        String pre = null;
        int plus = core.indexOf('+');
        if (plus >= 0) {
            core = core.substring(0, plus);
        }
        int dash = core.indexOf('-');
        if (dash >= 0) {
            pre = core.substring(dash + 1);
            core = core.substring(0, dash);
            if (pre.isEmpty()) {
                throw new IllegalArgumentException("empty pre-release suffix in: " + input);
            }
        }

        String[] parts = core.split("\\.", -1);
        if (parts.length < 1 || parts.length > 3) {
            throw new IllegalArgumentException("version must have 1-3 numeric parts: " + input);
        }
        int major = parseNumber(parts[0], input);
        int minor = parts.length > 1 ? parseNumber(parts[1], input) : 0;
        int patch = parts.length > 2 ? parseNumber(parts[2], input) : 0;
        return new Version(major, minor, patch, pre, trimmed);
    }

    private static int parseNumber(String s, String original) {
        if (s.isEmpty()) {
            throw new IllegalArgumentException("empty numeric part in version: " + original);
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                throw new IllegalArgumentException("non-numeric version part '" + s + "' in: " + original);
            }
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("numeric overflow in version: " + original, e);
        }
    }

    public int major() {
        return major;
    }

    public int minor() {
        return minor;
    }

    public int patch() {
        return patch;
    }

    public String preRelease() {
        return preRelease;
    }

    public String raw() {
        return raw;
    }

    @Override
    public int compareTo(Version other) {
        int c = Integer.compare(major, other.major);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(minor, other.minor);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(patch, other.patch);
        if (c != 0) {
            return c;
        }
        // 1.0.0 > 1.0.0-rc1 per semver precedence
        if (preRelease == null && other.preRelease == null) {
            return 0;
        }
        if (preRelease == null) {
            return 1;
        }
        if (other.preRelease == null) {
            return -1;
        }
        return preRelease.compareTo(other.preRelease);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Version v)) {
            return false;
        }
        return major == v.major && minor == v.minor && patch == v.patch && Objects.equals(preRelease, v.preRelease);
    }

    @Override
    public int hashCode() {
        return Objects.hash(major, minor, patch, preRelease);
    }

    @Override
    public String toString() {
        return preRelease == null
                ? major + "." + minor + "." + patch
                : major + "." + minor + "." + patch + "-" + preRelease;
    }
}
