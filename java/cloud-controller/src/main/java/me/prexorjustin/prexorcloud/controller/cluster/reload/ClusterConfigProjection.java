package me.prexorjustin.prexorcloud.controller.cluster.reload;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterConfigVersion;

/**
 * Folds the append-only chain of {@link ClusterConfigVersion} patches into a
 * single effective config map.
 *
 * <p>Each version stores a <em>patch</em> against its {@code parentVersion}, not
 * a full snapshot (only version 1 — the v1.0 migration seed — is effectively a
 * full map). To materialise the effective config we walk the parent chain from
 * the active version back to the root, then deep-merge the patches root → active.
 *
 * <p>Walking the <em>parent chain</em> rather than {@code 1..active} is what
 * makes rollbacks correct: after a rollback the active pointer references an
 * earlier version, and a subsequent patch is parented on that earlier version —
 * so version numbers branch while the parent chain stays linear. Folding by
 * version number would re-apply patches that the rollback discarded.
 *
 * <p>Merge semantics are deep for nested maps and replace-wins for everything
 * else (scalars and lists are treated as atomic values). This mirrors how the
 * config sections are shaped — {@code http.cors.allowedOrigins} is a whole-list
 * replacement, never an element-wise merge. Deletion semantics (RFC 7396 null
 * tombstones) are intentionally not implemented; the patch surface does not
 * expose them.
 */
public final class ClusterConfigProjection {

    private ClusterConfigProjection() {}

    /**
     * Materialise the effective config for {@code activeVersion} by folding the
     * parent chain of {@code allVersions}. Returns an empty map when there is no
     * active version (version 0) or the chain cannot be resolved.
     *
     * @param allVersions every known config version (order irrelevant — indexed by number)
     * @param activeVersion the version the cluster currently points at
     */
    public static Map<String, Object> fold(List<ClusterConfigVersion> allVersions, int activeVersion) {
        if (activeVersion <= 0 || allVersions == null || allVersions.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<Integer, ClusterConfigVersion> byVersion = new LinkedHashMap<>();
        for (ClusterConfigVersion v : allVersions) {
            byVersion.put(v.version(), v);
        }

        // Walk parent links from active back to the root, guarding against cycles
        // and dangling parents so a malformed chain degrades to "best effort fold"
        // instead of an infinite loop.
        Deque<ClusterConfigVersion> rootToActive = new ArrayDeque<>();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        int cursor = activeVersion;
        while (cursor > 0 && seen.add(cursor)) {
            ClusterConfigVersion v = byVersion.get(cursor);
            if (v == null) {
                break;
            }
            rootToActive.addFirst(v);
            cursor = v.parentVersion();
        }

        Map<String, Object> effective = new LinkedHashMap<>();
        for (ClusterConfigVersion v : rootToActive) {
            if (v.patch() != null) {
                merge(effective, v.patch());
            }
        }
        return effective;
    }

    @SuppressWarnings("unchecked")
    private static void merge(Map<String, Object> base, Map<String, Object> patch) {
        for (Map.Entry<String, Object> entry : patch.entrySet()) {
            String key = entry.getKey();
            Object patchValue = entry.getValue();
            Object baseValue = base.get(key);
            if (patchValue instanceof Map<?, ?> patchMap && baseValue instanceof Map<?, ?>) {
                merge((Map<String, Object>) baseValue, castStringKeys(patchMap));
            } else {
                base.put(key, mutableCopy(patchValue));
            }
        }
    }

    /** Deep mutable copy so later merges can recurse into nested maps without aliasing the source patch. */
    private static Object mutableCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                copy.put(String.valueOf(e.getKey()), mutableCopy(e.getValue()));
            }
            return copy;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castStringKeys(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
