package me.prexorjustin.prexorcloud.api.module.data;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Immutable, driver-agnostic sort specification for {@link ModuleDataStore}
 * queries.
 *
 * <pre>{@code
 * Sort.asc("created_at")
 * Sort.desc("created_at").thenAsc("name")
 * Sort.none()
 * }</pre>
 */
public final class Sort {

    private static final Sort NONE = new Sort(List.of());

    private final List<Map.Entry<String, Integer>> fields;

    private Sort(List<Map.Entry<String, Integer>> fields) {
        this.fields = fields;
    }

    /** Ascending sort on a single field. */
    public static Sort asc(String field) {
        return new Sort(List.of(entry(field, 1)));
    }

    /** Descending sort on a single field. */
    public static Sort desc(String field) {
        return new Sort(List.of(entry(field, -1)));
    }

    /** No sort order. */
    public static Sort none() {
        return NONE;
    }

    /** Append ascending sort on another field. */
    public Sort thenAsc(String field) {
        var copy = new ArrayList<>(fields);
        copy.add(entry(field, 1));
        return new Sort(List.copyOf(copy));
    }

    /** Append descending sort on another field. */
    public Sort thenDesc(String field) {
        var copy = new ArrayList<>(fields);
        copy.add(entry(field, -1));
        return new Sort(List.copyOf(copy));
    }

    /** Ordered list of (field, direction) pairs. 1 = ascending, -1 = descending. */
    public List<Map.Entry<String, Integer>> fields() {
        return fields;
    }

    public boolean isEmpty() {
        return fields.isEmpty();
    }

    private static Map.Entry<String, Integer> entry(String field, int direction) {
        return new AbstractMap.SimpleImmutableEntry<>(field, direction);
    }
}
