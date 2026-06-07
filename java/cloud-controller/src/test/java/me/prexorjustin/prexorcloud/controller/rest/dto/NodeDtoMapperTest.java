package me.prexorjustin.prexorcloud.controller.rest.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import me.prexorjustin.prexorcloud.controller.state.NodeHostInfo;
import me.prexorjustin.prexorcloud.controller.state.NodeState;
import me.prexorjustin.prexorcloud.controller.state.StateStore;
import me.prexorjustin.prexorcloud.security.token.JoinToken;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("NodeDtoMapper")
class NodeDtoMapperTest {

    @Test
    @DisplayName("maps connected nodes with stable labels and nullable host info")
    void mapsConnectedNode() {
        NodeState node = new NodeState(
                "node-1",
                "10.0.0.1",
                NodeState.NodeStatus.ONLINE,
                0.42,
                16384,
                8192,
                102400,
                512000,
                5,
                Set.of(25565),
                Map.of(),
                Instant.parse("2026-04-17T10:00:00Z"),
                Instant.parse("2026-04-17T10:05:00Z"),
                NodeHostInfo.UNKNOWN);
        StateStore.RegisteredNode registeredNode = new StateStore.RegisteredNode(
                "node-1", Instant.parse("2026-04-01T00:00:00Z"), Instant.parse("2026-04-17T10:05:00Z"));

        Map<String, Object> dto = NodeDtoMapper.toConnectedDto(node, registeredNode);

        assertEquals(Map.of(), dto.get("labels"));
        assertEquals(null, dto.get("hostInfo"));
        assertEquals("2026-04-01T00:00:00Z", dto.get("firstSeen"));
    }

    @Test
    @DisplayName("maps pending nodes including join token metadata")
    void mapsPendingNode() {
        JoinToken token = new JoinToken("tk-1", "node-2", "hash", "plain-token", Instant.parse("2026-04-18T00:00:00Z"));

        Map<String, Object> dto = NodeDtoMapper.toPendingDto(token);

        assertEquals("PENDING", dto.get("type"));
        assertEquals("plain-token", dto.get("joinToken"));
    }
}
