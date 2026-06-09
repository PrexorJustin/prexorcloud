package me.prexorjustin.prexorcloud.controller.rest.route;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import me.prexorjustin.prexorcloud.common.config.YamlConfigLoader;

/**
 * Shared helper for the runtime-mutable bits of {@code controller.yml}
 * (CORS allow-list, allowed subnets). Loads the YAML as a Map, navigates to a
 * nested list, performs an idempotent add/remove, and writes it back.
 * <p>
 * Each mutation reads-modifies-writes the whole file under an intrinsic lock
 * to avoid clobbering concurrent updates. The lock is local to this process,
 * so multiple controllers writing to the same on-disk file is still racy —
 * but that's a misconfiguration (one config file per controller) we don't try
 * to defend against.
 */
public final class ControllerYamlMutator {

    private static final Path CONFIG_PATH = Path.of("config", "controller.yml");
    private static final Object FILE_LOCK = new Object();

    private ControllerYamlMutator() {}

    /**
     * Idempotently add or remove {@code value} from the list at the dotted
     * {@code keyPath} (e.g. {@code "http.cors.allowedOrigins"}). Creates the
     * intermediate maps and the list if absent. Returns true when the file
     * actually changed (so callers can decide whether to log / surface a "no-op"
     * to the user).
     */
    @SuppressWarnings("unchecked")
    public static boolean upsertList(String keyPath, String value, boolean add) throws IOException {
        synchronized (FILE_LOCK) {
            Map<String, Object> root;
            if (Files.exists(CONFIG_PATH)) {
                root = YamlConfigLoader.mapper().readValue(CONFIG_PATH.toFile(), Map.class);
                if (root == null) root = new LinkedHashMap<>();
            } else {
                root = new LinkedHashMap<>();
            }
            String[] segments = keyPath.split("\\.");
            Map<String, Object> current = root;
            for (int i = 0; i < segments.length - 1; i++) {
                Object next = current.get(segments[i]);
                if (!(next instanceof Map)) {
                    next = new LinkedHashMap<String, Object>();
                    current.put(segments[i], next);
                }
                current = (Map<String, Object>) next;
            }
            String leaf = segments[segments.length - 1];
            Object existing = current.get(leaf);
            List<Object> list;
            if (existing instanceof List<?> l) {
                list = new ArrayList<>(l);
            } else {
                list = new ArrayList<>();
            }
            boolean changed;
            if (add) {
                changed = !list.contains(value);
                if (changed) list.add(value);
            } else {
                changed = list.remove(value);
            }
            if (changed) {
                current.put(leaf, list);
                YamlConfigLoader.mapper().writeValue(CONFIG_PATH.toFile(), root);
            }
            return changed;
        }
    }
}
