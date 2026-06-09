package me.prexorjustin.prexorcloud.controller.crash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CrashTrendBucketer")
class CrashTrendBucketerTest {

    private static final Instant NOW = Instant.parse("2026-05-11T12:00:00Z");

    @Test
    @DisplayName("places points into the correct hourly bucket")
    void bucketsByHour() {
        var points = List.of(
                point("2026-05-11T11:30:00Z", "OOM"),
                point("2026-05-11T11:45:00Z", "ERROR"),
                point("2026-05-11T10:00:00Z", "OOM"),
                point("2026-05-11T03:15:00Z", "SIGKILL"));

        var trend = CrashTrendBucketer.bucket(points, NOW, Duration.ofHours(24), 24);

        assertEquals(24, trend.buckets().size());
        assertEquals(3600L, trend.bucketSeconds());
        assertEquals(86_400L, trend.windowSeconds());
        assertEquals(4, trend.total());

        // last bucket = [11:00, 12:00) holds 2 events
        assertEquals(2, trend.buckets().get(23).count());
        assertEquals(1, trend.buckets().get(22).count()); // [10:00, 11:00)
        assertEquals(1, trend.buckets().get(15).count()); // [03:00, 04:00)
        assertEquals(0, trend.buckets().get(0).count()); // [12:00 prev day, ...)
    }

    @Test
    @DisplayName("aggregates by classification per bucket and overall")
    void aggregatesClassification() {
        var points = List.of(
                point("2026-05-11T11:30:00Z", "OOM"),
                point("2026-05-11T11:45:00Z", "OOM"),
                point("2026-05-11T11:50:00Z", "ERROR"));

        var trend = CrashTrendBucketer.bucket(points, NOW, Duration.ofHours(24), 24);

        assertEquals(2, (int) trend.buckets().get(23).byClassification().get("OOM"));
        assertEquals(1, (int) trend.buckets().get(23).byClassification().get("ERROR"));
        assertEquals(2, (int) trend.totalsByClassification().get("OOM"));
        assertEquals(1, (int) trend.totalsByClassification().get("ERROR"));
    }

    @Test
    @DisplayName("ignores points outside the window")
    void dropsOutOfWindowPoints() {
        var points = List.of(
                point("2026-05-09T00:00:00Z", "OOM"), // 60h ago, outside 24h
                point("2026-05-11T12:30:00Z", "OOM"), // future, outside
                point("2026-05-11T11:30:00Z", "OOM"));

        var trend = CrashTrendBucketer.bucket(points, NOW, Duration.ofHours(24), 24);

        assertEquals(1, trend.total());
    }

    @Test
    @DisplayName("treats null classification as UNKNOWN")
    void nullClassificationBecomesUnknown() {
        var points = List.of(point("2026-05-11T11:30:00Z", null));

        var trend = CrashTrendBucketer.bucket(points, NOW, Duration.ofHours(24), 24);

        assertEquals(1, (int) trend.totalsByClassification().get("UNKNOWN"));
    }

    @Test
    @DisplayName("rejects invalid configuration")
    void rejectsInvalidArgs() {
        assertThrows(
                IllegalArgumentException.class,
                () -> CrashTrendBucketer.bucket(List.of(), NOW, Duration.ofHours(1), 0));
        assertThrows(
                IllegalArgumentException.class, () -> CrashTrendBucketer.bucket(List.of(), NOW, Duration.ZERO, 10));
    }

    private static CrashTrendPoint point(String iso, String classification) {
        return new CrashTrendPoint(Instant.parse(iso), classification);
    }
}
