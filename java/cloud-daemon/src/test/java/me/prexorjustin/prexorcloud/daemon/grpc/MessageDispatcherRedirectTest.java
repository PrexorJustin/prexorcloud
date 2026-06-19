package me.prexorjustin.prexorcloud.daemon.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;

import me.prexorjustin.prexorcloud.protocol.ControllerMessage;
import me.prexorjustin.prexorcloud.protocol.HandshakeAck;

import org.junit.jupiter.api.Test;

/**
 * Verifies the dispatcher routes a follower's redirecting {@code HandshakeAck} to the client's target
 * swap rather than settling the connection on the follower (Phase 3). Uses the real client (no
 * reconnect manager, so no network) — the redirect path swaps the target without re-dialing.
 */
final class MessageDispatcherRedirectTest {

    @Test
    void followerAckRedirectsClientAndDoesNotSettle() {
        var client = new DaemonGrpcClient("ctrl-a", 9090, "node-1", "", 1024, Map.of(), null, null, null);
        var dispatcher = new MessageDispatcher();
        dispatcher.setClient(client);

        dispatcher.dispatch(ControllerMessage.newBuilder()
                .setHandshakeAck(HandshakeAck.newBuilder().setSessionId("s1").setLeaderGrpcAddr("ctrl-b:9091"))
                .build());

        assertEquals("ctrl-b", client.controllerHost(), "daemon retargets to the leader");
        assertEquals(9091, client.controllerPort());
        assertFalse(client.isConnected(), "a redirected daemon does not settle on the follower");
    }
}
