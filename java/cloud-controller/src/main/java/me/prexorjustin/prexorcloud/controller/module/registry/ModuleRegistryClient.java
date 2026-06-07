package me.prexorjustin.prexorcloud.controller.module.registry;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Talks to the operator-configured set of module registries (see {@code
 * modules.registries}): aggregates their indexes for browse/search, resolves a
 * {@code moduleId@version} to a concrete artifact, and downloads + integrity-
 * checks the JAR and its signature sidecar onto disk for the existing install
 * path to verify and load.
 *
 * <p><b>Trust model.</b> The registry is a discovery convenience, never a trust
 * anchor. Two independent checks gate an install: (1) the downloaded JAR's
 * SHA-256 must equal the {@code sha256} pinned in the index (done here), and
 * (2) the JAR's cosign/sig sidecar must verify against the controller's own
 * configured trust root (done later by {@code PlatformModuleSignatureVerifier}
 * during {@code PlatformModuleManager.install}). A compromised registry can at
 * worst serve a different-but-still-validly-signed module; it cannot get
 * arbitrary code installed.
 *
 * <p><b>SSRF.</b> Only operator-configured registry URLs are aggregated, and
 * artifact URLs are fetched only via {@link RegistryFetcher}, which rejects
 * non-http(s) schemes. The install REST surface additionally pins any
 * caller-supplied {@code registryUrl} to the configured allow-list.
 */
public final class ModuleRegistryClient {

    private static final Logger logger = LoggerFactory.getLogger(ModuleRegistryClient.class);

    private final List<String> registryUrls;
    private final RegistryFetcher fetcher;
    private final ObjectMapper mapper;

    public ModuleRegistryClient(List<String> registryUrls, RegistryFetcher fetcher, ObjectMapper mapper) {
        this.registryUrls = registryUrls == null ? List.of() : List.copyOf(registryUrls);
        this.fetcher = fetcher;
        this.mapper = mapper;
    }

    /** A registry entry paired with the registry URL it came from. */
    public record ResolvedEntry(String registryUrl, String registryName, RegistryModuleEntry entry) {}

    public List<String> configuredRegistries() {
        return registryUrls;
    }

    /**
     * Fetch and merge every configured registry's index. A registry that fails to
     * fetch or parse is logged and skipped — one broken registry must not blind the
     * operator to the others.
     */
    public List<ResolvedEntry> aggregate() {
        List<ResolvedEntry> all = new ArrayList<>();
        for (String registryUrl : registryUrls) {
            try {
                RegistryIndex index = fetchIndex(registryUrl);
                for (RegistryModuleEntry entry : index.modules()) {
                    all.add(new ResolvedEntry(registryUrl, index.name(), entry));
                }
            } catch (RuntimeException | IOException e) {
                logger.warn("skipping registry {} — {}", registryUrl, e.getMessage());
            }
        }
        return all;
    }

    /** Case-insensitive substring match over moduleId and tags. Empty query returns everything. */
    public List<ResolvedEntry> search(String query) {
        if (query == null || query.isBlank()) {
            return aggregate();
        }
        String needle = query.toLowerCase(Locale.ROOT);
        return aggregate().stream()
                .filter(r -> r.entry().moduleId().toLowerCase(Locale.ROOT).contains(needle)
                        || r.entry().tags().stream()
                                .anyMatch(t -> t.toLowerCase(Locale.ROOT).contains(needle)))
                .toList();
    }

    /**
     * Resolve a single artifact. {@code version} may be null/blank or {@code
     * "latest"} to select the highest semver. {@code registryUrl} may be null to
     * search all configured registries, or pin to one (which must be configured —
     * the REST layer enforces that before calling).
     *
     * @throws ModuleRegistryException {@code MODULE_NOT_FOUND} if nothing matches
     */
    public ResolvedEntry resolve(String moduleId, String version, String registryUrl) {
        List<ResolvedEntry> candidates = aggregate().stream()
                .filter(r -> r.entry().moduleId().equals(moduleId))
                .filter(r -> registryUrl == null || registryUrl.equals(r.registryUrl()))
                .toList();
        if (candidates.isEmpty()) {
            throw new ModuleRegistryException(
                    "MODULE_NOT_FOUND",
                    "no module '" + moduleId + "' in "
                            + (registryUrl == null ? "any configured registry" : registryUrl));
        }
        boolean latest = version == null || version.isBlank() || version.equalsIgnoreCase("latest");
        if (latest) {
            return candidates.stream()
                    .max(Comparator.comparing(r -> r.entry().version(), ModuleRegistryClient::compareSemver))
                    .orElseThrow();
        }
        return candidates.stream()
                .filter(r -> version.equals(r.entry().version()))
                .findFirst()
                .orElseThrow(() -> new ModuleRegistryException(
                        "MODULE_NOT_FOUND", "module '" + moduleId + "' has no version " + version));
    }

