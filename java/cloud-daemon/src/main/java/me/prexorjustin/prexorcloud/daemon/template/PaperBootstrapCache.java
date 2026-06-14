package me.prexorjustin.prexorcloud.daemon.template;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import me.prexorjustin.prexorcloud.common.cache.CacheEntries.BootstrapCacheInfo;
import me.prexorjustin.prexorcloud.daemon.process.JavaExecutable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Caches Paper's first-boot bootstrap artifacts (patched JARs, bundled
 * libraries, generated configuration files).
 *
 * <p>
 * Paper downloads the Mojang server JAR, decompiles it, applies patches, and
 * remaps plugins on every fresh start. This takes 30–60 seconds and is
 * identical for every instance using the same Paper version. This cache runs
 * the bootstrap once per version in a temporary directory, stores the generated
 * artifacts, and copies them into instance directories before launch -- making
 * subsequent starts nearly instant.
 *
 * <p>
 * The {@code config/} directory is included because Paper generates
 * configuration files (e.g. {@code paper-global.yml}) with a {@code _version}
 * key during bootstrap. Without the cached config, Paper would regenerate the
 * file on every first boot, overwriting template values with defaults. By
 * caching the generated config, Paper recognises the matching {@code _version}
 * and preserves the file as-is.
 *
 * <p>
 * Configuration patching (e.g. enabling Velocity forwarding) is handled
 * separately by {@link ServerConfigPatcher}, which runs after this cache is
 * applied.
 *
 * <p>
 * Additionally generates a CDS (Class Data Sharing) archive during the
 * bootstrap run. Paper loads thousands of classes on startup; the CDS archive
 * allows subsequent instances to map pre-parsed class metadata directly into
 * memory, saving 5–15 seconds of class loading per start.
 *
 * <p>
 * Cache layout:
 *
 * <pre>
 *   cache/paper-bootstrap/&lt;configFormat&gt;/&lt;version&gt;/cache/
 *   cache/paper-bootstrap/&lt;configFormat&gt;/&lt;version&gt;/versions/
 *   cache/paper-bootstrap/&lt;configFormat&gt;/&lt;version&gt;/libraries/
 *   cache/paper-bootstrap/&lt;configFormat&gt;/&lt;version&gt;/bundler/
 *   cache/paper-bootstrap/&lt;configFormat&gt;/&lt;version&gt;/config/
 *   cache/paper-bootstrap/&lt;configFormat&gt;/&lt;version&gt;/app-cds.jsa
 * </pre>
 */
public final class PaperBootstrapCache {

    private static final Logger logger = LoggerFactory.getLogger(PaperBootstrapCache.class);

    /**
     * Directories that Paper/Spigot generate during first-boot bootstrapping.
     */
    private static final Set<String> BOOTSTRAP_DIRS = Set.of("cache", "versions", "libraries", "bundler", "config");

    /**
     * Directories that are never modified by the server at runtime and can be
     * hardlinked instead of copied to save disk I/O on instance startup.
     */
    private static final Set<String> HARDLINK_SAFE_DIRS = Set.of("cache", "versions", "libraries", "bundler");

    /**
     * Config formats that use Paper-style bootstrapping (decompile, patch, remap).
     * Checked against {@code configFormat} (not {@code platform}) so that aliases
     * like "GAME" or "PURPUR" are handled correctly.
     */
    private static final Set<String> SUPPORTED_FORMATS = Set.of("paper", "spigot");

    private static final long BOOTSTRAP_TIMEOUT_SECONDS = 300;
    private static final String CDS_ARCHIVE_NAME = "app-cds.jsa";
    private static final String CDS_CLASS_LIST_NAME = "app-cds.classlist";
    private static final String CDS_JVM_VERSION_NAME = ".cds-jvm-version";

    /** The JVM version that generated CDS archives must match the running JVM. */
    private static final String CURRENT_JVM_VERSION = Runtime.version().toString();

