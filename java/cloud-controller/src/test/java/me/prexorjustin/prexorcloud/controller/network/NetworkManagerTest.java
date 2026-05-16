package me.prexorjustin.prexorcloud.controller.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import me.prexorjustin.prexorcloud.api.domain.NetworkComposition;
import me.prexorjustin.prexorcloud.controller.catalog.CatalogConfigLoader.CatalogEntry;
import me.prexorjustin.prexorcloud.controller.catalog.CatalogStore;
import me.prexorjustin.prexorcloud.controller.group.GroupConfig;
import me.prexorjustin.prexorcloud.controller.group.GroupManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("NetworkManager")
@ExtendWith(MockitoExtension.class)
class NetworkManagerTest {

    @Mock
    GroupManager groupManager;

    @Mock
    CatalogStore catalogStore;

    @Mock
    GroupConfig velocityGroup;

    @Mock
    GroupConfig paperGroup;

    @Mock
    CatalogEntry velocityCatalog;

    NetworkManager manager;

    @BeforeEach
    void setUp() throws Exception {
        // Default: most groups exist; tests override for missing-group cases.
        lenient().when(groupManager.exists(anyString())).thenReturn(true);
        lenient().when(catalogStore.getAll()).thenReturn(List.of(velocityCatalog));
        lenient().when(velocityCatalog.platform()).thenReturn("VELOCITY");
        lenient().when(velocityCatalog.isProxy()).thenReturn(true);
        manager = new NetworkManager(groupManager, catalogStore);
    }

    private static NetworkComposition net(
            String name, String lobby, List<String> fallbacks, List<String> members, List<String> proxies) {
        return new NetworkComposition(name, "", lobby, fallbacks, members, proxies, "");
    }

    @Test
    @DisplayName("create stores a network and exposes it via get/snapshot")
    void create_stores() {
        var n = net("main", "lobby", List.of(), List.of(), List.of());
        manager.create(n);
        assertEquals(Optional.of(n), manager.get("main"));
        assertEquals(1, manager.snapshot().size());
        assertTrue(manager.exists("main"));
    }

    @Test
    @DisplayName("create rejects duplicate name")
    void create_rejectsDuplicate() {
        var n = net("main", "lobby", List.of(), List.of(), List.of());
        manager.create(n);
        assertThrows(IllegalArgumentException.class, () -> manager.create(n));
    }

    @Test
    @DisplayName("create rejects when lobbyGroup does not exist")
    void create_rejectsMissingLobby() {
        when(groupManager.exists("ghost")).thenReturn(false);
        var n = net("main", "ghost", List.of(), List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> manager.create(n));
    }

    @Test
    @DisplayName("create rejects when fallback equals lobbyGroup")
    void create_rejectsFallbackEqualsLobby() {
        var n = net("main", "lobby", List.of("lobby"), List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> manager.create(n));
    }

    @Test
    @DisplayName("create rejects duplicate fallback entries")
    void create_rejectsDuplicateFallback() {
        var n = net("main", "lobby", List.of("survival", "survival"), List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> manager.create(n));
    }

    @Test
    @DisplayName("create rejects proxyGroups entry whose platform is not a proxy")
    void create_rejectsNonProxyEntry() {
        when(groupManager.get("survival-1")).thenReturn(Optional.of(paperGroup));
        when(paperGroup.platform()).thenReturn("PAPER");
        var n = net("main", "lobby", List.of(), List.of(), List.of("survival-1"));
        assertThrows(IllegalArgumentException.class, () -> manager.create(n));
    }

    @Test
    @DisplayName("create accepts proxyGroups entry whose platform is a proxy")
    void create_acceptsProxyPlatform() {
        when(groupManager.get("velo")).thenReturn(Optional.of(velocityGroup));
        when(velocityGroup.platform()).thenReturn("VELOCITY");
        var n = net("main", "lobby", List.of(), List.of(), List.of("velo"));
        manager.create(n);
        assertTrue(manager.exists("main"));
    }

    @Test
    @DisplayName("update replaces existing entry")
    void update_replaces() {
        var initial = net("main", "lobby", List.of(), List.of(), List.of());
        manager.create(initial);
        var updated = net("main", "lobby", List.of("survival"), List.of(), List.of());
        manager.update(updated);
        assertEquals(List.of("survival"), manager.get("main").orElseThrow().fallbackGroups());
    }

    @Test
    @DisplayName("update fails on unknown name")
    void update_rejectsMissing() {
        var n = net("ghost", "lobby", List.of(), List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> manager.update(n));
    }

    @Test
    @DisplayName("delete removes from cache; idempotent on missing")
    void delete_removes() {
        var n = net("main", "lobby", List.of(), List.of(), List.of());
        manager.create(n);
        manager.delete("main");
        assertTrue(manager.get("main").isEmpty());
        manager.delete("main"); // no exception
    }

    @Test
    @DisplayName("create rejects bad name")
    void create_rejectsBadName() {
        assertThrows(
                IllegalArgumentException.class,
                () -> manager.create(net("Bad Name", "lobby", List.of(), List.of(), List.of())));
    }

    @Test
    @DisplayName("memberGroups duplicates rejected")
    void create_rejectsDuplicateMember() {
        var n = net("main", "lobby", List.of(), List.of("a", "a"), List.of());
        assertThrows(IllegalArgumentException.class, () -> manager.create(n));
    }

    @Test
    @DisplayName("removeNetworkFromCache drops only the named entry")
    void removeFromCache_isolated() {
        manager.create(net("a", "lobby", List.of(), List.of(), List.of()));
        manager.create(net("b", "lobby", List.of(), List.of(), List.of()));
        manager.removeNetworkFromCache("a");
        assertEquals(
                Set.of("b"),
                Set.copyOf(manager.snapshot().stream()
                        .map(NetworkComposition::name)
                        .toList()));
    }
}
