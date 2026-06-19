package me.prexorjustin.prexorcloud.daemon.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.prexorjustin.prexorcloud.protocol.ControllerMessage;
import me.prexorjustin.prexorcloud.protocol.HandshakeAck;
import me.prexorjustin.prexorcloud.protocol.Ping;
import me.prexorjustin.prexorcloud.protocol.StopInstance;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the daemon-side epoch fence ({@link MessageDispatcher#acceptEpoch}). A command from
 * a deposed leader (lower epoch than the highest accepted) must be rejected; a higher epoch raises
 * the fence; unset epoch (legacy controller) and the handshake ack are always accepted.
 */
final class MessageDispatcherEpochTest {

    private static ControllerMessage command(long epoch) {
        return ControllerMessage.newBuilder()
                .setEpoch(epoch)
                .setStopInstance(StopInstance.newBuilder().setInstanceId("i-1").build())
                .build();
    }

    @Test
    void unsetEpochIsAccepted() {
        var d = new MessageDispatcher();
        assertTrue(d.acceptEpoch(ControllerMessage.newBuilder()
                .setPing(Ping.newBuilder().setSequence(1).build())
                .build()));
        assertEquals(0L, d.latestAcceptedEpoch());
    }

    @Test
    void handshakeAckIsAlwaysAccepted() {
        var d = new MessageDispatcher();
        // Even with a high epoch on the ack, acceptEpoch never fences a handshake — it is the baseline.
        assertTrue(d.acceptEpoch(ControllerMessage.newBuilder()
                .setHandshakeAck(HandshakeAck.newBuilder().setEpoch(9).build())
                .build()));
    }

    @Test
    void higherEpochRaisesFenceAndIsAccepted() {
        var d = new MessageDispatcher();
        assertTrue(d.acceptEpoch(command(5)));
        assertEquals(5L, d.latestAcceptedEpoch());
        // Equal epoch (same leader, later command) still accepted.
        assertTrue(d.acceptEpoch(command(5)));
    }

    @Test
    void lowerEpochFromDeposedLeaderIsRejected() {
        var d = new MessageDispatcher();
        assertTrue(d.acceptEpoch(command(5)));
        assertFalse(d.acceptEpoch(command(3)), "a command from a lower (deposed) epoch is fenced");
        assertEquals(5L, d.latestAcceptedEpoch(), "a rejected command must not lower the fence");
    }

    @Test
    void fenceTracksTheHighestEpochMonotonically() {
        var d = new MessageDispatcher();
        assertTrue(d.acceptEpoch(command(5)));
        assertTrue(d.acceptEpoch(command(7)));
        assertEquals(7L, d.latestAcceptedEpoch());
        assertFalse(d.acceptEpoch(command(6)), "6 < 7 is now stale even though it was once current");
        assertTrue(d.acceptEpoch(command(8)));
        assertEquals(8L, d.latestAcceptedEpoch());
    }
}
