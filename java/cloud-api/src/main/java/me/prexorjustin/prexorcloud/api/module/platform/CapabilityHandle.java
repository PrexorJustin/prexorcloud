package me.prexorjustin.prexorcloud.api.module.platform;

import java.util.Objects;

/**
 * Typed binding from a capability id to its handle value.
 *
 * <p>A capability handle is the runtime artifact a provider module exposes for
 * consumers to call. {@link #type()} is the public interface (or class) that
 * consumers resolve against; {@link #value()} must be an instance of it.
 *
 * <p>The constructor enforces that {@code value instanceof type} so providers
 * cannot expose a handle that no consumer can legally cast.
 *
 * @param <T> the public type of the handle
 */
public final class CapabilityHandle<T> {

    private final String id;
    private final Class<T> type;
    private final T value;

    private CapabilityHandle(String id, Class<T> type, T value) {
        this.id = Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        this.type = Objects.requireNonNull(type, "type");
        this.value = Objects.requireNonNull(value, "value");
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException("handle for '" + id + "' is not an instance of " + type.getName());
        }
    }

    public static <T> CapabilityHandle<T> of(String id, Class<T> type, T value) {
        return new CapabilityHandle<>(id, type, value);
    }

    public String id() {
        return id;
    }

    public Class<T> type() {
        return type;
    }

    public T value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CapabilityHandle<?> that)) return false;
        return id.equals(that.id) && type.equals(that.type) && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, type, value);
    }

    @Override
    public String toString() {
        return "CapabilityHandle[" + id + " : " + type.getName() + "]";
    }
}
