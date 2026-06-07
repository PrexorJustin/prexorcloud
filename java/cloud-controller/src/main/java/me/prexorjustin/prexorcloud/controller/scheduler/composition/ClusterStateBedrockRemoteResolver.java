package me.prexorjustin.prexorcloud.controller.scheduler.composition;

import java.util.Optional;

import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

/**
 * {@link BedrockRemoteResolver} backed by live {@link ClusterState}: picks a {@code RUNNING} instance of
 * the proxy group and combines its node's advertised host with the instance's listen port. Returns empty
 * when nothing is running yet (cold start).
 */
public final class ClusterStateBedrockRemoteResolver implements BedrockRemoteResolver {

    private final ClusterState clusterState;

    public ClusterStateBedrockRemoteResolver(ClusterState clusterState) {
        this.clusterState = clusterState;
    }

    @Override
    public Optional<Endpoint> resolve(String proxyGroup) {
        if (proxyGroup == null || proxyGroup.isBlank()) return Optional.empty();
        return clusterState.getInstancesByGroup(proxyGroup).stream()
                .filter(instance -> instance.state() == InstanceState.RUNNING)
                .flatMap(instance ->
                        clusterState
                                .getNode(instance.nodeId())
                                .map(node -> new Endpoint(hostOf(node.address()), instance.port()))
                                .stream())
                .findFirst();
    }

    /** Strips the daemon advertise port from a {@code host:port} address, leaving the reachable host. */
    private static String hostOf(String address) {
        if (address == null || address.isBlank()) return "127.0.0.1";
        int portColon = address.lastIndexOf(':');
        // Keep bare hosts and bracketed IPv6 (e.g. [::1]:8080) intact; only trim a trailing :port.
        return portColon > 0 && address.indexOf(':') == portColon ? address.substring(0, portColon) : address;
    }
}
