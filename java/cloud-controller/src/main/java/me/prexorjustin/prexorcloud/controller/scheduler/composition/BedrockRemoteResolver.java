package me.prexorjustin.prexorcloud.controller.scheduler.composition;

import java.util.Optional;

/**
 * Resolves the live network endpoint a Geyser (Bedrock) instance should forward to — a running Java
 * proxy instance of the named proxy group. Used by {@link InstanceCompositionPlanner} at provision time
 * to inject {@code remote.address} / {@code remote.port} into the Geyser config, so the Bedrock
 * front-door points at an actual scheduled proxy rather than a static guess.
 */
@FunctionalInterface
public interface BedrockRemoteResolver {

    /** A reachable proxy endpoint. */
    record Endpoint(String host, int port) {}

    /**
     * Returns a reachable endpoint of a running instance of {@code proxyGroup}, or empty if the group
     * is blank or has no running instance yet (cold start — the caller leaves the config default).
     */
    Optional<Endpoint> resolve(String proxyGroup);
}
