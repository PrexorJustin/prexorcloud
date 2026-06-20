package me.prexorjustin.prexorcloud.daemon.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Best-effort on-disk cache of controller endpoints the cluster has advertised at runtime, so a daemon
 * recovers even if every controller in its config seed list was decommissioned before it last
 * restarted. Stored as a small JSON array of {@code "host:port"} strings (NOT under the 0700 cert dir).
 *
 * <p>Every operation is best-effort: a missing, empty, or corrupt file yields an empty list, and a
 * failed write is logged and swallowed — the daemon always still has its configured seeds.</p>
 */
public final class LearnedControllersStore {

    private static final Logger logger = LoggerFactory.getLogger(LearnedControllersStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_PORT = 9090;

    private final Path path;

    public LearnedControllersStore(Path path) {
        this.path = path;
    }

    /** Load persisted endpoints, dropping unparseable and loopback/wildcard entries. Never throws. */
    public List<ControllerEndpoint> load() {
        if (!Files.exists(path)) {
            return List.of();
        }
        try {
            List<String> raw = MAPPER.readValue(Files.readAllBytes(path), new TypeReference<List<String>>() {});
            var out = new ArrayList<ControllerEndpoint>();
            for (String s : raw) {
                ControllerEndpoint ep = ControllerEndpoint.parse(s, DEFAULT_PORT);
                if (ep != null && !ep.isLoopbackOrWildcard()) {
                    out.add(ep);
                }
            }
            return out;
        } catch (Exception e) {
            logger.warn("Ignoring unreadable learned-controllers cache {}: {}", path, e.getMessage());
            return List.of();
        }
    }

    /** Persist endpoints via a temp file + rename (atomic on the same filesystem). Never throws. */
    public void save(List<ControllerEndpoint> endpoints) {
        if (endpoints == null) {
            return;
        }
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            List<String> raw =
                    endpoints.stream().map(ControllerEndpoint::toString).toList();
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            MAPPER.writeValue(tmp.toFile(), raw);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            logger.warn("Failed to persist learned-controllers cache {}: {}", path, e.getMessage());
        }
    }
}
