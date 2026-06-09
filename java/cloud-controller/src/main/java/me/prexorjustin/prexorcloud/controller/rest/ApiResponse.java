package me.prexorjustin.prexorcloud.controller.rest;

import java.util.LinkedHashMap;
import java.util.List;

import io.javalin.http.Context;

/**
 * Standardised response helpers for the REST API.
 *
 * <ul>
 * <li>{@link #paginated} — wraps a list in {@code {data, page, pageSize,
 * total}}</li>
 * <li>{@link #page}/{@link #pageSize} — parse pagination query params</li>
 * <li>{@link #slice} — extract one page from an in-memory list</li>
 * </ul>
 */
public final class ApiResponse {

    private ApiResponse() {}

    /**
     * Writes a paginated list response.
     *
     * <pre>{@code
     * {
     *   "data": [ ... ],
     *   "page": 1,
     *   "pageSize": 100,
     *   "total": 42
     * }
     * }</pre>
     */
    public static void paginated(Context ctx, List<?> data, int total, int page, int pageSize) {
        var body = new LinkedHashMap<String, Object>(4);
        body.put("data", data);
        body.put("page", page);
        body.put("pageSize", pageSize);
        body.put("total", total);
        ctx.json(body);
    }

    /** Parse {@code ?page=} query param (1-based, default 1). */
    public static int page(Context ctx) {
        return Math.max(1, ctx.queryParamAsClass("page", Integer.class).getOrDefault(1));
    }

    /** Parse {@code ?pageSize=} query param, clamped to {@code [1, max]}. */
    public static int pageSize(Context ctx, int max) {
        int requested = ctx.queryParamAsClass("pageSize", Integer.class).getOrDefault(max);
        return Math.clamp(requested, 1, max);
    }

    /**
     * Returns a sublist for the given page. Safe for out-of-range pages (returns
     * empty list).
     */
    public static <T> List<T> slice(List<T> items, int page, int pageSize) {
        int from = Math.min((page - 1) * pageSize, items.size());
        int to = Math.min(from + pageSize, items.size());
        return items.subList(from, to);
    }

    /**
     * Convenience: parse params, slice, and write the paginated response in one
     * call.
     *
     * @param maxPageSize
     *            upper bound for pageSize (e.g. 500)
     */
    public static <T> void writeList(Context ctx, List<T> allItems, int maxPageSize) {
        int page = page(ctx);
        int pageSize = pageSize(ctx, maxPageSize);
        paginated(ctx, slice(allItems, page, pageSize), allItems.size(), page, pageSize);
    }
}
