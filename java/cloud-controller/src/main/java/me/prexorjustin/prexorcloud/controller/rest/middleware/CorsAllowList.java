package me.prexorjustin.prexorcloud.controller.rest.middleware;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Thread-safe mutable allow-list of CORS origins.
 * <p>
 * Lives for the controller process lifetime. {@link DynamicCorsHandler} reads
 * it on every request; admin routes (e.g. the dashboard installer's PATCH)
 * mutate it. Writes are protected by an intrinsic lock; reads use a volatile
 * reference to an immutable {@code List<String>}, so the hot request path is
 * lock-free and any caller's snapshot is stable for the duration of one
 * request.
 * <p>
 * Why this exists: Javalin's bundled CORS plugin captures origins at app
 * construction time and offers no runtime mutation hook. We replace it so the
 * dashboard installer can register a new origin and have it take effect on
 * the very next request — no controller restart.
 */
public final class CorsAllowList {

    private volatile List<String> origins;

    public CorsAllowList(List<String> initial) {
        this.origins = List.copyOf(Objects.requireNonNullElseGet(initial, List::of));
    }

    public boolean allows(String origin) {
        if (origin == null || origin.isBlank()) return false;
        return origins.contains(origin);
    }

    /** Returns true if the origin was added (false if it was already present). */
    public synchronized boolean add(String origin) {
        if (origin == null || origin.isBlank()) return false;
        if (origins.contains(origin)) return false;
        var next = new ArrayList<>(origins);
        next.add(origin);
        this.origins = List.copyOf(next);
        return true;
    }

    /** Returns true if the origin was removed (false if it wasn't present). */
    public synchronized boolean remove(String origin) {
        if (origin == null || origin.isBlank()) return false;
        if (!origins.contains(origin)) return false;
        var next = new ArrayList<>(origins);
        next.remove(origin);
        this.origins = List.copyOf(next);
        return true;
    }

    /** Snapshot of the current allow-list. Returned list is immutable. */
    public List<String> snapshot() {
        return origins;
    }
}
