package me.prexorjustin.prexorcloud.daemon.process.prep;

import static me.prexorjustin.prexorcloud.daemon.process.prep.PreparationOps.blankToNull;
import static me.prexorjustin.prexorcloud.daemon.process.prep.PreparationOps.downloadToFile;
import static me.prexorjustin.prexorcloud.daemon.process.prep.PreparationOps.linkOrCopy;
import static me.prexorjustin.prexorcloud.daemon.process.prep.PreparationOps.stageFailure;
import static me.prexorjustin.prexorcloud.daemon.process.prep.PreparationOps.withPreparationRetries;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import me.prexorjustin.prexorcloud.daemon.template.ArtifactCache;
import me.prexorjustin.prexorcloud.daemon.template.JarCache;
import me.prexorjustin.prexorcloud.daemon.template.PaperBootstrapCache;
import me.prexorjustin.prexorcloud.protocol.StartPreparationStage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cache-backed artefact resolution + installation for an instance start.
 *
 * <p>Owns three caches that previously lived on {@code ProcessManager} as
 * peer fields:
 *
 * <ul>
 *   <li>{@link JarCache} — runtime jars (Paper, Velocity, etc.) keyed by
 *       {@code (platform, platformVersion, jarFile, sha256)}.
 *   <li>{@link ArtifactCache} — extension jars (module-scoped) keyed by
 *       {@code (moduleId, extensionId, variantId, sha256)}.
 *   <li>{@link PaperBootstrapCache} — Paper's first-launch bootstrap
 *       output, pre-warmed so cold-start latency stays in milliseconds.
 * </ul>
 *
 * <p>Every public method either returns a {@link Path} to an installed
 * artefact or installs in place. Throws {@link StartPreparationException}
 * with a {@link StartPreparationStage} tag so the caller can map the
 * failure to the right wire-format error code.
 *
 * <p>Stateless apart from the constructor-injected collaborators.
 */
public final class ArtifactProvisioner {

    private static final Logger logger = LoggerFactory.getLogger(ArtifactProvisioner.class);

    private final JarCache jarCache;
    private final ArtifactCache artifactCache;
    private final PaperBootstrapCache paperBootstrapCache;

    public ArtifactProvisioner(
            JarCache jarCache, ArtifactCache artifactCache, PaperBootstrapCache paperBootstrapCache) {
        this.jarCache = jarCache;
        this.artifactCache = artifactCache;
        this.paperBootstrapCache = paperBootstrapCache;
    }

    // ──────────────────────────────────────────────────────────────────
    // Runtime jar
    // ──────────────────────────────────────────────────────────────────

    /**
     * Resolve the instance's runtime jar — either cached, downloaded
     * fresh, or already present in the instance dir.
     *
     * @return path to the jar (may be inside the instance dir or inside
     *         the cache — caller decides whether to promote)
     */
    public Path resolveRuntimeArtifact(ResolvedStartSpec spec, Path instanceDir) throws StartPreparationException {
        try {
            return withPreparationRetries("runtime artifact provisioning for " + spec.instanceId(), () -> {
                Path jarPath = instanceDir.resolve(spec.jarFile());
                if (Files.exists(jarPath)) {
                    return jarPath;
                }
                if (spec.runtimeDownloadUrl().isBlank()) {
                    throw new IllegalStateException(
                            "Jar '" + spec.jarFile() + "' not found in instance dir and no download_url provided");
                }
                if (!spec.platform().isBlank() && !spec.platformVersion().isBlank()) {
                    return jarCache.resolve(
                            spec.platform(),
                            spec.platformVersion(),
                            spec.jarFile(),
                            spec.runtimeDownloadUrl(),
                            blankToNull(spec.runtimeSha256()));
                }

                Path tempJar = instanceDir.resolve("." + spec.jarFile() + ".download");
                downloadToFile(tempJar, spec.runtimeDownloadUrl(), blankToNull(spec.runtimeSha256()), spec.jarFile());
                return tempJar;
            });
        } catch (Exception e) {
            throw stageFailure(
                    StartPreparationStage.RUNTIME_PROVISION,
                    "RUNTIME_PROVISION_FAILED",
                    spec.planHash(),
                    "Failed to provision runtime artifact for " + spec.instanceId(),
                    e);
        }
    }

