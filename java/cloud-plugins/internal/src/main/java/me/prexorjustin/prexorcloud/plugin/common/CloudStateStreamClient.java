package me.prexorjustin.prexorcloud.plugin.common;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maintains the plugin-side controller event stream. The stream resumes from
 * the last accepted sequence, applies cheap local deltas when event payloads are
 * sufficient, and falls back to controller snapshots when replay gaps or
 * incomplete entity payloads require a resync.
 */
final class CloudStateStreamClient {

    private static final Logger logger = LoggerFactory.getLogger(CloudStateStreamClient.class);
    private static final long INITIAL_BACKOFF_MS = 1_000L;
    private static final long MAX_BACKOFF_MS = 30_000L;

    private final BaseControllerClient client;
    private final CloudStateCache cache;
    private final ExecutorService executor;
    private final AtomicReference<Closeable> activeStream = new AtomicReference<>();

    private volatile boolean running;
    private volatile boolean streaming;
    private volatile long lastSequence;

    CloudStateStreamClient(BaseControllerClient client, CloudStateCache cache) {
        this.client = Objects.requireNonNull(client, "client");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "cloud-state-stream");
            t.setDaemon(true);
            return t;
        });
    }

    void start() {
        if (running) {
            return;
        }
        running = true;
        executor.execute(this::runLoop);
    }

    void stop() {
        running = false;
        closeActiveStream();
        executor.shutdownNow();
    }

    boolean isStreaming() {
        return streaming;
    }

    long lastSequence() {
        return lastSequence;
    }

    private void runLoop() {
        long backoffMs = INITIAL_BACKOFF_MS;
        while (running) {
            try {
                HttpResponse<InputStream> response = client.openEventStream(lastSequence);
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("HTTP " + response.statusCode());
                }
                InputStream body = response.body();
                activeStream.set(body);
                streaming = true;
                backoffMs = INITIAL_BACKOFF_MS;
                try (var reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                    readFrames(reader, this::handleFrame);
                } finally {
                    streaming = false;
                    activeStream.compareAndSet(body, null);
                }
            } catch (Exception e) {
                streaming = false;
                if (running) {
                    logger.warn("Controller state stream disconnected: {}", e.getMessage());
                    sleep(backoffMs);
                    backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
                }
            }
        }
    }

    void handleFrame(SseFrame frame) {
        if (frame.data().isBlank()) {
            return;
        }
        Map<String, Object> data;
        try {
            data = BaseControllerClient.objectMapper.readValue(
                    frame.data(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            logger.warn("Ignoring malformed controller SSE payload: {}", e.getMessage());
            return;
        }

        String type = asString(data.get("type"));
        long sequence = resolveSequence(frame, data);
        if (sequence > 0 && lastSequence > 0 && sequence > lastSequence + 1) {
            cache.refreshNow();
            lastSequence = Math.max(lastSequence, sequence);
            return;
        }

        if ("RESYNC_REQUIRED".equals(type)) {
            cache.refreshNow();
            long latestSequence = asLong(data.get("latestSequence"));
            lastSequence = Math.max(lastSequence, Math.max(sequence, latestSequence));
            return;
        }

        if (type != null) {
            applyEvent(type, data);
        }
        if (sequence > 0) {
            lastSequence = Math.max(lastSequence, sequence);
        }
    }

    private void applyEvent(String type, Map<String, Object> data) {
        switch (type) {
            case "INSTANCE_STATE_CHANGED" -> applyInstanceStateChanged(data);
            case "INSTANCE_CRASHED" -> applyInstanceCrashed(data);
            case "INSTANCE_METRICS" -> applyInstanceMetrics(data);
            case "GROUP_CREATED", "GROUP_UPDATED" -> cache.refreshGroupsNow();
            case "GROUP_DELETED" -> cache.removeGroup(asString(data.get("groupName")));
            case "PLAYER_CONNECTED" -> applyPlayerConnected(data);
            case "PLAYER_DISCONNECTED" -> applyPlayerDisconnected(data);
            case "PLAYER_TRANSFER" -> applyPlayerTransfer(data);
            default -> {
                // Other controller events do not affect the plugin cluster cache.
            }
        }
    }

    private void applyInstanceStateChanged(Map<String, Object> data) {
        String instanceId = asString(data.get("instanceId"));
        String newState = asString(data.get("newState"));
        if (instanceId == null || newState == null || !cache.applyInstanceStateDelta(instanceId, newState)) {
            cache.refreshInstancesNow();
        }
    }

    private void applyInstanceCrashed(Map<String, Object> data) {
        String instanceId = asString(data.get("instanceId"));
        if (instanceId == null || !cache.applyInstanceStateDelta(instanceId, "CRASHED")) {
            cache.refreshInstancesNow();
        }
    }

    private void applyInstanceMetrics(Map<String, Object> data) {
        String instanceId = asString(data.get("instanceId"));
        int playerCount = asInt(data.get("playerCount"), -1);
        if (instanceId != null && playerCount >= 0 && !cache.applyInstancePlayerCount(instanceId, playerCount)) {
            cache.refreshInstancesNow();
        }
    }

    private void applyPlayerConnected(Map<String, Object> data) {
        String instanceId = asString(data.get("instanceId"));
        String group = asString(data.get("group"));
        boolean applied = true;
        if (instanceId != null) {
            applied = cache.applyInstancePlayerDelta(instanceId, 1);
        }
        if (group != null) {
            applied = cache.applyGroupOnlineDelta(group, 1) && applied;
        }
        if (!applied) {
            cache.refreshNow();
        }
    }

    private void applyPlayerDisconnected(Map<String, Object> data) {
        String instanceId = asString(data.get("instanceId"));
        String group = asString(data.get("group"));
        boolean applied = true;
        if (instanceId != null) {
            applied = cache.applyInstancePlayerDelta(instanceId, -1);
        }
        if (group != null) {
            applied = cache.applyGroupOnlineDelta(group, -1) && applied;
        }
        if (!applied) {
            cache.refreshNow();
        }
    }

    private void applyPlayerTransfer(Map<String, Object> data) {
        String fromInstanceId = asString(data.get("fromInstanceId"));
        String toInstanceId = asString(data.get("toInstanceId"));
        boolean applied = true;
        if (fromInstanceId != null) {
            applied = cache.applyInstancePlayerDelta(fromInstanceId, -1);
        }
        if (toInstanceId != null) {
            applied = cache.applyInstancePlayerDelta(toInstanceId, 1) && applied;
        }
        if (!applied) {
            cache.refreshInstancesNow();
        }
    }

    private long resolveSequence(SseFrame frame, Map<String, Object> data) {
        long sequence = asLong(data.get("sequence"));
        if (sequence > 0) {
            return sequence;
        }
        try {
            return frame.id() == null || frame.id().isBlank() ? 0 : Long.parseLong(frame.id());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    static void readFrames(Reader input, Consumer<SseFrame> consumer) throws java.io.IOException {
        var reader = input instanceof BufferedReader buffered ? buffered : new BufferedReader(input);
        String event = "message";
        String id = "";
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (data.length() > 0) {
                    consumer.accept(new SseFrame(event, id, data.substring(0, data.length() - 1)));
                }
                event = "message";
                id = "";
                data.setLength(0);
                continue;
            }
            if (line.startsWith(":")) {
                continue;
            }
            int separator = line.indexOf(':');
            String field = separator >= 0 ? line.substring(0, separator) : line;
            String value = separator >= 0 ? line.substring(separator + 1) : "";
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            switch (field) {
                case "event" -> event = value;
                case "id" -> id = value;
                case "data" -> data.append(value).append('\n');
                default -> {
                    // Unknown SSE fields are ignored by the browser EventSource model too.
                }
            }
        }
        if (data.length() > 0) {
            consumer.accept(new SseFrame(event, id, data.substring(0, data.length() - 1)));
        }
    }

    private void closeActiveStream() {
        Closeable stream = activeStream.getAndSet(null);
        if (stream != null) {
            try {
                stream.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void sleep(long backoffMs) {
        try {
            TimeUnit.MILLISECONDS.sleep(backoffMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static int asInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(value.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    record SseFrame(String event, String id, String data) {}
}