    private final Path cacheDir;
    private final ConcurrentHashMap<String, Semaphore> locks = new ConcurrentHashMap<>();

    public PaperBootstrapCache(Path cacheDir) {
        this.cacheDir = cacheDir;
    }

    /**
     * Returns {@code true} if the given config format uses Paper-style
     * bootstrapping.
     */
    public boolean supports(String configFormat) {
        return configFormat != null && SUPPORTED_FORMATS.contains(configFormat.toLowerCase());
    }

    /**
     * Ensures bootstrap artifacts exist for the given config format and version. If
     * the cache is cold, runs Paper in a temporary directory to generate them.
     *
     * @param configFormat
     *            the config format (paper, spigot)
     * @param platformVersion
     *            the version string (e.g. 1.21.11)
     * @param jarPath
     *            path to the server JAR
     */
    public void ensureWarmed(String configFormat, String platformVersion, Path jarPath) throws Exception {
        String format = configFormat.toLowerCase();
        String key = format + "/" + platformVersion;
        Path versionDir = cacheDir.resolve(format).resolve(platformVersion);

        if (isCacheWarm(versionDir)) {
            logger.debug("Paper bootstrap cache hit: {}", key);
            // Check if CDS archive needs regeneration due to JVM upgrade
            ensureCdsCurrentJvm(versionDir, jarPath);
            return;
        }

        Semaphore lock = locks.computeIfAbsent(key, k -> new Semaphore(1));
        lock.acquireUninterruptibly();
        try {
            if (isCacheWarm(versionDir)) {
                ensureCdsCurrentJvm(versionDir, jarPath);
                return;
            }

            logger.info("Warming Paper bootstrap cache for {} -- this runs once per version", key);
            runBootstrap(format, platformVersion, jarPath, versionDir);
            logger.debug("Paper bootstrap cache warmed for {}", key);
        } finally {
            lock.release();
        }
    }

    /**
     * Copies cached bootstrap artifacts into the given instance directory.
     *
     * <p>
     * For the {@code config/} directory, files are copied with overwrite so that
     * Paper's generated configs (with correct {@code _version}) replace the
     * template's minimal stubs. This prevents Paper from regenerating configs on
     * instance startup. The template's {@code forwarding.secret} lives in the
     * instance root (not in {@code config/}), so it is unaffected by the overwrite.
     */
    public void applyTo(String configFormat, String platformVersion, Path instanceDir) throws IOException {
        if (!supports(configFormat)) return;

        Path versionDir = cacheDir.resolve(configFormat.toLowerCase()).resolve(platformVersion);
        if (!isCacheWarm(versionDir)) return;

        for (String dirName : BOOTSTRAP_DIRS) {
            Path source = versionDir.resolve(dirName);
            if (Files.isDirectory(source)) {
                Path target = instanceDir.resolve(dirName);
                // Config files must overwrite template stubs so Paper sees the correct _version
                boolean overwrite = "config".equals(dirName);
                if (HARDLINK_SAFE_DIRS.contains(dirName)) {
                    hardlinkDirectory(source, target);
                } else {
                    copyDirectory(source, target, overwrite);
                }
            }
        }
        // Copy CDS archive if it was built by the current JVM version.
        // Mismatched archives are silently ignored by the JVM, but skipping
        // avoids wasting I/O on a file that won't be used.
        Path cdsSource = versionDir.resolve(CDS_ARCHIVE_NAME);
        if (Files.exists(cdsSource) && isCdsCurrentJvm(versionDir)) {
            Path cdsDest = instanceDir.resolve(CDS_ARCHIVE_NAME);
            if (!Files.exists(cdsDest)) {
                try {
                    Files.createLink(cdsDest, cdsSource);
                } catch (UnsupportedOperationException | IOException _) {
                    Files.copy(cdsSource, cdsDest);
                }
            }
        }

        logger.debug("Applied Paper bootstrap cache to {}", instanceDir.getFileName());
    }

