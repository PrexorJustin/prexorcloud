package me.prexorjustin.prexorcloud.api.module.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Fluent, driver-agnostic filter builder for {@link ModuleDataStore} queries.
 *
 * <pre>{@code
 * Query.where("status").eq("QUEUED").and("to_uuid").in(uuids)
 * Query.or(
 *     Query.where("from_uuid").eq(a).and("to_uuid").eq(b),
 *     Query.where("from_uuid").eq(b).and("to_uuid").eq(a))
 * }</pre>
 */
public final class Query {

    /** A single field-operator-value condition. */
    public record Condition(String field, String operator, Object value) {}

    public enum LogicalOp {
        AND,
        OR
    }

    private final List<Condition> conditions;
    private final LogicalOp combinator;
    private final List<Query> children;

    // -- internal state for builder chain --
    private String pendingField;

    private Query(LogicalOp combinator) {
        this.conditions = new ArrayList<>();
        this.combinator = combinator;
        this.children = new ArrayList<>();
    }

    // ── Entry points ────────────────────────────────────────────────────────

    /**
     * Start a filter with a field condition: {@code Query.where("name").eq("x")}.
     */
    public static Query where(String field) {
        var q = new Query(LogicalOp.AND);
        q.pendingField = field;
        return q;
    }

    /** Empty filter — matches all documents. */
    public static Query all() {
        return new Query(LogicalOp.AND);
    }

    /** Combine multiple queries with AND. */
    public static Query and(Query... queries) {
        var q = new Query(LogicalOp.AND);
        q.children.addAll(List.of(queries));
        return q;
    }

    /** Combine multiple queries with OR. */
    public static Query or(Query... queries) {
        var q = new Query(LogicalOp.OR);
        q.children.addAll(List.of(queries));
        return q;
    }

    // ── Operators (terminate a pending field) ────────────────────────────────

    public Query eq(Object value) {
        return addCondition("$eq", value);
    }

    public Query ne(Object value) {
        return addCondition("$ne", value);
    }

    public Query gt(Object value) {
        return addCondition("$gt", value);
    }

    public Query gte(Object value) {
        return addCondition("$gte", value);
    }

    public Query lt(Object value) {
        return addCondition("$lt", value);
    }

    public Query lte(Object value) {
        return addCondition("$lte", value);
    }

    public Query in(Collection<?> values) {
        return addCondition("$in", List.copyOf(values));
    }

    public Query exists(boolean exists) {
        return addCondition("$exists", exists);
    }

    public Query regex(String pattern) {
        return addCondition("$regex", pattern);
    }

    // ── Chaining ─────────────────────────────────────────────────────────────

    /** Chain another field condition: {@code .and("age").gt(18)}. */
    public Query and(String field) {
        requireNoPendingField();
        this.pendingField = field;
        return this;
    }

    // ── Accessors for the implementation ──────────────────────────────────────

    public List<Condition> conditions() {
        requireNoPendingField();
        return List.copyOf(conditions);
    }

    public LogicalOp combinator() {
        return combinator;
    }

    public List<Query> children() {
        return List.copyOf(children);
    }

    public boolean isEmpty() {
        return conditions.isEmpty() && children.isEmpty();
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private Query addCondition(String operator, Object value) {
        if (pendingField == null) {
            throw new IllegalStateException("No field set — call where() or and() first");
        }
        conditions.add(new Condition(pendingField, operator, value));
        pendingField = null;
        return this;
    }

    private void requireNoPendingField() {
        if (pendingField != null) {
            throw new IllegalStateException(
                    "Field '" + pendingField + "' has no operator — call eq(), in(), etc. before chaining");
        }
    }
}
