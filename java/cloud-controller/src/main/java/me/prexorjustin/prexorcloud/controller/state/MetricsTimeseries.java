package me.prexorjustin.prexorcloud.controller.state;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory ring-buffer cache of resource metrics sampled at a fixed cadence,
 * surfaced to the dashboard via the {@code /timeseries} REST endpoints. State
 * is rebuilt from scratch on controller restart -- this is for sparklines and
 * trend bars, not durable analytics.
 */
public final class MetricsTimeseries {

    /** Default sample cadence — used by the no-arg constructor (tests, fixtures). */
    public static final long DEFAULT_SAMPLE_INTERVAL_MS = 15_000L;
    /** Default retention — used by the no-arg constructor (tests, fixtures). */
    public static final int DEFAULT_RETENTION_HOURS = 24;
    /** Legacy alias — kept so callers reading the historical sampler cadence keep compiling. */
    public static final long SAMPLE_INTERVAL_MS = DEFAULT_SAMPLE_INTERVAL_MS;

    public static final List<String> OVERVIEW_SERIES = List.of("players", "instances", "onlineNodes");
    public static final List<String> INSTANCE_SERIES = List.of("tps1m", "msptAvg", "heapUsedMb", "playerCount");
    public static final List<String> NODE_SERIES = List.of("cpuUsage", "usedMemoryMb", "instanceCount");

    private static final Map<String, Long> WINDOWS;

    static {
        var m = new LinkedHashMap<String, Long>();
        m.put("15m", Duration.ofMinutes(15).toMillis());
        m.put("1h", Duration.ofHours(1).toMillis());
        m.put("6h", Duration.ofHours(6).toMillis());
        m.put("24h", Duration.ofHours(24).toMillis());
        WINDOWS = Map.copyOf(m);
    }

    public static final String DEFAULT_WINDOW = "1h";
    public static final int DEFAULT_BUCKETS = 60;
    public static final int MAX_BUCKETS = 360;
    public static final int MIN_BUCKETS = 10;

    private final int maxSamples;
    private final RingBuffer overview;
    private final ConcurrentMap<String, RingBuffer> perInstance = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RingBuffer> perNode = new ConcurrentHashMap<>();

    public MetricsTimeseries() {
        this((int) (DEFAULT_SAMPLE_INTERVAL_MS / 1000), DEFAULT_RETENTION_HOURS);
    }

    /**
     * Build the time-series cache from operator-configured retention + sampling
     * cadence (controller.yml: {@code metrics.retentionHours} and
     * {@code metrics.collectionIntervalSeconds}). Ring-buffer capacity is sized
     * to fit {@code retentionHours} of samples at {@code sampleIntervalSec}.
     */
    public MetricsTimeseries(int sampleIntervalSec, int retentionHours) {
        long sampleIntervalMs = Math.max(1, sampleIntervalSec) * 1000L;
        long retentionMs = Math.max(1, retentionHours) * 3_600_000L;
        // Cap to keep memory bounded — a 7-day retention at 1s sampling is 604k samples
        // per series. 600k entries × 8 bytes × ~6 series is ~30 MB, OK; beyond that we
        // start to hurt. Operators wanting longer retention should sample less often.
        int candidate = (int) Math.min(Integer.MAX_VALUE, retentionMs / sampleIntervalMs);
        this.maxSamples = Math.min(600_000, Math.max(1, candidate));
        this.overview = new RingBuffer(OVERVIEW_SERIES.size(), maxSamples);
    }

    int maxSamples() {
        return maxSamples;
    }

    public void recordOverview(long timestampMs, int players, int instances, int onlineNodes) {
        overview.record(timestampMs, new double[] {players, instances, onlineNodes});
    }

    public void recordInstance(long timestampMs, String instanceId, InstanceMetrics m) {
        perInstance
                .computeIfAbsent(instanceId, k -> new RingBuffer(INSTANCE_SERIES.size(), maxSamples))
                .record(timestampMs, new double[] {m.tps1m(), m.msptAvg(), m.heapUsedMb(), m.playerCount()});
    }

    public void recordNode(long timestampMs, NodeState n) {
        perNode.computeIfAbsent(n.nodeId(), k -> new RingBuffer(NODE_SERIES.size(), maxSamples))
                .record(timestampMs, new double[] {n.cpuUsage(), n.usedMemoryMb(), n.instanceCount()});
    }

    public void forgetInstance(String instanceId) {
        perInstance.remove(instanceId);
    }

    public void forgetNode(String nodeId) {
        perNode.remove(nodeId);
    }