    /**
     * Returns the CDS archive filename if one exists in the given instance
     * directory. The caller should add {@code -XX:SharedArchiveFile=<name>} to the
     * JVM args.
     */
    public static String cdsArchiveName() {
        return CDS_ARCHIVE_NAME;
    }

    /**
     * Lists all cached bootstrap entries by walking the cache directory structure.
     */
    public List<BootstrapCacheInfo> listEntries() {
        var entries = new ArrayList<BootstrapCacheInfo>();
        if (!Files.isDirectory(cacheDir)) return entries;

        try (Stream<Path> formats = Files.list(cacheDir)) {
            formats.filter(Files::isDirectory)
                    .filter(d -> !d.getFileName().toString().startsWith("_")) // skip temp
                    // bootstrap
                    // dirs
                    .forEach(formatDir -> {
                        String configFormat = formatDir.getFileName().toString();
                        try (Stream<Path> versions = Files.list(formatDir)) {
                            versions.filter(Files::isDirectory).forEach(versionDir -> {
                                if (!isCacheWarm(versionDir)) return;
                                String version = versionDir.getFileName().toString();
                                boolean hasCds = Files.exists(versionDir.resolve(CDS_ARCHIVE_NAME))
                                        && isCdsCurrentJvm(versionDir);
                                long size = directorySize(versionDir);
                                entries.add(new BootstrapCacheInfo(configFormat, version, hasCds, size));
                            });
                        } catch (IOException _) {
                            logger.debug("Failed to list bootstrap format dir: {}", formatDir);
                        }
                    });
        } catch (IOException e) {
            logger.warn("Failed to list bootstrap cache entries: {}", e.getMessage());
        }
        return entries;
    }

