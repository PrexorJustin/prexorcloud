package me.prexorjustin.prexorcloud.controller.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;

import me.prexorjustin.prexorcloud.controller.crash.CrashLoopDetector;
import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.scheduler.Scheduler;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.controller.state.InstanceInfo;
import me.prexorjustin.prexorcloud.protocol.InstanceState;
import me.prexorjustin.prexorcloud.protocol.StartFailureDisposition;
import me.prexorjustin.prexorcloud.protocol.StartInstanceAck;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers the live-run finding #9 fix: a terminal {@code StartInstance} rejection (e.g. a PERMANENT
 * {@code RUNTIME_PROVISION_FAILED} from an empty catalog) must feed the {@link CrashLoopDetector} so
 * the group's auto-placement is paused instead of re-dispatching in a tight loop.
 */
class DaemonCommandAckHandlerTest {

    private EventBus eventBus;
    private ClusterState clusterState;
    private CrashLoopDetector crashLoopDetector;

    @BeforeEach
    void setUp() {
        eventBus = new EventBus();
        clusterState = new ClusterState(eventBus);
        // threshold 3 within a 60s window — matches the controller's default wiring.
        crashLoopDetector = new CrashLoopDetector(3, 60, eventBus);
    }

    @AfterEach
    void tearDown() {
        eventBus.shutdown();
    }

    private DaemonCommandAckHandler handler(Scheduler scheduler) {
        return new DaemonCommandAckHandler(clusterState, crashLoopDetector, () -> scheduler);
    }

    private static StartInstanceAck reject(StartFailureDisposition disposition) {
        return StartInstanceAck.newBuilder()
                .setInstanceId("lobby-1")
                .setAccepted(false)
                .setFailureDisposition(disposition)
                .setErrorCode("RUNTIME_PROVISION_FAILED")
                .build();
    }

    @Test
    void permanentStartFailurePausesGroupAfterThreshold() {
        clusterState.addInstance(
                new InstanceInfo("lobby-1", "lobby", "node-1", InstanceState.SCHEDULED, 25565, 0, 0, Instant.now()));
        var handler = handler(null);

        handler.handleStartInstanceAck("node-1", reject(StartFailureDisposition.PERMANENT));
        assertFalse(crashLoopDetector.isCrashLoopPaused("lobby"), "one failure should not pause");
        handler.handleStartInstanceAck("node-1", reject(StartFailureDisposition.PERMANENT));
        assertFalse(crashLoopDetector.isCrashLoopPaused("lobby"), "two failures should not pause");

        handler.handleStartInstanceAck("node-1", reject(StartFailureDisposition.PERMANENT));
        assertTrue(crashLoopDetector.isCrashLoopPaused("lobby"), "threshold reached → group paused");
        assertEquals(
                InstanceState.CRASHED,
                clusterState.getInstance("lobby-1").orElseThrow().state());
    }

    @Test
    void unspecifiedDispositionIsTreatedAsTerminalAndFeedsDetector() {
        clusterState.addInstance(
                new InstanceInfo("lobby-1", "lobby", "node-1", InstanceState.SCHEDULED, 25565, 0, 0, Instant.now()));
        var handler = handler(null);

        for (int i = 0; i < 3; i++) {
            handler.handleStartInstanceAck(
                    "node-1", reject(StartFailureDisposition.START_FAILURE_DISPOSITION_UNSPECIFIED));
        }
        assertTrue(crashLoopDetector.isCrashLoopPaused("lobby"), "unspecified defaults to PERMANENT → terminal");
    }

    @Test
    void acceptedAckDoesNotFeedCrashLoop() {
        clusterState.addInstance(
                new InstanceInfo("lobby-1", "lobby", "node-1", InstanceState.STARTING, 25565, 0, 0, Instant.now()));
        var scheduler = mock(Scheduler.class);
        var handler = handler(scheduler);

        for (int i = 0; i < 5; i++) {
            handler.handleStartInstanceAck(
                    "node-1",
                    StartInstanceAck.newBuilder()
                            .setInstanceId("lobby-1")
                            .setAccepted(true)
                            .build());
        }
        assertFalse(crashLoopDetector.isCrashLoopPaused("lobby"), "accepted starts must never pause the group");
    }

    @Test
    void transientRejectThatStillRetriesDoesNotFeedCrashLoop() {
        clusterState.addInstance(
                new InstanceInfo("lobby-1", "lobby", "node-1", InstanceState.SCHEDULED, 25565, 0, 0, Instant.now()));
        var scheduler = mock(Scheduler.class);
        when(scheduler.retryStart(anyString(), anyInt(), anyString())).thenReturn(true);
        var handler = handler(scheduler);

        for (int i = 0; i < 5; i++) {
            handler.handleStartInstanceAck("node-1", reject(StartFailureDisposition.TRANSIENT));
        }
        assertFalse(
                crashLoopDetector.isCrashLoopPaused("lobby"),
                "a transient failure that is still being retried is not a crash loop");
        assertEquals(
                InstanceState.SCHEDULED,
                clusterState.getInstance("lobby-1").orElseThrow().state());
    }
}
