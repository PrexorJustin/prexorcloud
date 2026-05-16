package me.prexorjustin.prexorcloud.api.client.version;

/**
 * Tier-1 version dispatch: instantiates the best-matching nested adapter class
 * annotated with {@link ForVersion}.
 *
 * <p>
 * Decoupled from {@code org.bukkit.Bukkit} — takes a plain version string (e.g.
 * {@code "1.21.4"}) which the platform bridge supplies via
 * {@code Bukkit.getServer().getBukkitVersion()} or equivalent.
 *
 * <h3>Selection order</h3>
 * <ol>
 * <li>All nested classes of the container annotated with {@code @ForVersion}
 * whose version range includes the running server are collected.</li>
 * <li>The class with the highest {@code min} version wins (greedy
 * best-fit).</li>
 * <li>If no class matches the range, a class annotated with
 * {@code @ForVersion(fallback = true)} is used.</li>
 * <li>If neither exists, {@link UnsupportedOperationException} is thrown with a
 * message listing what versions are covered.</li>
 * </ol>
 *
 * <p>
 * Every dispatch decision is logged at {@code FINE} level via JUL so server
 * administrators can confirm which implementation was chosen at startup.
 */
public final class VersionDispatcher {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(VersionDispatcher.class.getName());

    private final int major;
    private final int minor;
    private final String versionString;

    /**
     * @param runningVersion
     *            e.g. {@code "1.21.4"}, {@code "1.20"}, or
     *            {@code "1.21.4-R0.1-SNAPSHOT"}
     */
    public VersionDispatcher(String runningVersion) {
        int[] parsed = parseVersion(runningVersion);
        this.major = parsed[0];
        this.minor = parsed[1];
        this.versionString = runningVersion;
    }

    public int major() {
        return major;
    }

    public int minor() {
        return minor;
    }

    public String versionString() {
        return versionString;
    }

    /** Returns true if the running version is {@code >= version}. */
    public boolean atLeast(String version) {
        int[] v = parseVersion(version);
        return major > v[0] || (major == v[0] && minor >= v[1]);
    }

    /** Returns true if the running version is {@code <= version}. */
    public boolean atMost(String version) {
        int[] v = parseVersion(version);
        return major < v[0] || (major == v[0] && minor <= v[1]);
    }

    public boolean matches(String min, String max) {
        return atLeast(min) && (max.isEmpty() || atMost(max));
    }

    /**
     * Tier-1 dispatch: scans nested classes of {@code container} for the best
     * {@link ForVersion} match that implements {@code type}.
     */
    @SuppressWarnings("unchecked")
    public <T> T resolve(Class<T> type, Class<?> container) {
        Class<?> best = null;
        int bestMajor = -1, bestMinor = -1;
        Class<?> fallback = null;
        StringBuilder covered = new StringBuilder();

        for (Class<?> nested : container.getDeclaredClasses()) {
            if (!type.isAssignableFrom(nested)) continue;
            ForVersion ann = nested.getAnnotation(ForVersion.class);
            if (ann == null) continue;

            // Collect fallback — used only when no versioned class matches.
            if (ann.fallback()) {
                fallback = nested;
                continue;
            }

            // Track which ranges are covered (for the error message).
            if (!covered.isEmpty()) covered.append(", ");
            covered.append(ann.min()).append(ann.max().isEmpty() ? "+" : "–" + ann.max());

            if (!matches(ann.min(), ann.max())) continue;

            int[] v = parseVersion(ann.min());
            if (best == null || v[0] > bestMajor || (v[0] == bestMajor && v[1] > bestMinor)) {
                best = nested;
                bestMajor = v[0];
                bestMinor = v[1];
            }
        }

        // Pass 1: a versioned class matched — use it.
        if (best != null) {
            final Class<?> selected = best;
            LOG.fine(() -> "[VersionDispatch] " + type.getSimpleName() + " → " + selected.getSimpleName() + " (server="
                    + versionString + ")");
            return instantiate(selected);
        }

        // Pass 2: no versioned match — fall back to the @ForVersion(fallback=true)
        // class.
        if (fallback != null) {
            final Class<?> fb = fallback;
            LOG.fine(() -> "[VersionDispatch] " + type.getSimpleName() + " → " + fb.getSimpleName()
                    + " (fallback, server=" + versionString + " not in [" + covered + "])");
            return instantiate(fb);
        }

        throw new UnsupportedOperationException("No @ForVersion adapter found for " + type.getSimpleName() + " in "
                + container.getSimpleName() + " on Minecraft " + versionString + ". Covered: [" + covered
                + "]. Add @ForVersion(fallback=true) to handle unknown versions.");
    }

    @SuppressWarnings("unchecked")
    private <T> T instantiate(Class<?> cls) {
        try {
            return (T) cls.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to instantiate adapter " + cls.getSimpleName(), e);
        }
    }

    /**
     * Tier-1 shortcut: {@code type} itself acts as both type and container. Nested
     * classes of {@code type} are scanned for {@link ForVersion}.
     */
    public <T> T resolve(Class<T> type) {
        return resolve(type, type);
    }

    /**
     * Parses a Minecraft version string into {@code [major, minor]}.
     * <p>
     * Examples: {@code "1.21.4"} → {@code [21, 4]}, {@code "1.20"} →
     * {@code [20, 0]}, {@code "1.21.4-R0.1-SNAPSHOT"} → {@code [21, 4]}.
     */
    static int[] parseVersion(String v) {
        String[] parts = v.split("[^0-9]+");
        int idx = 0;
        int major = 0, minor = 0;
        for (String part : parts) {
            if (part.isEmpty()) continue;
            try {
                int val = Integer.parseInt(part);
                if (idx == 0 && val == 1) {
                    idx++;
                    continue;
                } // skip leading "1" in "1.21.4"
                if (idx == 1 || (idx == 0)) {
                    major = val;
                    idx = 2;
                } else if (idx == 2) {
                    minor = val;
                    break;
                }
            } catch (NumberFormatException ignored) {
                // skip non-numeric segments
            }
        }
        return new int[] {major, minor};
    }
}
