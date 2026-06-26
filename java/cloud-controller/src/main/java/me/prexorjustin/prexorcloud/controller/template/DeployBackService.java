package me.prexorjustin.prexorcloud.controller.template;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import me.prexorjustin.prexorcloud.api.module.capability.InstanceFileAccess;
import me.prexorjustin.prexorcloud.controller.state.InstanceInfo;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * "Deploy-back": capture a RUNNING instance's current config files into a new version of an existing
 * template, so future instances that compose the template inherit that state (CloudNet's "copy service to
 * template").
 *
 * <p><b>Config-only by design.</b> Only text/config files (by extension) are captured, each bounded by the
 * {@link InstanceFileAccess} 64 KiB read cap. Binary/world data is deliberately out of scope — it
 * round-trips lossily over the UTF-8 file RPC (the capability's own javadoc says to filter by extension); a
 * daemon-side tar capability is the future path for world snapshots. Skipped files are reported, never
 * silently dropped.
 */
public final class DeployBackService {

    private static final Logger logger = LoggerFactory.getLogger(DeployBackService.class);

    // Extensions safe to round-trip through the UTF-8 file RPC. Anything else is skipped so deploy-back
    // never writes a lossy binary (server.jar, world region files, …) into a template.
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "yml", "yaml", "properties", "json", "toml", "conf", "cfg", "config", "txt", "xml", "ini", "env",
            "sh", "lang", "mcmeta", "md");

    /** Outcome of a deploy-back: the template version produced + per-file accounting. */
    public record DeployBackResult(String template, String hash, int filesWritten, List<String> skipped) {}

    private final InstanceFileAccess fileAccess;
    private final TemplateManager templateManager;

    public DeployBackService(InstanceFileAccess fileAccess, TemplateManager templateManager) {
        this.fileAccess = fileAccess;
        this.templateManager = templateManager;
    }

    /**
     * Capture {@code instance}'s config files into a new version of {@code templateName}. The instance must
     * be RUNNING and the template must already exist; {@code pathPrefix} (nullable) limits capture to files
     * under that relative prefix. Returns the new template hash plus which files were written vs skipped.
     *
     * @throws IllegalArgumentException the template does not exist
     * @throws IllegalStateException the instance is not RUNNING, the file listing failed, or nothing was captured
     * @throws IOException writing a file or rehashing the template failed
     */
    public DeployBackResult saveToTemplate(InstanceInfo instance, String templateName, String pathPrefix)
            throws IOException {
        if (instance.state() != InstanceState.RUNNING) {
            throw new IllegalStateException("Instance " + instance.id() + " is not RUNNING (" + instance.state() + ")");
        }
        if (!templateManager.exists(templateName)) {
            throw new IllegalArgumentException("Template not found: " + templateName);
        }

        InstanceFileAccess.InstanceFileTree tree = fileAccess.walk(instance.nodeId(), instance.group(), instance.id());
        if (!tree.ok()) {
            throw new IllegalStateException(
                    "Could not list files for instance " + instance.id() + ": " + tree.error());
        }

        Path filesDir = templateManager.getTemplateFilesDir(templateName).normalize();
        String prefix = pathPrefix == null ? "" : pathPrefix.strip();
        int written = 0;
        List<String> skipped = new ArrayList<>();

        for (InstanceFileAccess.InstanceFileEntry entry : tree.entries()) {
            if (entry.isDir()) {
                continue;
            }
            String rel = entry.path();
            if (!prefix.isEmpty() && !rel.startsWith(prefix)) {
                continue;
            }
            if (!isTextFile(rel)) {
                skipped.add(rel + " (binary/non-config)");
                continue;
            }
            Path target = filesDir.resolve(rel).normalize();
            if (!target.startsWith(filesDir)) {
                skipped.add(rel + " (unsafe path)");
                continue;
            }
            InstanceFileAccess.InstanceFileBytes bytes =
                    fileAccess.read(instance.nodeId(), instance.group(), instance.id(), rel, 0);
            if (!bytes.ok()) {
                skipped.add(rel + " (" + bytes.error() + ")");
                continue;
            }
            if (bytes.truncated()) {
                skipped.add(rel + " (too large: " + bytes.totalSizeBytes() + " bytes)");
                continue;
            }
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(target, bytes.content(), StandardCharsets.UTF_8);
            written++;
        }

        if (written == 0) {
            throw new IllegalStateException("No config files captured from instance " + instance.id()
                    + (skipped.isEmpty() ? "" : " (skipped " + skipped.size() + ")"));
        }

        templateManager.rehash(templateName);
        String hash = templateManager.get(templateName).map(TemplateConfig::hash).orElse("");
        logger.info(
                "Deploy-back: captured {} config file(s) from instance {} into template {} (hash {}, {} skipped)",
                written,
                instance.id(),
                templateName,
                hash.isEmpty() ? "?" : hash.substring(0, Math.min(8, hash.length())),
                skipped.size());
        return new DeployBackResult(templateName, hash, written, List.copyOf(skipped));
    }

    private static boolean isTextFile(String relPath) {
        int slash = Math.max(relPath.lastIndexOf('/'), relPath.lastIndexOf('\\'));
        String name = slash >= 0 ? relPath.substring(slash + 1) : relPath;
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return false; // no extension — skip to avoid capturing unknown blobs
        }
        return TEXT_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
