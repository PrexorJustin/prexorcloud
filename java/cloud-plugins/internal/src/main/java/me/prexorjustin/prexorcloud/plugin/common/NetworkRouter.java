package me.prexorjustin.prexorcloud.plugin.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import me.prexorjustin.prexorcloud.api.domain.NetworkComposition;
import me.prexorjustin.prexorcloud.api.domain.PlayerEdition;

/**
 * Resolves join + fallback routing for a single proxy group from the
 * {@link CloudStateCache}'s {@link NetworkComposition} snapshot, with a
 * legacy fallback to the cache's default group when no network applies.
 *
 * <p><b>Edition-aware (Track F.1):</b> the {@code edition} overloads route
 * Bedrock players ({@link PlayerEdition#BEDROCK}) to the network's
 * {@code bedrockLobbyGroup} / {@code bedrockFallbackGroups} when configured,
 * and otherwise behave identically to the Java route. The no-argument /
 * single-argument overloads default to {@link PlayerEdition#JAVA}.
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

    /** Java-edition {@link #joinTargetGroup(String)}. */
    public Optional<String> joinTargetGroup() {
        return joinTargetGroup(PlayerEdition.JAVA);
    }

    /**
     * Group to spawn the player into on first join. Prefers the network's
     * lobby for the player's {@code edition} (the Bedrock lobby for Bedrock
     * players when set, else {@code lobbyGroup}); otherwise falls back to the
     * cluster default.
     */
    public Optional<String> joinTargetGroup(String edition) {
        return cache.getNetworkForProxyGroup(proxyGroup)
                .map(network -> lobbyFor(network, edition))
                .or(cache::getDefaultGroupName);
    }

    /** Java-edition {@link #fallbackChain(String, String)}. */
    public List<String> fallbackChain(String excludeGroup) {
        return fallbackChain(excludeGroup, PlayerEdition.JAVA);
    }

    /**
     * Ordered chain of backend groups to try on join or after a kick:
     * {@code [lobby] ++ fallbacks} for the player's {@code edition},
     * deduplicated, with {@code excludeGroup} removed (typically the group the
     * player was just kicked from). Bedrock players use the network's
     * {@code bedrockLobbyGroup} / {@code bedrockFallbackGroups} when configured,
     * and otherwise the shared Java route. Empty when no network and no default
     * group exist.
     */
    public List<String> fallbackChain(String excludeGroup, String edition) {
        Optional<NetworkComposition> network = cache.getNetworkForProxyGroup(proxyGroup);
        if (network.isPresent()) {
            NetworkComposition n = network.get();
            List<String> fallbacks = fallbacksFor(n, edition);
            List<String> chain = new ArrayList<>(1 + fallbacks.size());
            addUnlessExcluded(chain, lobbyFor(n, edition), excludeGroup);
            for (String group : fallbacks) {
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

    /** Lobby group for the edition: the Bedrock lobby when Bedrock and set, else the shared lobby. */
    private static String lobbyFor(NetworkComposition network, String edition) {
        if (PlayerEdition.BEDROCK.equals(edition)
                && !network.bedrockLobbyGroup().isBlank()) {
            return network.bedrockLobbyGroup();
        }
        return network.lobbyGroup();
    }

    /** Fallback chain for the edition: the Bedrock chain when Bedrock and non-empty, else shared. */
    private static List<String> fallbacksFor(NetworkComposition network, String edition) {
        if (PlayerEdition.BEDROCK.equals(edition)
                && !network.bedrockFallbackGroups().isEmpty()) {
            return network.bedrockFallbackGroups();
        }
        return network.fallbackGroups();
    }

    private static void addUnlessExcluded(List<String> chain, String group, String excludeGroup) {
        if (group == null || group.isBlank()) return;
        if (group.equals(excludeGroup)) return;
        if (chain.contains(group)) return;
        chain.add(group);
    }
}
