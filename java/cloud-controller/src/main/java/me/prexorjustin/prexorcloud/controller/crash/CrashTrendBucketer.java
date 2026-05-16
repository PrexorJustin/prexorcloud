package me.prexorjustin.prexorcloud.controller.crash;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Buckets a stream of {@link CrashTrendPoint}s into fixed-width windows for
 * sparkline / trend rendering on the dashboard.
 */
public final class CrashTrendBucketer {

    private CrashTrendBucketer() {}

    public record Bucket(Instant ts, int count, Map<String, Integer> byClassification) {}

    public record Trend(
            Instant windowStart,
            Instant windowEnd,
            long windowSeconds,
            long bucketSeconds,
            List<Bucket> buckets,
            Map<String, Integer> totalsByClassification,
            int total) {}

    public static Trend bucket(List<CrashTrendPoint> points, Instant windowEnd, Duration window, int bucketCount) {
        if (bucketCount <= 0) {
            throw new IllegalArgumentException("bucketCount must be > 0");
        }
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be positive");
        }

        long windowSeconds = window.toSeconds();
        long bucketSeconds = Math.max(1L, windowSeconds / bucketCount);
        Instant windowStart = windowEnd.minusSeconds(bucketSeconds * bucketCount);

        var counts = new int[bucketCount];
        var byClass = new LinkedHashMap<Integer, Map<String, Integer>>();
        var totals = new TreeMap<String, Integer>();
        int total = 0;

        for (var p : points) {
            if (p.crashedAt().isBefore(windowStart) || !p.crashedAt().isBefore(windowEnd)) {
                continue;
            }
            long offset = p.crashedAt().getEpochSecond() - windowStart.getEpochSecond();
            int idx = (int) (offset / bucketSeconds);
            if (idx < 0 || idx >= bucketCount) continue;
            counts[idx]++;
            String cls = p.classification() != null ? p.classification() : "UNKNOWN";
            byClass.computeIfAbsent(idx, k -> new LinkedHashMap<>()).merge(cls, 1, Integer::sum);
            totals.merge(cls, 1, Integer::sum);
            total++;
        }

        var buckets = new java.util.ArrayList<Bucket>(bucketCount);
        for (int i = 0; i < bucketCount; i++) {
            var ts = windowStart.plusSeconds((long) i * bucketSeconds);
            var classes = byClass.getOrDefault(i, Map.of());
            buckets.add(new Bucket(ts, counts[i], classes));
        }
        return new Trend(windowStart, windowEnd, windowSeconds, bucketSeconds, buckets, totals, total);
    }
}
