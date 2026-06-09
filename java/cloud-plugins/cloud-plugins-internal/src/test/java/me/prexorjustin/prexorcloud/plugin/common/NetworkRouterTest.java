package me.prexorjustin.prexorcloud.plugin.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import me.prexorjustin.prexorcloud.api.domain.NetworkComposition;
import me.prexorjustin.prexorcloud.plugin.common.dto.GroupDto;

import org.junit.jupiter.api.Test;

final class NetworkRouterTest {

    @Test
    void joinTargetGroupPrefersNetworkLobbyOverDefaultGroup() {
        CloudStateCache cache = newCache();
        cache.applyGroupSnapshot(List.of(group("global-default", true)));
        cache.applyNetworkSnapshot(List.of(network("main", "lobby", List.of("survival"), List.of("primary"), "")));

        NetworkRouter router = new NetworkRouter(cache, "primary");

        assertEquals("lobby", router.joinTargetGroup().orElseThrow());
    }

    @Test
    void joinTargetGroupFallsBackToDefaultGroupWhenNoNetworkApplies() {
        CloudStateCache cache = newCache();
        cache.applyGroupSnapshot(List.of(group("global-default", true)));
        cache.applyNetworkSnapshot(List.of(network("other", "lobby", List.of(), List.of("not-this-proxy"), "")));

        NetworkRouter router = new NetworkRouter(cache, "primary");

        assertEquals("global-default", router.joinTargetGroup().orElseThrow());
    }

    @Test
    void wildcardNetworkAppliesWhenNoGroupScopedMatchExists() {
        CloudStateCache cache = newCache();
        cache.applyNetworkSnapshot(List.of(network("any", "wildcard-lobby", List.of(), List.of(), "")));

        NetworkRouter router = new NetworkRouter(cache, "primary");

        assertEquals("wildcard-lobby", router.joinTargetGroup().orElseThrow());
    }

    @Test
    void groupScopedNetworkBeatsWildcardNetwork() {
        CloudStateCache cache = newCache();
        cache.applyNetworkSnapshot(List.of(
                network("any", "wildcard-lobby", List.of(), List.of(), ""),
                network("scoped", "scoped-lobby", List.of(), List.of("primary"), "")));

        NetworkRouter router = new NetworkRouter(cache, "primary");

        assertEquals("scoped-lobby", router.joinTargetGroup().orElseThrow());
    }

    @Test
    void fallbackChainStartsWithLobbyAndFollowsConfiguredOrder() {
        CloudStateCache cache = newCache();
        cache.applyNetworkSnapshot(
                List.of(network("main", "lobby", List.of("survival", "creative"), List.of("primary"), "")));

        NetworkRouter router = new NetworkRouter(cache, "primary");

        assertEquals(List.of("lobby", "survival", "creative"), router.fallbackChain(null));
    }

    @Test
    void fallbackChainExcludesSourceGroupForFailover() {
        CloudStateCache cache = newCache();
        cache.applyNetworkSnapshot(
                List.of(network("main", "lobby", List.of("survival", "creative"), List.of("primary"), "")));

        NetworkRouter router = new NetworkRouter(cache, "primary");

        assertEquals(List.of("lobby", "creative"), router.fallbackChain("survival"));
    }

    @Test
    void fallbackChainDeduplicatesLobbyListedAlsoInFallbacks() {
        CloudStateCache cache = newCache();
        cache.applyNetworkSnapshot(
                List.of(network("main", "lobby", List.of("lobby", "survival"), List.of("primary"), "")));

        NetworkRouter router = new NetworkRouter(cache, "primary");

        assertEquals(List.of("lobby", "survival"), router.fallbackChain(null));
    }

    @Test
    void fallbackChainIsDefaultGroupWhenNoNetworkApplies() {
        CloudStateCache cache = newCache();
        cache.applyGroupSnapshot(List.of(group("global-default", true)));

        NetworkRouter router = new NetworkRouter(cache, "primary");

        assertEquals(List.of("global-default"), router.fallbackChain(null));
    }

    @Test
    void fallbackChainIsEmptyWhenDefaultGroupIsTheExcludedSource() {
        CloudStateCache cache = newCache();
        cache.applyGroupSnapshot(List.of(group("global-default", true)));

        NetworkRouter router = new NetworkRouter(cache, "primary");

        assertTrue(router.fallbackChain("global-default").isEmpty());
    }

    @Test
    void kickMessagePrefersNetworkValueOverDefault() {
        CloudStateCache cache = newCache();
        cache.applyNetworkSnapshot(
                List.of(network("main", "lobby", List.of(), List.of("primary"), "Network is down for maintenance")));

        NetworkRouter router = new NetworkRouter(cache, "primary");

        assertEquals("Network is down for maintenance", router.kickMessage("default"));
    }

    @Test
    void kickMessageFallsBackToDefaultWhenNetworkMessageIsBlank() {
        CloudStateCache cache = newCache();
        cache.applyNetworkSnapshot(List.of(network("main", "lobby", List.of(), List.of("primary"), "")));

        NetworkRouter router = new NetworkRouter(cache, "primary");

        assertEquals("default", router.kickMessage("default"));
    }

    private static CloudStateCache newCache() {
        return new CloudStateCache(new ThrowingControllerClient(), 0);
    }

    private static NetworkComposition network(
            String name, String lobby, List<String> fallbacks, List<String> proxyGroups, String kickMessage) {
        return new NetworkComposition(name, "", lobby, fallbacks, List.of(), proxyGroups, kickMessage);
    }

    private static GroupDto group(String name, boolean defaultGroup) {
        return new GroupDto(
                name,
                "PAPER",
                1,
                10,
                100,
                0,
                false,
                "",
                List.of(),
                false,
                defaultGroup,
                512,
                0.5,
                1024L,
                List.of(),
                java.util.Map.of(),
                List.of(),
                List.of(),
                "STATIC",
                30);
    }

    private static final class ThrowingControllerClient extends BaseControllerClient {
        ThrowingControllerClient() {
            super("http://invalid", "token");
        }

        @Override
        protected String apiPrefix() {
            return "/api/proxy";
        }
    }
}
