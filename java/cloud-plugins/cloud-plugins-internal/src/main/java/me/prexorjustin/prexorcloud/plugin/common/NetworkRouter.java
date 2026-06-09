package me.prexorjustin.prexorcloud.plugin.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import me.prexorjustin.prexorcloud.api.domain.NetworkComposition;

/**
 * Resolves join + fallback routing for a single proxy group from the
 * {@link CloudStateCache}'s {@link NetworkComposition} snapshot, with a
 * legacy fallback to the cache's default group when no network applies.
 *
 * <p>Stateless: holds a reference to the cache and the proxy group only.
 * Calls are safe from any thread because the cache exposes a volatile
 * snapshot.
 */
public final class NetworkRouter {

    private final CloudStateCache cache;
    private final String proxyGroup;

    public NetworkRouter(CloudStateCache cache, String proxyGroup) {
        this.cache = cache;
        this.proxyGroup = proxyGroup;
    }

    /**
     * Group to spawn the player into on first join. Prefers the network's
     * {@code lobbyGroup}; otherwise falls back to the cluster default.
     */
    public Optional<String> joinTargetGroup() {
        return cache.getNetworkForProxyGroup(proxyGroup)
                .map(NetworkComposition::lobbyGroup)
                .or(cache::getDefaultGroupName);
    }

    /**
     * Ordered chain of backend groups to try on join or after a kick:
     * {@code [lobbyGroup] ++ fallbackGroups}, deduplicated, with
     * {@code excludeGroup} removed (typically the group the player was
     * just kicked from). Empty when no network and no default group exist.
     */
    public List<String> fallbackChain(String excludeGroup) {
        Optional<NetworkComposition> network = cache.getNetworkForProxyGroup(proxyGroup);
        if (network.isPresent()) {
            NetworkComposition n = network.get();
            List<String> chain = new ArrayList<>(1 + n.fallbackGroups().size());
            addUnlessExcluded(chain, n.lobbyGroup(), excludeGroup);
            for (String group : n.fallbackGroups()) {
                addUnlessExcluded(chain, group, excludeGroup);
            }
            return List.copyOf(chain);
        }
        return cache.getDefaultGroupName()
                .filter(group -> !group.equals(excludeGroup))
                .map(List::of)
                .orElse(List.of());
    }

    /**
     * Disconnect message when every fallback is exhausted. Falls back to
     * {@code defaultMessage} when no network applies or its kick message
     * is blank.
     */
    public String kickMessage(String defaultMessage) {
        return cache.getNetworkForProxyGroup(proxyGroup)
                .map(NetworkComposition::kickMessage)
                .filter(message -> !message.isBlank())
                .orElse(defaultMessage);
    }

    private static void addUnlessExcluded(List<String> chain, String group, String excludeGroup) {
        if (group == null || group.isBlank()) return;
        if (group.equals(excludeGroup)) return;
        if (chain.contains(group)) return;
        chain.add(group);
    }
}