    /**
     * Promote a prepared runtime jar into its final on-disk location.
     * Uses {@link PreparationOps#linkOrCopy(Path, Path)} so the hardlink
     * path is taken when the filesystem supports it.
     */
    public void installPreparedRuntime(Path preparedRuntime, Path jarPath) throws StartPreparationException {
        try {
            withPreparationRetries("runtime install into " + jarPath.getFileName(), () -> {
                if (preparedRuntime.equals(jarPath)) {
                    return null;
                }
                Files.deleteIfExists(jarPath);
                linkOrCopy(preparedRuntime, jarPath);
                if (preparedRuntime.getParent().equals(jarPath.getParent())
                        && preparedRuntime.getFileName().toString().endsWith(".download")) {
                    Files.deleteIfExists(preparedRuntime);
                }
                return null;
            });
        } catch (Exception e) {
            throw stageFailure(
                    StartPreparationStage.RUNTIME_PROVISION,
                    "RUNTIME_INSTALL_FAILED",
                    "",
                    "Failed to install runtime artifact into instance directory",
                    e);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Extensions (module-scoped jars)
    // ──────────────────────────────────────────────────────────────────

    /**
     * Resolve every extension artefact declared on the spec into a cached
     * path. Returns paths in spec order so the caller can pair them with
     * {@link ResolvedStartSpec#extensions()} for installation.
     */
    public List<Path> resolveExtensionArtifacts(ResolvedStartSpec spec) throws StartPreparationException {
        try {
            return withPreparationRetries("extension artifact provisioning for " + spec.instanceId(), () -> {
                List<Path> cached = new ArrayList<>();
                for (ResolvedExtensionSpec extension : spec.extensions()) {
                    cached.add(artifactCache.resolve(
                            "extensions",
                            extension.moduleId() + "__" + extension.extensionId() + "__" + extension.variantId(),
                            extension.fileName(),
                            extension.downloadUrl(),
                            extension.sha256()));
                }
                return List.copyOf(cached);
            });
        } catch (Exception e) {
            throw stageFailure(
                    StartPreparationStage.EXTENSION_PROVISION,
                    "EXTENSION_PROVISION_FAILED",
                    spec.planHash(),
                    "Failed to provision extension artifacts for " + spec.instanceId(),
                    e);
        }
    }

    /**
     * Install resolved extension artefacts into their per-extension
     * {@code installPath} under the instance dir. Enforces zip-slip-style
     * containment — any install path that resolves outside the instance
     * dir throws {@link SecurityException} (which is non-retryable, so
     * surfaces as a {@code PERMANENT} failure).
     */
    public void installExtensionArtifacts(ResolvedStartSpec spec, List<Path> cachedExtensions, Path instanceDir)
            throws StartPreparationException {
        try {
            withPreparationRetries("extension install for " + spec.instanceId(), () -> {
                Path normalizedInstanceDir = instanceDir.toAbsolutePath().normalize();
                for (int i = 0; i < spec.extensions().size(); i++) {
                    ResolvedExtensionSpec extension = spec.extensions().get(i);
                    Path installRoot = extension.installPath().isBlank()
                            ? normalizedInstanceDir
                            : normalizedInstanceDir
                                    .resolve(extension.installPath())
                                    .normalize();
                    if (!installRoot.startsWith(normalizedInstanceDir)) {
                        throw new SecurityException(
                                "Extension install path escapes instance dir: " + extension.installPath());
                    }
                    Files.createDirectories(installRoot);
                    Path target = installRoot.resolve(extension.fileName()).normalize();
                    if (!target.startsWith(normalizedInstanceDir)) {
                        throw new SecurityException("Extension target escapes instance dir: " + target);
                    }
                    Files.deleteIfExists(target);
                    linkOrCopy(cachedExtensions.get(i), target);
                }
                return null;
            });
        } catch (Exception e) {
            throw stageFailure(
                    StartPreparationStage.EXTENSION_PROVISION,
                    "EXTENSION_INSTALL_FAILED",
                    spec.planHash(),
                    "Failed to install extension artifacts for " + spec.instanceId(),
                    e);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Paper bootstrap cache
    // ──────────────────────────────────────────────────────────────────

    /**
     * Best-effort pre-warm of the Paper bootstrap cache while other
     * preparation stages run in parallel. Failures are logged but never
     * propagate — the instance will bootstrap on first start instead.
     */
    public void preWarmBootstrap(ResolvedStartSpec spec) {
        if (!paperBootstrapCache.supports(spec.configFormat())
                || spec.platformVersion().isBlank()) {
            return;
        }
        try {
            if (!spec.platform().isBlank() && !spec.runtimeDownloadUrl().isBlank()) {
                Path cachedJar = jarCache.resolve(
                        spec.platform(),
                        spec.platformVersion(),
                        spec.jarFile(),
                        spec.runtimeDownloadUrl(),
                        blankToNull(spec.runtimeSha256()));
                paperBootstrapCache.ensureWarmed(spec.configFormat(), spec.platformVersion(), cachedJar);
            }
        } catch (Exception e) {
            logger.warn(
                    "Bootstrap cache warmup failed for {}/{}: {}",
                    spec.configFormat(),
                    spec.platformVersion(),
                    e.getMessage());
        }
    }

    /**
     * Apply the warm cache to a prepared instance dir. Same best-effort
     * contract as {@link #preWarmBootstrap(ResolvedStartSpec)}: failures
     * log + return; the instance will bootstrap on first start.
     */
    public void applyBootstrapCache(ResolvedStartSpec spec, Path preparedRuntime, Path instanceDir) {
        if (!paperBootstrapCache.supports(spec.configFormat())
                || spec.platformVersion().isBlank()) {
            return;
        }
        try {
            paperBootstrapCache.ensureWarmed(spec.configFormat(), spec.platformVersion(), preparedRuntime);
            paperBootstrapCache.applyTo(spec.configFormat(), spec.platformVersion(), instanceDir);
        } catch (Exception e) {
            logger.warn(
                    "Paper bootstrap cache failed for {}/{} -- instance will bootstrap on first start: {}",
                    spec.configFormat(),
                    spec.platformVersion(),
                    e.getMessage());
        }
    }
}
