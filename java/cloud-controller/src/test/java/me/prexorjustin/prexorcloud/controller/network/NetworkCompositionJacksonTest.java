package me.prexorjustin.prexorcloud.controller.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import me.prexorjustin.prexorcloud.api.domain.NetworkComposition;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the Jackson path that {@link MongoNetworkStore} uses
 * ({@code convertValue(doc, NetworkComposition.class)}) — the two-constructor record must still
 * deserialize through its canonical constructor, and documents persisted before the F.1 Bedrock
 * fields existed must rehydrate with empty Bedrock routing.
 */
@DisplayName("NetworkComposition Jackson round-trip")
class NetworkCompositionJacksonTest {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    @DisplayName("round-trips the Bedrock fields via the Mongo convertValue path")
    void roundTripsBedrockFields() {
        var original = new NetworkComposition(
                "main",
                "d",
                "lobby",
                List.of("survival"),
                List.of(),
                List.of(),
                "",
                "bedrock-lobby",
                List.of("bedrock-survival"));
        @SuppressWarnings("unchecked")
        var map = MAPPER.convertValue(original, Map.class);
        var restored = MAPPER.convertValue(map, NetworkComposition.class);
        assertEquals(original, restored);
        assertEquals("bedrock-lobby", restored.bedrockLobbyGroup());
        assertEquals(List.of("bedrock-survival"), restored.bedrockFallbackGroups());
    }

    @Test
    @DisplayName("a pre-F.1 document without Bedrock fields rehydrates with empty Bedrock routing")
    void legacyDocDeserializes() {
        var legacy = new HashMap<String, Object>();
        legacy.put("name", "main");
        legacy.put("description", "d");
        legacy.put("lobbyGroup", "lobby");
        legacy.put("fallbackGroups", List.of("survival"));
        legacy.put("memberGroups", List.of());
        legacy.put("proxyGroups", List.of());
        legacy.put("kickMessage", "");

        var restored = MAPPER.convertValue(legacy, NetworkComposition.class);

        assertEquals("", restored.bedrockLobbyGroup());
        assertEquals(List.of(), restored.bedrockFallbackGroups());
        assertEquals("lobby", restored.lobbyGroup());
    }
}
