package me.prexorjustin.prexorcloud.api.module.platform;

/**
 * Internal adapter used to expose capability handles that can swap delegates
 * without replacing the consumer's captured reference.
 */
public interface CapabilityHandleResolver {

    <T> T resolve(Class<T> type);
}
