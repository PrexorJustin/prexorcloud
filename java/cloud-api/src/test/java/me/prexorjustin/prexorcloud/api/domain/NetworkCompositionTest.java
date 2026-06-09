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

    @Test
    @DisplayName("seven-arg constructor leaves Bedrock routing empty (inherits Java route)")
    void sevenArgCtorHasNoBedrockRouting() {
        var n = new NetworkComposition("main", "d", "lobby", List.of("survival"), List.of(), List.of(), "");
        assertEquals("", n.bedrockLobbyGroup());
        assertEquals(List.of(), n.bedrockFallbackGroups());
    }

    @Test
    @DisplayName("retains explicit Bedrock lobby + fallback chain")
    void retainsBedrockRouting() {
        var n = new NetworkComposition(
                "main",
                "d",
                "lobby",
                List.of("survival"),
                List.of(),
                List.of(),
                "",
                "bedrock-lobby",
                List.of("bedrock-survival"));
        assertEquals("bedrock-lobby", n.bedrockLobbyGroup());
        assertEquals(List.of("bedrock-survival"), n.bedrockFallbackGroups());
    }

    @Test
    @DisplayName("normalizes null Bedrock fields to empty")
    void nullBedrockFieldsBecomeEmpty() {
        var n = new NetworkComposition("main", "d", "lobby", List.of(), List.of(), List.of(), "", null, null);
        assertEquals("", n.bedrockLobbyGroup());
        assertEquals(List.of(), n.bedrockFallbackGroups());
    }

    @Test
    @DisplayName("defensively copies the Bedrock fallback list")
    void defensivelyCopiesBedrockFallbacks() {
        var fallbacks = new ArrayList<>(List.of("a", "b"));
        var n = new NetworkComposition(
                "main", "d", "lobby", List.of(), List.of(), List.of(), "", "bedrock-lobby", fallbacks);
        fallbacks.add("c");
        assertEquals(List.of("a", "b"), n.bedrockFallbackGroups());
    }
}
