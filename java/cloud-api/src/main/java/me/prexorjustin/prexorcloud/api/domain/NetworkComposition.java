package me.prexorjustin.prexorcloud.api.domain;

import java.util.List;

/**
 * A named composition of proxy + backend groups that defines lobby spawn and
 * fallback routing for a Minecraft network.
 *
 * <p>Read by Velocity / Bungee proxies through {@code GET /api/proxy/networks}.
 * The proxy uses {@code lobbyGroup} as the join target and walks
 * {@code fallbackGroups} when a backend instance crashes or is unreachable.
 *
 * @param name           unique network identifier; matches {@code [a-z0-9_][a-z0-9_-]*}
 * @param description    human-readable description (may be empty)
 * @param lobbyGroup     backend group used as the default join target and last-resort fallback
 * @param fallbackGroups ordered fallback chain attempted on instance failure (may be empty)
 * @param memberGroups   backend groups belonging to this network; empty means "no restriction"
 * @param proxyGroups    proxy groups this composition applies to; empty means "all proxies"
 * @param kickMessage    message shown when all fallbacks are exhausted (may be empty)
 */
public record NetworkComposition(
        String name,
        String description,
        String lobbyGroup,
        List<String> fallbackGroups,
        List<String> memberGroups,
        List<String> proxyGroups,
        String kickMessage) {

    public NetworkComposition {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name");
        if (description == null) description = "";
        if (lobbyGroup == null || lobbyGroup.isBlank()) throw new IllegalArgumentException("lobbyGroup");
        fallbackGroups = fallbackGroups == null ? List.of() : List.copyOf(fallbackGroups);
        memberGroups = memberGroups == null ? List.of() : List.copyOf(memberGroups);
        proxyGroups = proxyGroups == null ? List.of() : List.copyOf(proxyGroups);
        if (kickMessage == null) kickMessage = "";
    }
}