    private static long directorySize(Path dir) {
        var total = new AtomicLong(0);
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile).forEach(f -> {
                try {
                    total.addAndGet(Files.size(f));
                } catch (IOException _) {
                }
            });
        } catch (IOException _) {
        }
        return total.get();
    }

    private static boolean isCdsCurrentJvm(Path versionDir) {
        try {
            Path jvmVersionFile = versionDir.resolve(CDS_JVM_VERSION_NAME);
            return Files.exists(jvmVersionFile)
                    && CURRENT_JVM_VERSION.equals(
                            Files.readString(jvmVersionFile).trim());
        } catch (IOException _) {
            return false;
        }
    }

    private boolean isCacheWarm(Path versionDir) {
        Path versionsDir = versionDir.resolve("versions");
        if (!Files.isDirectory(versionsDir)) return false;
        try (Stream<Path> entries = Files.list(versionsDir)) {
            return entries.findAny().isPresent();
        } catch (IOException _) {
            return false;
        }
    }

    /**
     * Checks if the cached CDS archive was generated by the current JVM version. If
     * not, regenerates it using the saved class list (fast — seconds, not minutes).
     */
    private void ensureCdsCurrentJvm(Path versionDir, Path jarPath) {
        try {
            Path jvmVersionFile = versionDir.resolve(CDS_JVM_VERSION_NAME);
            if (Files.exists(jvmVersionFile)) {
                String cachedVersion = Files.readString(jvmVersionFile).trim();
                if (CURRENT_JVM_VERSION.equals(cachedVersion)) {
                    return; // CDS archive matches current JVM
                }
                logger.info(
                        "CDS archive was built with JVM {} but running JVM {} -- regenerating",
                        cachedVersion,
                        CURRENT_JVM_VERSION);
            }

            Path classListPath = versionDir.resolve(CDS_CLASS_LIST_NAME);
            if (!Files.exists(classListPath)) {
                logger.warn(
                        "No class list found for CDS regeneration -- skipping (delete cache to force full rebuild)");
                return;
            }

            regenerateCds(versionDir, jarPath, classListPath);
        } catch (Exception e) {
            logger.warn("CDS regeneration failed: {} -- instances will start without CDS", e.getMessage());
        }
    }

    /**
     * Regenerates just the CDS archive from a saved class list using
     * {@code -Xshare:dump}. This takes seconds instead of the 30–60s full
     * bootstrap.
     */
    private void regenerateCds(Path versionDir, Path jarPath, Path classListPath) throws Exception {
        Path cdsArchive = versionDir.resolve(CDS_ARCHIVE_NAME);
        Path tempArchive = versionDir.resolve(CDS_ARCHIVE_NAME + ".tmp");

        ProcessBuilder pb = new ProcessBuilder(
                JavaExecutable.path(),
                "-Xshare:dump",
                "--add-opens",
                "java.base/java.lang=ALL-UNNAMED",
                "-XX:SharedClassListFile=" + classListPath.toAbsolutePath(),
                "-XX:SharedArchiveFile=" + tempArchive.toAbsolutePath(),
                "-cp",
                jarPath.toAbsolutePath().toString());
        pb.redirectErrorStream(true);

        Process process = pb.start();
        // Drain output to prevent blocking
        Thread.startVirtualThread(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.debug("[cds-regen] {}", line);
                }
            } catch (IOException _) {
                /* process ended */ }
        });

        boolean exited = process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            Files.deleteIfExists(tempArchive);
            throw new IOException("CDS regeneration timed out");
        }

        if (process.exitValue() != 0) {
            Files.deleteIfExists(tempArchive);
            throw new IOException("CDS regeneration failed with exit code " + process.exitValue());
        }

        // Atomic replace so running instances with hardlinks to the old archive aren't
        // affected
        Files.move(tempArchive, cdsArchive, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Files.writeString(versionDir.resolve(CDS_JVM_VERSION_NAME), CURRENT_JVM_VERSION);
        logger.debug(
                "CDS archive regenerated for JVM {} ({} MB)",
                CURRENT_JVM_VERSION,
                Files.size(cdsArchive) / (1024 * 1024));
    }

    /**
     * Runs the Paper JAR in a local bootstrap directory to generate all artifacts.
     *
     * <p>
     * The directory is created under the cache root (not in {@code %TEMP%}) so that
     * Paper behaves identically to a real instance — same filesystem, same path
     * structure. Paper 1.21+ only generates {@code config/paper-global.yml} during
     * full server startup, and some JVMs behave differently when the working
     * directory is on a different volume.
     */
    private void runBootstrap(String configFormat, String platformVersion, Path jarPath, Path versionDir)
            throws Exception {
        Path bootstrapDir = cacheDir.resolve("_bootstrap-" + configFormat + "-" + platformVersion);
        // Clean any leftover from a previous failed attempt
        if (Files.isDirectory(bootstrapDir)) {
            deleteDirectory(bootstrapDir);
        }
        Files.createDirectories(bootstrapDir);

        try {
            Files.writeString(bootstrapDir.resolve("eula.txt"), "eula=true\n");

            Path bootstrapJar = bootstrapDir.resolve("server.jar");
            Files.copy(jarPath, bootstrapJar);

            // -XX:DumpLoadedClassList captures the class list for later CDS archive
            // generation. We deliberately do NOT use -XX:ArchiveClassesAtExit here
            // because it produces a dynamic archive layered on the JDK's default
            // static archive. That default archive was built without --add-opens,
            // causing a property mismatch at instance runtime and disabling CDS.
            // Instead we capture the class list now and generate a proper static
            // archive afterwards via regenerateCds() with matching flags.
            Path classList = bootstrapDir.resolve(CDS_CLASS_LIST_NAME);
            ProcessBuilder pb = new ProcessBuilder(
                    JavaExecutable.path(),
                    "-Xmx512m",
                    "-Xms512m",
                    "--add-opens",
                    "java.base/java.lang=ALL-UNNAMED",
                    "-Dcom.mojang.eula.agree=true",
                    "-XX:DumpLoadedClassList=" + classList.toAbsolutePath(),
                    "-jar",
                    "server.jar",
                    "--nogui",
                    "--port",
                    "0");
            pb.directory(bootstrapDir.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();

            Thread monitor = Thread.startVirtualThread(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        logger.debug("[bootstrap-{}] {}", configFormat, line);
                        if (line.contains("Done (")) {
                            logger.debug("Paper {} bootstrap complete, stopping temp server", platformVersion);
                            process.getOutputStream().write("stop\n".getBytes());
                            process.getOutputStream().flush();
                            break;
                        }
                    }
                } catch (IOException _) {
                    // Process ended
                }
            });

            boolean exited = process.waitFor(BOOTSTRAP_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            if (!exited) {
                logger.warn("Paper bootstrap timed out after {}s -- killing", BOOTSTRAP_TIMEOUT_SECONDS);
                process.destroyForcibly();
            }
            monitor.join(5000);

            Path bootstrapVersions = bootstrapDir.resolve("versions");
            if (!Files.isDirectory(bootstrapVersions)) {
                throw new IOException(
                        "Paper bootstrap did not produce versions/ directory -- patching may have failed");
            }

            // Verify config generation
            Path paperConfig = bootstrapDir.resolve("config").resolve("paper-global.yml");
            if (Files.exists(paperConfig)) {
                logger.debug("Bootstrap generated config/paper-global.yml");
            } else {
                logger.warn(
                        "Bootstrap did NOT generate config/paper-global.yml -- velocity forwarding must be configured manually");
            }

            // Patch enabled + online-mode into the cached config so instances get
            // the correct defaults. The forwarding secret is instance-specific and
            // is patched later by ServerConfigPatcher.patch() at launch time.
            ServerConfigPatcher.patchPaperGlobalConfig(bootstrapDir, true);

            // Copy generated artifacts to cache
            Files.createDirectories(versionDir);
            for (String dirName : BOOTSTRAP_DIRS) {
                Path source = bootstrapDir.resolve(dirName);
                if (Files.isDirectory(source)) {
                    Path target = versionDir.resolve(dirName);
                    copyDirectory(source, target, false);
                }
            }

            // Copy class list to cache and generate a static CDS archive via -Xshare:dump.
            // A static archive embeds the --add-opens flags directly, avoiding the
            // property mismatch that dynamic archives inherit from the JDK base archive.
            if (Files.exists(classList)) {
                Files.copy(classList, versionDir.resolve(CDS_CLASS_LIST_NAME));
                try {
                    regenerateCds(versionDir, jarPath, versionDir.resolve(CDS_CLASS_LIST_NAME));
                } catch (Exception e) {
                    logger.warn(
                            "CDS archive generation failed: {} -- instances will start without CDS", e.getMessage());
                }
            } else {
                logger.warn("Class list was not generated -- JVM may not support -XX:DumpLoadedClassList");
            }
        } finally {
            deleteDirectory(bootstrapDir);
        }
    }

    /**
     * Creates hardlinks for all files in the source directory tree. Hardlinks are
     * near-instant and share disk blocks with the cache, avoiding the cost of
     * copying hundreds of MB of read-only libraries. Falls back to regular copy if
     * hardlinking fails (e.g. cross-device).
     */
    private static void hardlinkDirectory(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path dest = target.resolve(source.relativize(file));
                if (!Files.exists(dest)) {
                    try {
                        Files.createLink(dest, file);
                    } catch (UnsupportedOperationException | IOException _) {
                        // Cross-device or filesystem doesn't support hardlinks — fall back to copy
                        Files.copy(file, dest);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void copyDirectory(Path source, Path target, boolean overwrite) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path dest = target.resolve(source.relativize(file));
                if (overwrite) {
                    Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
                } else if (!Files.exists(dest)) {
                    Files.copy(file, dest);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteDirectory(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException _) {
                    /* best-effort */ }
            });
        } catch (IOException _) {
            // best-effort
        }
    }
}