    public void retainInstances(java.util.Set<String> liveIds) {
        perInstance.keySet().retainAll(liveIds);
    }

    public void retainNodes(java.util.Set<String> liveIds) {
        perNode.keySet().retainAll(liveIds);
    }

    public Response queryOverview(long windowMs, int buckets, long nowMs) {
        return bucketize(overview, OVERVIEW_SERIES, windowMs, buckets, nowMs);
    }

    public Response queryInstance(String instanceId, long windowMs, int buckets, long nowMs) {
        var buf = perInstance.get(instanceId);
        return bucketize(buf, INSTANCE_SERIES, windowMs, buckets, nowMs);
    }

    public Response queryNode(String nodeId, long windowMs, int buckets, long nowMs) {
        var buf = perNode.get(nodeId);
        return bucketize(buf, NODE_SERIES, windowMs, buckets, nowMs);
    }

    public static long parseWindowMs(String raw) {
        if (raw == null || raw.isBlank()) return WINDOWS.get(DEFAULT_WINDOW);
        Long m = WINDOWS.get(raw);
        if (m == null) {
            throw new IllegalArgumentException("window must be one of " + WINDOWS.keySet());
        }
        return m;
    }

    public static int parseBuckets(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_BUCKETS;
        int parsed;
        try {
            parsed = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("buckets must be an integer");
        }
        if (parsed < MIN_BUCKETS || parsed > MAX_BUCKETS) {
            throw new IllegalArgumentException("buckets must be in [" + MIN_BUCKETS + ", " + MAX_BUCKETS + "]");
        }
        return parsed;
    }

    public static List<String> validWindows() {
        return List.copyOf(WINDOWS.keySet());
    }

    private static Response bucketize(
            RingBuffer buf, List<String> seriesNames, long windowMs, int buckets, long nowMs) {
        long bucketWidthMs = windowMs / buckets;
        long startMs = nowMs - windowMs;
        int width = seriesNames.size();

        double[][] sums = new double[width][buckets];
        int[][] counts = new int[width][buckets];

        if (buf != null) {
            buf.forEach(startMs, nowMs, (ts, values) -> {
                int idx = (int) ((ts - startMs) / bucketWidthMs);
                if (idx < 0) idx = 0;
                if (idx >= buckets) idx = buckets - 1;
                for (int i = 0; i < width; i++) {
                    sums[i][idx] += values[i];
                    counts[i][idx]++;
                }
            });
        }

        Map<String, List<Double>> series = new LinkedHashMap<>();
        for (int i = 0; i < width; i++) {
            List<Double> column = new ArrayList<>(buckets);
            for (int b = 0; b < buckets; b++) {
                if (counts[i][b] == 0) {
                    column.add(null);
                } else {
                    column.add(sums[i][b] / counts[i][b]);
                }
            }
            series.put(seriesNames.get(i), column);
        }
        return new Response(windowMs, bucketWidthMs, buckets, startMs, series);
    }

    /** REST payload for a timeseries query. Empty buckets carry {@code null}. */
    public record Response(
            long windowMs, long bucketWidthMs, int buckets, long startedAtMs, Map<String, List<Double>> series) {}

    @FunctionalInterface
    private interface SampleConsumer {
        void accept(long timestampMs, double[] values);
    }

    /**
     * Fixed-capacity circular buffer. Writes wrap; reads copy out the chronological slice.
     */
    static final class RingBuffer {
        private final int width;
        private final int capacity;
        private final long[] timestamps;
        private final double[][] values; // [series][slot]
        private int head;
        private int size;

        RingBuffer(int width, int capacity) {
            this.width = width;
            this.capacity = capacity;
            this.timestamps = new long[capacity];
            this.values = new double[width][capacity];
        }

        synchronized void record(long ts, double[] sample) {
            timestamps[head] = ts;
            for (int i = 0; i < width; i++) values[i][head] = sample[i];
            head = (head + 1) % capacity;
            if (size < capacity) size++;
        }

        synchronized void forEach(long startMs, long endMs, SampleConsumer consumer) {
            int start = (head - size + capacity) % capacity;
            double[] scratch = new double[width];
            for (int i = 0; i < size; i++) {
                int slot = (start + i) % capacity;
                long ts = timestamps[slot];
                if (ts < startMs || ts > endMs) continue;
                for (int s = 0; s < width; s++) scratch[s] = values[s][slot];
                consumer.accept(ts, scratch);
            }
        }

        synchronized int size() {
            return size;
        }
    }
}
