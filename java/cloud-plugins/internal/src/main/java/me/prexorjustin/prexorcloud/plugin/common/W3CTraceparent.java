package me.prexorjustin.prexorcloud.plugin.common;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates W3C {@code traceparent} header values for plugin → controller calls (northstar-plan
 * Track D.3 — MC-plugin trace-context hop).
 *
 * <p>MC-plugins run inside the Bukkit/Velocity classloader and deliberately do <em>not</em> bundle
 * the OpenTelemetry SDK (heavyweight, classloader-fragile). Instead each plugin-originated request
 * carries a freshly minted, sampled trace id; the controller's {@code HttpServerTracing} extracts it
 * and opens a SERVER span continuing that trace, so a player-join / metrics report is traceable
 * end-to-end (plugin action → controller → daemon) without an SDK on the edge.
 *
 * <p>The plugin is the trace originator and therefore makes the head sampling decision — the flags
 * byte is {@code 01} (sampled). When the controller has telemetry disabled the header is simply
 * ignored (its tracing filter only runs when enabled), so this is inert by default and never floods
 * a collector that isn't there. Per-plugin sampling ratios are a possible follow-up.
 */
public final class W3CTraceparent {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private W3CTraceparent() {}

    /** A new {@code 00-<32 hex trace-id>-<16 hex span-id>-01} value. Trace/span ids are never all-zero. */
    public static String random() {
        byte[] traceId = new byte[16];
        byte[] spanId = new byte[8];
        ThreadLocalRandom.current().nextBytes(traceId);
        ThreadLocalRandom.current().nextBytes(spanId);
        // The spec forbids the all-zero trace-id / span-id; a random source effectively never hits
        // it, but guard anyway so a degenerate RNG can't emit an invalid (silently dropped) header.
        if (allZero(traceId)) {
            traceId[15] = 1;
        }
        if (allZero(spanId)) {
            spanId[7] = 1;
        }
        return "00-" + hex(traceId) + "-" + hex(spanId) + "-01";
    }

    private static boolean allZero(byte[] bytes) {
        for (byte b : bytes) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}
