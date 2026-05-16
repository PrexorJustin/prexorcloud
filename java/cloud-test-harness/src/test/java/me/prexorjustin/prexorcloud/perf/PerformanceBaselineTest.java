package me.prexorjustin.prexorcloud.perf;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import me.prexorjustin.prexorcloud.controller.group.GroupConfig;
import me.prexorjustin.prexorcloud.controller.metrics.MetricsCollector;
import me.prexorjustin.prexorcloud.harness.SseListener;
import me.prexorjustin.prexorcloud.harness.TestCluster;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Performance baseline runner. Excluded from the default test pass; invoked by the
 * {@code perfBaselines} gradle task and the nightly CI job. Measurements:
 *
 * <ul>
 *   <li>controller cold start (TestCluster.startWithRedis() → /api/v1/system/status 200)</li>
 *   <li>coordination-store latency (Lettuce SET/GET round trip)</li>
 *   <li>SSE event latency (REST → SSE round trip)</li>
 *   <li>scheduler tick at N groups (Micrometer p50/p95)</li>
 * </ul>
 *
 * Instance start p50/p95 is intentionally deferred — it requires real MC process spawn.
 * Outputs JSON to {@code -Dperf.report.file=...} for the drift comparator to consume.
 */
@Tag("perf")
class PerformanceBaselineTest {

    private static final int SCHEDULER_TICK_GROUPS =
            Integer.parseInt(System.getProperty("perf.scheduler.groups", "1000"));
    private static final int SCHEDULER_TICK_SAMPLES =
            Integer.parseInt(System.getProperty("perf.scheduler.samples", "8"));
    private static final int COORDINATION_SAMPLES =
            Integer.parseInt(System.getProperty("perf.coordination.samples", "500"));
    private static final int SSE_SAMPLES = Integer.parseInt(System.getProperty("perf.sse.samples", "30"));

    @Test
    void publishBaselines() throws Exception {
        assumeTrue(TestCluster.mongoAvailable(), "MongoDB not reachable; perf baselines skipped");
        assumeTrue(TestCluster.redisAvailable(), "Redis not reachable; perf baselines skipped");

        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        ObjectNode report = mapper.createObjectNode();
        report.put("schemaVersion", 1);
        report.put("generatedAt", Instant.now().toString());

        ObjectNode env = report.putObject("env");
        env.put("javaVersion", System.getProperty("java.version"));
        env.put("javaVendor", System.getProperty("java.vendor"));
        env.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        env.put("commit", Optional.ofNullable(System.getenv("GITHUB_SHA")).orElse("local"));

        ObjectNode metrics = report.putObject("metrics");

        measureColdStart(metrics);

        try (TestCluster cluster = TestCluster.startWithRedis()) {
            measureCoordinationStore(cluster, metrics);
            measureSseLatency(cluster, metrics);
            measureSchedulerTick(cluster, metrics);
        }

        Path reportFile =
                Paths.get(System.getProperty("perf.report.file", "build/reports/perf-baselines/baseline-report.json"));
        Files.createDirectories(reportFile.getParent());
        Files.writeString(reportFile, mapper.writeValueAsString(report));
        System.out.println("[perf] baseline report written to " + reportFile.toAbsolutePath());
    }

    // --- Cold start ---