    /**
     * Download {@code entry}'s JAR (and its signature sidecar) into {@code destDir},
     * verifying the JAR SHA-256 against the index. Returns the JAR path; the sidecar
     * is written adjacent with the {@code .cosign.bundle} / {@code .sig} suffix the
     * store auto-discovers during install.
     *
     * @throws ModuleRegistryException on fetch failure or SHA-256 mismatch
     */
    public Path download(RegistryModuleEntry entry, Path destDir) {
        if (entry.jarUrl() == null || entry.jarUrl().isBlank()) {
            throw new ModuleRegistryException("MISSING_JAR_URL", "registry entry has no jarUrl");
        }
        byte[] jarBytes = fetch(entry.jarUrl(), "jar");
        String actualSha = sha256Hex(jarBytes);
        if (entry.sha256() == null || entry.sha256().isBlank()) {
            throw new ModuleRegistryException(
                    "MISSING_SHA256", "registry entry for " + entry.moduleId() + " has no sha256 to verify against");
        }
        if (!actualSha.equalsIgnoreCase(entry.sha256())) {
            throw new ModuleRegistryException(
                    "SHA256_MISMATCH",
                    "downloaded " + entry.moduleId() + "@" + entry.version() + " sha256=" + actualSha
                            + " does not match index sha256=" + entry.sha256());
        }
        try {
            Path jarPath = destDir.resolve(safeName(entry.moduleId()) + ".jar");
            Files.write(jarPath, jarBytes);
            // Sidecar — prefer the cosign bundle; the install path discovers it by suffix.
            if (entry.hasCosignBundle()) {
                byte[] bundle = fetch(entry.cosignBundleUrl(), "cosign.bundle");
                Files.write(jarPath.resolveSibling(jarPath.getFileName() + ".cosign.bundle"), bundle);
            } else if (entry.hasSig()) {
                byte[] sig = fetch(entry.sigUrl(), "sig");
                Files.write(jarPath.resolveSibling(jarPath.getFileName() + ".sig"), sig);
            }
            return jarPath;
        } catch (IOException e) {
            throw new ModuleRegistryException(
                    "DOWNLOAD_WRITE_FAILED", "could not write downloaded artifact: " + e.getMessage(), e);
        }
    }

    private RegistryIndex fetchIndex(String registryUrl) throws IOException {
        byte[] body = fetch(registryUrl, "index");
        try {
            return mapper.readValue(body, RegistryIndex.class);
        } catch (IOException e) {
            throw new ModuleRegistryException(
                    "INDEX_PARSE_FAILED",
                    "registry index at " + registryUrl + " is not valid JSON: " + e.getMessage(),
                    e);
        }
    }

    private byte[] fetch(String url, String what) {
        try {
            return fetcher.get(URI.create(url));
        } catch (IllegalArgumentException e) {
            throw new ModuleRegistryException("BAD_URL", "malformed " + what + " URL: " + url, e);
        } catch (IOException e) {
            throw new ModuleRegistryException(
                    "FETCH_FAILED", "could not fetch " + what + " from " + url + ": " + e.getMessage(), e);
        }
    }

    /** Reduce a module id to a filename-safe token (it is already constrained, but be defensive). */
    private static String safeName(String moduleId) {
        String cleaned = moduleId.replaceAll("[^a-zA-Z0-9._-]", "_");
        return cleaned.isBlank() ? "module" : cleaned;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Compare two version strings by dotted numeric segments (1.2.10 &gt; 1.2.9),
     * falling back to lexicographic comparison for non-numeric / pre-release
     * suffixes. Good enough for "pick the latest" — not a full semver implementation.
     */
    static int compareSemver(String a, String b) {
        String[] as = splitVersion(a);
        String[] bs = splitVersion(b);
        int n = Math.max(as.length, bs.length);
        for (int i = 0; i < n; i++) {
            Optional<Integer> ai = numeric(i < as.length ? as[i] : null);
            Optional<Integer> bi = numeric(i < bs.length ? bs[i] : null);
            if (ai.isPresent() && bi.isPresent()) {
                int cmp = Integer.compare(ai.get(), bi.get());
                if (cmp != 0) return cmp;
            } else {
                String av = i < as.length ? as[i] : "";
                String bv = i < bs.length ? bs[i] : "";
                int cmp = av.compareTo(bv);
                if (cmp != 0) return cmp;
            }
        }
        return 0;
    }

    private static String[] splitVersion(String v) {
        if (v == null || v.isBlank()) return new String[] {"0"};
        return v.trim().split("[.+-]");
    }

    private static Optional<Integer> numeric(String s) {
        if (s == null || s.isEmpty()) return Optional.empty();
        try {
            return Optional.of(Integer.parseInt(s));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
