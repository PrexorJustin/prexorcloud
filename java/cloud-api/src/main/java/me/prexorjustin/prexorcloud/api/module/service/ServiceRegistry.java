package me.prexorjustin.prexorcloud.api.module.service;

import java.util.Collection;
import java.util.NoSuchElementException;
import java.util.Optional;

import me.prexorjustin.prexorcloud.api.module.SemverRange;

/**
 * Cross-module service registry. Modules publish shared services keyed by
 * {@link ServiceKey}; other modules consume them either with
 * {@link #require(ServiceKey)} (hard dependency) or {@link #optional(ServiceKey)}
 * (integrate-if-present).
 *
 * <p>Single-primary-per-key: registering the same key twice is an error.
 * Multi-binding, priority, and replaceable services are deliberately out of
 * scope until a concrete use case appears.
 */
public interface ServiceRegistry {

    // ── Key-based primary API ────────────────────────────────────────────

    <T> void register(ServiceKey<T> key, T implementation);

    <T> Optional<T> get(ServiceKey<T> key);

    /** Returns the registered service or throws if not present. */
    <T> T require(ServiceKey<T> key);

    <T> void unregister(ServiceKey<T> key);

    /**
     * Consumer-side alias for {@link #get(ServiceKey)}. Prefer this at call
     * sites that integrate-if-present — the name documents intent.
     */
    default <T> Optional<T> optional(ServiceKey<T> key) {
        return get(key);
    }

    /** Snapshot of every currently-registered provider. */
    Collection<ServiceProvider> providers();

    // ── Versioned API (slice 5) ─────────────────────────────────────────
    // Default implementations fall through to the unversioned API so any
    // ServiceRegistry implementation that does not care about versions keeps
    // working unchanged.

    /**
     * Register a service and declare its contract version. Consumers can then
     * filter providers with {@link #get(ServiceKey, SemverRange)}.
     */
    default <T> void register(ServiceKey<T> key, T implementation, String serviceVersion) {
        register(key, implementation);
    }

    /**
     * Returns the registered service if its declared contract version is
     * contained in {@code versionRange}. Returns empty when no service is
     * registered for the key, or when the registered version falls outside the
     * requested range.
     */
    default <T> Optional<T> get(ServiceKey<T> key, SemverRange versionRange) {
        return get(key);
    }

    /** Hard variant of {@link #get(ServiceKey, SemverRange)}. */
    default <T> T require(ServiceKey<T> key, SemverRange versionRange) {
        Optional<T> svc = get(key, versionRange);
        if (svc.isPresent()) {
            return svc.get();
        }
        throw new NoSuchElementException("No service matching " + key.asString() + " in range " + versionRange);
    }

    /** Returns the declared contract version of the registered service, if any. */
    default Optional<String> versionOf(ServiceKey<?> key) {
        return Optional.empty();
    }

    // ── Class-based convenience overloads ───────────────────────────────
    // Delegate to the key-based API with an unqualified key.

    default <T> void register(Class<T> type, T implementation) {
        register(ServiceKey.of(type), implementation);
    }

    default <T> Optional<T> get(Class<T> type) {
        return get(ServiceKey.of(type));
    }

    default <T> T require(Class<T> type) {
        return require(ServiceKey.of(type));
    }

    default <T> void unregister(Class<T> type) {
        unregister(ServiceKey.of(type));
    }

    default <T> Optional<T> optional(Class<T> type) {
        return get(ServiceKey.of(type));
    }

    // ── Legacy aliases used by existing modules ─────────────────────────

    default <T> void provide(Class<T> type, T implementation) {
        register(type, implementation);
    }

    default <T> Optional<T> lookup(Class<T> type) {
        return get(type);
    }
}