    private static void measureColdStart(ObjectNode metrics) throws Exception {
        long start = System.nanoTime();
        try (TestCluster cluster = TestCluster.startWithRedis()) {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(cluster.restBaseUrl() + "/api/v1/system/status"))
                    .header("Authorization", "Bearer " + cluster.adminJwtToken())
                    .GET()
                    .build();

            long deadline = System.currentTimeMillis() + 30_000;
            int status = -1;
            while (System.currentTimeMillis() < deadline) {
                HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
                status = resp.statusCode();
                if (status == 200) break;
                Thread.sleep(50);
            }
            assertTrue(status == 200, "Controller did not become ready: status=" + status);
        }
        long ms = Duration.ofNanos(System.nanoTime() - start).toMillis();
        ObjectNode node = metrics.putObject("controllerColdStartMs");
        node.put("value", ms);
    }

    // --- Coordination store latency ---

    private static void measureCoordinationStore(TestCluster cluster, ObjectNode metrics) {
        String redisUri = cluster.redisUri();
        if (redisUri == null) return;

        List<Long> samples = new ArrayList<>(COORDINATION_SAMPLES);
        RedisClient client = RedisClient.create(redisUri);
        try (StatefulRedisConnection<String, String> connection = client.connect()) {
            String key = "perf:baseline:" + UUID.randomUUID();
            // Warmup
            for (int i = 0; i < 50; i++) {
                connection.sync().set(key, "v" + i);
                connection.sync().get(key);
            }
            for (int i = 0; i < COORDINATION_SAMPLES; i++) {
                long t0 = System.nanoTime();
                connection.sync().set(key, "v" + i);
                connection.sync().get(key);
                samples.add(System.nanoTime() - t0);
            }
            connection.sync().del(key);
        } finally {
            client.shutdown();
        }
        writeNanoSamples(metrics.putObject("coordinationStoreSetGetMs"), samples);
    }

    // --- SSE event latency ---

    private static void measureSseLatency(TestCluster cluster, ObjectNode metrics) throws Exception {
        try (SseListener listener = SseListener.connectToEventStream(cluster.restBaseUrl(), cluster.adminJwtToken())) {
            assertTrue(listener.awaitConnected(5_000), "SSE failed to connect for perf baseline");

            HttpClient http = HttpClient.newHttpClient();
            ObjectMapper mapper = new ObjectMapper();
            List<Long> samples = new ArrayList<>(SSE_SAMPLES);

            for (int i = 0; i < SSE_SAMPLES; i++) {
                String name = "perf-sse-" + i + "-" + UUID.randomUUID();
                String body = mapper.writeValueAsString(Map.of(
                        "name",
                        name,
                        "platform",
                        "PAPER",
                        "platformVersion",
                        "1.21",
                        "minInstances",
                        0,
                        "maxInstances",
                        0,
                        "templates",
                        List.of()));
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(cluster.restBaseUrl() + "/api/v1/groups"))
                        .header("Authorization", "Bearer " + cluster.adminJwtToken())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();

                long t0 = System.nanoTime();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() / 100 != 2) {
                    continue; // give up on this sample, keep going
                }
                SseListener.SseEvent event = listener.awaitEvent("GROUP_CREATED", "groupName", name, 5_000);
                if (event != null) {
                    samples.add(System.nanoTime() - t0);
                }
            }
            writeNanoSamples(metrics.putObject("sseEventLatencyMs"), samples);
        }
    }

    // --- Scheduler tick at N groups ---

    private static void measureSchedulerTick(TestCluster cluster, ObjectNode metrics) throws Exception {
        for (int i = 0; i < SCHEDULER_TICK_GROUPS; i++) {
            cluster.controller().groupManager().create(synthGroup("perf-grp-" + i));
        }

        // Wait for several scheduler ticks to populate the timer.
        MetricsCollector mc = cluster.controller().metricsCollector();
        assertNotNull(mc, "MetricsCollector required for perf baselines");

        long deadline = System.currentTimeMillis() + 60_000;
        long observedCount = 0;
        while (System.currentTimeMillis() < deadline && observedCount < SCHEDULER_TICK_SAMPLES) {
            Thread.sleep(2_500);
            observedCount = readSchedulerTickCount(mc);
        }

        ObjectNode node = metrics.putObject("schedulerTickMs");
        node.put("groups", SCHEDULER_TICK_GROUPS);
        node.put("samples", observedCount);
        // Histogram percentiles come from Prometheus exposition (publishPercentiles registers them).
        Map<Double, Double> percentiles = scrapePercentiles(mc, "prexorcloud_scheduler_tick_duration_seconds");
        Double p50 = percentiles.get(0.5);
        Double p95 = percentiles.get(0.95);
        Double p99 = percentiles.get(0.99);
        if (p50 != null) node.put("p50", p50 * 1000.0);
        if (p95 != null) node.put("p95", p95 * 1000.0);
        if (p99 != null) node.put("p99", p99 * 1000.0);
    }

    // --- Helpers ---

    private static GroupConfig synthGroup(String name) {
        return new GroupConfig(
                name,
                null,
                "PAPER",
                "1.21",
                "server.jar",
                List.of(),
                "DYNAMIC",
                0,
                0,
                100,
                0.8,
                300,
                30,
                false,
                0.2,
                0,
                "LOWEST_PLAYERS",
                30000,
                30100,
                120,
                30,
                false,
                0,
                false,
                List.of(),
                List.of(),
                null,
                false,
                List.of(),
                0,
                false,
                "",
                List.of(),
                "ROLLING",
                List.of(),
                List.of(),
                "",
                0,
                1024,
                0.0,
                0L,
                List.of(),
                Map.of(),
                List.of(),
                "STATIC",
                30,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of());
    }

    private static long readSchedulerTickCount(MetricsCollector mc) {
        return mc.registry().find("prexorcloud.scheduler.tick.duration").timers().stream()
                .mapToLong(t -> t.count())
                .sum();
    }

    /**
     * Parse Prometheus exposition output for histogram quantiles published via
     * {@code Timer.publishPercentiles(…)}. The lines we care about look like:
     * <pre>
     * prexorcloud_scheduler_tick_duration_seconds{quantile="0.5",} 0.018
     * </pre>
     */
    private static Map<Double, Double> scrapePercentiles(MetricsCollector mc, String metric) {
        String scrape = mc.registry().scrape();
        Map<Double, Double> out = new java.util.HashMap<>();
        for (String line : scrape.split("\n")) {
            if (line.startsWith("#")) continue;
            if (!line.startsWith(metric)) continue;
            int qIdx = line.indexOf("quantile=\"");
            if (qIdx < 0) continue;
            int qEnd = line.indexOf('"', qIdx + 10);
            if (qEnd < 0) continue;
            int valueStart = line.lastIndexOf(' ');
            if (valueStart < 0) continue;
            try {
                double quantile = Double.parseDouble(line.substring(qIdx + 10, qEnd));
                double value = Double.parseDouble(line.substring(valueStart + 1).trim());
                out.put(quantile, value);
            } catch (NumberFormatException _) {
                // Skip malformed line.
            }
        }
        return out;
    }

    private static void writeNanoSamples(ObjectNode node, List<Long> samples) {
        if (samples.isEmpty()) {
            node.put("samples", 0);
            return;
        }
        Collections.sort(samples);
        node.put("samples", samples.size());
        node.put("p50", percentile(samples, 0.5) / 1_000_000.0);
        node.put("p95", percentile(samples, 0.95) / 1_000_000.0);
        node.put("p99", percentile(samples, 0.99) / 1_000_000.0);
        node.put("max", samples.get(samples.size() - 1) / 1_000_000.0);
    }

    private static long percentile(List<Long> sortedSamples, double q) {
        int n = sortedSamples.size();
        int idx = (int) Math.min(n - 1, Math.max(0, Math.round(q * (n - 1))));
        return sortedSamples.get(idx);
    }
}
