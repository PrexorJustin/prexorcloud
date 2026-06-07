package me.prexorjustin.prexorcloud.controller.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MetricsTimeseriesTest {

    @Test
    @DisplayName("parseWindowMs accepts the four documented windows and defaults to 1h")
    void parseWindowMs() {
        assertEquals(Duration.ofMinutes(15).toMillis(), MetricsTimeseries.parseWindowMs("15m"));
        assertEquals(Duration.ofHours(1).toMillis(), MetricsTimeseries.parseWindowMs("1h"));
        assertEquals(Duration.ofHours(6).toMillis(), MetricsTimeseries.parseWindowMs("6h"));
        assertEquals(Duration.ofHours(24).toMillis(), MetricsTimeseries.parseWindowMs("24h"));
        assertEquals(Duration.ofHours(1).toMillis(), MetricsTimeseries.parseWindowMs(null));
        assertEquals(Duration.ofHours(1).toMillis(), MetricsTimeseries.parseWindowMs(""));
    }

    @Test
    @DisplayName("parseWindowMs rejects unknown windows")
    void parseWindowMsInvalid() {
        assertThrows(IllegalArgumentException.class, () -> MetricsTimeseries.parseWindowMs("30s"));
        assertThrows(IllegalArgumentException.class, () -> MetricsTimeseries.parseWindowMs("7d"));
    }

    @Test
    @DisplayName("parseBuckets clamps to [MIN, MAX] and defaults to 60")
    void parseBuckets() {
        assertEquals(60, MetricsTimeseries.parseBuckets(null));
        assertEquals(60, MetricsTimeseries.parseBuckets(""));
        assertEquals(60, MetricsTimeseries.parseBuckets("60"));
        assertEquals(10, MetricsTimeseries.parseBuckets("10"));
        assertEquals(360, MetricsTimeseries.parseBuckets("360"));
        assertThrows(IllegalArgumentException.class, () -> MetricsTimeseries.parseBuckets("9"));
        assertThrows(IllegalArgumentException.class, () -> MetricsTimeseries.parseBuckets("361"));
        assertThrows(IllegalArgumentException.class, () -> MetricsTimeseries.parseBuckets("abc"));
    }

    @Test
    @DisplayName("queryOverview returns null-padded buckets when no samples recorded")
    void queryOverviewEmpty() {
        var ts = new MetricsTimeseries();
        long now = 1_700_000_000_000L;
        var resp = ts.queryOverview(Duration.ofMinutes(15).toMillis(), 60, now);

        assertEquals(60, resp.buckets());
        assertEquals(Duration.ofMinutes(15).toMillis(), resp.windowMs());
        assertEquals(Duration.ofMinutes(15).toMillis() / 60, resp.bucketWidthMs());
        assertEquals(now - resp.windowMs(), resp.startedAtMs());
        assertEquals(MetricsTimeseries.OVERVIEW_SERIES.size(), resp.series().size());
        for (var col : resp.series().values()) {
            assertEquals(60, col.size());
            for (Double d : col) assertNull(d);
        }
    }

    @Test
    @DisplayName("recorded overview samples fall into the right bucket and average per slot")
    void overviewSamplesBucketize() {
        var ts = new MetricsTimeseries();
        long now = 1_700_000_000_000L;
        long window = Duration.ofMinutes(15).toMillis();
        long bucketWidth = window / 60;

        // Two samples in the same bucket (bucket 50 from window start)
        long b50 = now - window + bucketWidth * 50;
        ts.recordOverview(b50 + 1, 10, 5, 2);
        ts.recordOverview(b50 + 2, 20, 7, 3);
        // One sample two buckets later
        long b52 = now - window + bucketWidth * 52;
        ts.recordOverview(b52 + 1, 100, 8, 4);

        var resp = ts.queryOverview(window, 60, now);
        List<Double> players = resp.series().get("players");
        List<Double> instances = resp.series().get("instances");
        List<Double> onlineNodes = resp.series().get("onlineNodes");
        assertNotNull(players);
        assertNotNull(instances);
        assertNotNull(onlineNodes);

        assertEquals(15.0, players.get(50));
        assertEquals(6.0, instances.get(50));
        assertEquals(2.5, onlineNodes.get(50));
        assertNull(players.get(51));
        assertEquals(100.0, players.get(52));
    }

    @Test
    @DisplayName("samples outside the window are excluded")
    void samplesOutsideWindowDropped() {
        var ts = new MetricsTimeseries();
        long now = 1_700_000_000_000L;
        long window = Duration.ofMinutes(15).toMillis();
        ts.recordOverview(now - window - 60_000L, 999, 999, 999); // before
        ts.recordOverview(now + 60_000L, 999, 999, 999); // future (clock skew safety)

        var resp = ts.queryOverview(window, 60, now);
        for (var col : resp.series().values()) {
            for (Double d : col) assertNull(d);
        }
    }

    @Test
    @DisplayName("retainInstances/retainNodes drop buffers for absent ids")
    void retainPrunesBuffers() {
        var ts = new MetricsTimeseries();
        long now = 1_700_000_000_000L;
        var metrics = new InstanceMetrics(
                "inst-1",
                20.0,
                20.0,
                20.0,
                5.0,
                512,
                2048,
                1024,
                1,
                10,
                8,
                4,
                3,
                20,
                1,
                0,
                0,
                List.of(),
                "1.20.4",
                1,
                1000,
                java.time.Instant.ofEpochMilli(now));
        ts.recordInstance(now, "inst-1", metrics);
        ts.recordInstance(now, "inst-2", metrics);
        ts.retainInstances(java.util.Set.of("inst-1"));

        var keep = ts.queryInstance("inst-1", Duration.ofMinutes(15).toMillis(), 60, now);
        var gone = ts.queryInstance("inst-2", Duration.ofMinutes(15).toMillis(), 60, now);
        assertTrue(keep.series().get("tps1m").stream().anyMatch(d -> d != null && d == 20.0));
        assertTrue(gone.series().get("tps1m").stream().allMatch(java.util.Objects::isNull));
    }
}
