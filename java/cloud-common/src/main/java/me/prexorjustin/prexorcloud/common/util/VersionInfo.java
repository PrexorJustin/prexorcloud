package me.prexorjustin.prexorcloud.common.util;

import java.io.InputStream;
import java.util.Properties;

/**
 * Build-time and runtime version metadata.
 *
 * <p>
 * Loaded lazily from {@code version.properties} on the classpath via the
 * holder-class idiom: the inner {@code Holder} is initialized once on first
 * access, thread-safely, with no synchronization on subsequent reads.
 * </p>
 */
public record VersionInfo(String version, String gitCommit, String javaVersion) {

    private static final System.Logger logger = System.getLogger(VersionInfo.class.getName());

    private static final class Holder {
        private static final VersionInfo INSTANCE = load();
    }

    /**
     * Compact constructor that normalises null or blank values to safe defaults,
     * ensuring no downstream null checks are needed.
     */
    public VersionInfo {
        version = isBlank(version) ? "dev" : version;
        gitCommit = isBlank(gitCommit) ? "unknown" : gitCommit;
        javaVersion = isBlank(javaVersion) ? "unknown" : javaVersion;
    }

    /**
     * Returns the singleton version info, loading it on first access.
     *
     * @return the version metadata for this build
     */
    public static VersionInfo get() {
        return Holder.INSTANCE;
    }

    private static VersionInfo load() {
        Properties props = new Properties();
        try (InputStream in = VersionInfo.class.getClassLoader().getResourceAsStream("version.properties")) {
            if (in == null) {
                logger.log(System.Logger.Level.WARNING, "version.properties not found on classpath; using defaults");
            } else {
                props.load(in);
            }
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Failed to load version.properties: {0}", e.getMessage());
        }
        return new VersionInfo(
                props.getProperty("version"), props.getProperty("git.commit"), System.getProperty("java.version"));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
