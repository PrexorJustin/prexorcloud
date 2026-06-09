package me.prexorjustin.prexorcloud.api.module.data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable, driver-agnostic index specification for {@link ModuleDataStore}.
 *
 * <pre>{@code
 * IndexSpec.asc("created_at")
 * IndexSpec.compound(Map.of("to_uuid", 1, "status", 1))
 * IndexSpec.compound(Map.of("blocker_uuid", 1, "blocked_uuid", 1)).unique()
 * }</pre>
 */
public record IndexSpec(Map<String, Integer> keys, boolean unique, String name) {

    /** Single-field ascending index. */
    public static IndexSpec asc(String... fields) {
        var keys = new LinkedHashMap<String, Integer>();
        for (var f : fields) keys.put(f, 1);
        return new IndexSpec(Map.copyOf(keys), false, null);
    }

    /** Single-field descending index. */
    public static IndexSpec desc(String... fields) {
        var keys = new LinkedHashMap<String, Integer>();
        for (var f : fields) keys.put(f, -1);
        return new IndexSpec(Map.copyOf(keys), false, null);
    }

    /** Compound index with explicit field-to-direction mapping. */
    public static IndexSpec compound(Map<String, Integer> keys) {
        return new IndexSpec(Map.copyOf(keys), false, null);
    }

    /** Return a copy marked as unique. */
    public IndexSpec asUnique() {
        return new IndexSpec(keys, true, name);
    }

    /** Return a copy with an explicit index name. */
    public IndexSpec withName(String name) {
        return new IndexSpec(keys, unique, name);
    }
}
