package me.prexorjustin.prexorcloud.api.module.service;

import java.util.Objects;
import java.util.Optional;

/**
 * Typed identity for a cross-module service. The key is the contract surface
 * between providers and consumers, and is the stable string used by the
 * capability system ({@link #asString()}).
 *
 * <p>A key is either a plain type ({@link #of(Class)}) or a qualified variant
 * ({@link #named(Class, String)}) when multiple implementations of the same
 * interface need to coexist. The registry resolves by full key equality —
 * two keys that differ only in qualifier are distinct services.
 *
 * @param <T> the contract type
 */
public final class ServiceKey<T> {

    private final Class<T> type;
    private final String qualifier;

    private ServiceKey(Class<T> type, String qualifier) {
        this.type = Objects.requireNonNull(type, "type");
        this.qualifier = qualifier;
    }

    public static <T> ServiceKey<T> of(Class<T> type) {
        return new ServiceKey<>(type, null);
    }

    public static <T> ServiceKey<T> named(Class<T> type, String qualifier) {
        Objects.requireNonNull(qualifier, "qualifier");
        if (qualifier.isBlank()) {
            throw new IllegalArgumentException("qualifier must not be blank");
        }
        return new ServiceKey<>(type, qualifier);
    }

    public Class<T> type() {
        return type;
    }

    public Optional<String> qualifier() {
        return Optional.ofNullable(qualifier);
    }

    /**
     * Stable string form used in logs and as a capability identifier in the
     * permission model. Format: {@code FQCN} or {@code FQCN#qualifier}.
     */
    public String asString() {
        return qualifier == null ? type.getName() : type.getName() + "#" + qualifier;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ServiceKey<?> other)) return false;
        return type.equals(other.type) && Objects.equals(qualifier, other.qualifier);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, qualifier);
    }

    @Override
    public String toString() {
        return "ServiceKey[" + asString() + "]";
    }
}
