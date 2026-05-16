package me.prexorjustin.prexorcloud.api.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("NetworkComposition")
class NetworkCompositionTest {

    @Test
    @DisplayName("rejects blank name")
    void blankName() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new NetworkComposition("", "d", "lobby", List.of(), List.of(), List.of(), ""));
    }

    @Test
    @DisplayName("rejects blank lobbyGroup")
    void blankLobbyGroup() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new NetworkComposition("main", "", "", List.of(), List.of(), List.of(), ""));
    }

    @Test
    @DisplayName("normalizes nulls to empty collections + strings")
    void nullsBecomeEmpty() {
        var n = new NetworkComposition("main", null, "lobby", null, null, null, null);
        assertEquals("", n.description());
        assertEquals(List.of(), n.fallbackGroups());
        assertEquals(List.of(), n.memberGroups());
        assertEquals(List.of(), n.proxyGroups());
        assertEquals("", n.kickMessage());
    }

    @Test
    @DisplayName("defensively copies list arguments")
    void defensivelyCopiesLists() {
        var fallbacks = new ArrayList<>(List.of("a", "b"));
        var n = new NetworkComposition("main", "d", "lobby", fallbacks, List.of(), List.of(), "");
        fallbacks.add("c");
        assertEquals(List.of("a", "b"), n.fallbackGroups());
    }

    @Test
    @DisplayName("happy path constructs cleanly")
    void happyPath() {
        assertDoesNotThrow(() -> {
            var n = new NetworkComposition(
                    "main", "Primary", "lobby", List.of("survival"), List.of("creative"), List.of("proxy"), "Bye");
            assertEquals("main", n.name());
            assertTrue(n.fallbackGroups().contains("survival"));
        });
    }
}
