package me.prexorjustin.prexorcloud.daemon.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import me.prexorjustin.prexorcloud.protocol.CrashReport;

import org.junit.jupiter.api.Test;

/**
 * Verifies the client buffers undeliverable crash reports instead of dropping them (Phase 3). With no
 * established stream the client is disconnected, so a crash report cannot be sent — it must be
 * buffered for at-least-once replay on reconnect (the replay send itself needs a live stream and is
 * exercised in integration).
 */
final class DaemonGrpcClientCrashBufferTest {

    private static DaemonGrpcClient client() {
        // Never connected: state IDLE, no request stream — sends cannot go out.
        return new DaemonGrpcClient("ctrl-a", 9090, "node-1", "", 1024, Map.of(), null, null, null);
    }

    private static CrashReport report(String id) {
        return CrashReport.newBuilder().setInstanceId(id).setGroup("g").build();
    }

    @Test
    void buffersCrashReportsWhenNotConnected() {
        var c = client();
        c.sendCrashReport(report("i-1"));
        c.sendCrashReport(report("i-2"));
        assertEquals(2, c.bufferedCrashReportCount(), "undeliverable crash reports are buffered, not dropped");
    }
}
