package me.prexorjustin.prexorcloud.daemon.process;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves the {@code java} launcher used to spawn server and bootstrap
 * processes.
 *
 * <p>
 * The daemon is started from an explicit JRE (see the systemd unit's
 * {@code ExecStart=.../jre/bin/java}), but the host PATH frequently has no
 * {@code java} on it at all. Spawning a child with a bare {@code "java"} then
 * fails with {@code error=2 (No such file or directory)}. We instead resolve the
 * launcher from the running JVM's {@code java.home}, which is always present.
 * </p>
 */
public final class JavaExecutable {

    private JavaExecutable() {}

    /**
     * Absolute path to the {@code java} launcher of the JVM running this daemon,
     * or the bare string {@code "java"} as a last resort if it cannot be
     * resolved (preserving the previous behaviour rather than failing here).
     */
    public static String path() {
        String javaHome = System.getProperty("java.home");
        if (javaHome != null && !javaHome.isBlank()) {
            String exe = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
            Path candidate = Path.of(javaHome, "bin", exe);
            if (Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath().toString();
            }
        }
        return "java";
    }
}
