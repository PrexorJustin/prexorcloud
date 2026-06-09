package me.prexorjustin.prexorcloud.api.module.data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Fluent, driver-agnostic update builder for {@link ModuleDataStore} mutations.
 *
 * <pre>{@code
 * Update.set("status", "DELIVERED").set("delivered_at", now)
 * Update.setOnInsert("blocker_uuid", uuid).setOnInsert("created_at", now)
 * Update.inc("counter", 1)
 * }</pre>
 */
public final class Update {

    private final Map<String, Object> sets = new LinkedHashMap<>();
    private final Map<String, Object> setOnInserts = new LinkedHashMap<>();
    private final Map<String, Object> increments = new LinkedHashMap<>();
    private final Set<String> unsets = new LinkedHashSet<>();

    private Update() {}

    // ── Static entry points ──────────────────────────────────────────────────

    /** Start an update with a {@code $set} operation. */
    public static Update set(String field, Object value) {
        var u = new Update();
        u.sets.put(field, value);
        return u;
    }

    /** Start an update with a {@code $setOnInsert} operation (used with upsert). */
    public static Update setOnInsert(String field, Object value) {
        var u = new Update();
        u.setOnInserts.put(field, value);
        return u;
    }

    /** Start an update with an {@code $inc} operation. */
    public static Update inc(String field, Number value) {
        var u = new Update();
        u.increments.put(field, value);
        return u;
    }

    /** Start an update with an {@code $unset} operation. */
    public static Update unset(String field) {
        var u = new Update();
        u.unsets.add(field);
        return u;
    }

    // ── Instance chaining ────────────────────────────────────────────────────

    /** Chain another {@code $set} field. */
    public Update andSet(String field, Object value) {
        sets.put(field, value);
        return this;
    }

    /** Chain another {@code $setOnInsert} field. */
    public Update andSetOnInsert(String field, Object value) {
        setOnInserts.put(field, value);
        return this;
    }

    /** Chain another {@code $inc} field. */
    public Update andInc(String field, Number value) {
        increments.put(field, value);
        return this;
    }

    /** Chain another {@code $unset} field. */
    public Update andUnset(String field) {
        unsets.add(field);
        return this;
    }

    // ── Accessors for the implementation ──────────────────────────────────────

    public Map<String, Object> sets() {
        return Collections.unmodifiableMap(sets);
    }

    public Map<String, Object> setOnInserts() {
        return Collections.unmodifiableMap(setOnInserts);
    }

    public Map<String, Object> increments() {
        return Collections.unmodifiableMap(increments);
    }

    public Set<String> unsets() {
        return Collections.unmodifiableSet(unsets);
    }

    public boolean isEmpty() {
        return sets.isEmpty() && setOnInserts.isEmpty() && increments.isEmpty() && unsets.isEmpty();
    }
}
