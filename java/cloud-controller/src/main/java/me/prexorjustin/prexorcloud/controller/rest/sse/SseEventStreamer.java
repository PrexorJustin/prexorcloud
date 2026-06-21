package me.prexorjustin.prexorcloud.controller.rest.sse;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.atomic.AtomicLong;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;
import me.prexorjustin.prexorcloud.api.event.events.InstanceConsoleOutputEvent;
import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.metrics.MetricsCollector;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.config.RoutesConfig;
import io.javalin.http.sse.SseClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges the EventBus to SSE clients at {@code /api/v1/events/stream}.
 * Automatically forwards ALL events using {@link CloudEvent#type()} as the SSE
 * event type. No manual per-event subscription - new event types (including
 * custom module events) are forwarded automatically.
 *
 * <p>
 * Console output events are excluded (high-volume, streamed separately by
 * {@link ConsoleStreamer}).
 * </p>
 */
public final class SseEventStreamer implements MetricsCollector.SseStreamerProbe {

    private static final Logger logger = LoggerFactory.getLogger(SseEventStreamer.class);
    private static final int MAX_CLIENTS = 100;
    private static final int DEFAULT_REPLAY_CAPACITY = 2048;

    private final Queue<SseClient> clients = new ConcurrentLinkedQueue<>();
    private final ObjectMapper objectMapper;
    private final SseTicketManager ticketManager;
    private final ReplayStore replayStore;

    public SseEventStreamer(EventBus eventBus, ObjectMapper objectMapper, SseTicketManager ticketManager) {
        this(eventBus, objectMapper, ticketManager, DEFAULT_REPLAY_CAPACITY);
    }

    SseEventStreamer(EventBus eventBus, ObjectMapper objectMapper, SseTicketManager ticketManager, int replayCapacity) {
        this(eventBus, objectMapper, ticketManager, new InMemoryReplayStore(replayCapacity));
    }

    SseEventStreamer(
            EventBus eventBus, ObjectMapper objectMapper, SseTicketManager ticketManager, ReplayStore replayStore) {
        this.objectMapper = objectMapper;
        this.ticketManager = ticketManager;
        this.replayStore = replayStore;
        eventBus.subscribeAll(this::forwardEvent);
    }

    public void register(RoutesConfig routes) {
        routes.sse("/api/v1/events/stream", client -> {
            // Ticket-based auth only (short-lived, single-use, not logged in URLs)
            String ticket = client.ctx().queryParam("ticket");
            long lastSeenSequence = parseLastSeenSequence(
                    client.ctx().queryParam("lastSequence"), client.ctx().header("Last-Event-ID"));

            boolean authenticated = false;
            if (ticket != null) {
                authenticated = ticketManager.validate(ticket) != null;
            }

            if (!authenticated) {
                client.sendEvent("error", "{\"message\":\"Unauthorized\"}");
                client.close();
                return;
            }

            if (clients.size() >= MAX_CLIENTS) {
                client.sendEvent("error", "{\"message\":\"Too many connections\"}");
                client.close();
                logger.warn("SSE client rejected — connection limit ({}) reached", MAX_CLIENTS);
                return;
            }

            client.keepAlive();
            client.onClose(() -> clients.remove(client));
            clients.add(client);

            client.sendEvent(
                    "connected", Map.of("message", "Connected to event stream", "latestSequence", latestSequence()));
            replayTo(client, lastSeenSequence);
        });
    }

    private void forwardEvent(CloudEvent event) {
        // Console output is high-volume and has its own per-instance streaming
        if (event instanceof InstanceConsoleOutputEvent) return;

        try {
            Map<String, Object> data =
                    new LinkedHashMap<>(objectMapper.convertValue(event, new TypeReference<Map<String, Object>>() {}));
            data.put("type", event.type());
            long eventSequence = replayStore.nextSequence();
            data.put("sequence", eventSequence);
            var replayEvent = replayStore.remember(eventSequence, data);
            if (!clients.isEmpty()) {
                broadcast(List.of(replayEvent));
            }
        } catch (Exception e) {
            logger.warn("Failed to serialize event {}: {}", event.type(), e.getMessage());
        }
    }

    private void broadcast(List<ReplayEvent> events) {
        try (var scope = StructuredTaskScope.open()) {
            for (var client : clients) {
                scope.fork(() -> {
                    try {
                        for (var event : events) {
                            sendReplayEvent(client, event);
                        }
                    } catch (Exception _) {
                        clients.remove(client);
                    }
                    return null;
                });
            }
            scope.join();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private void replayTo(SseClient client, long lastSeenSequence) {
        if (replayGapAfter(lastSeenSequence)) {
            sendResyncRequired(client, lastSeenSequence);
            return;
        }
        for (var event : replayEventsAfter(lastSeenSequence)) {
            try {
                sendReplayEvent(client, event);
            } catch (Exception _) {
                clients.remove(client);
                return;
            }
        }
    }

    private void sendReplayEvent(SseClient client, ReplayEvent event) {
        client.sendEvent("message", event.envelope(), Long.toString(event.sequence()));
    }

    private void sendResyncRequired(SseClient client, long lastSeenSequence) {
        long latest = latestSequence();
        client.sendEvent(
                "message",
                Map.of(
                        "type",
                        "RESYNC_REQUIRED",
                        "lastSequence",
                        lastSeenSequence,
                        "earliestSequence",
                        earliestSequence(),
                        "latestSequence",
                        latest,
                        "timestamp",
                        Instant.now().toString()),
                Long.toString(latest));
    }

    private static long parseLastSeenSequence(String queryValue, String headerValue) {
        long querySequence = parseSequence(queryValue);
        return querySequence > 0 ? querySequence : parseSequence(headerValue);
    }

    private static long parseSequence(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            return Math.max(0, Long.parseLong(value));
        } catch (NumberFormatException _) {
            return 0;
        }
    }

    @Override
    public int clientCount() {
        return clients.size();
    }

    @Override
    public long latestSequence() {
        return replayStore.latestSequence();
    }

    @Override
    public long earliestSequence() {
        return replayStore.earliestSequence();
    }

    boolean replayGapAfter(long lastSeenSequence) {
        return replayStore.replayGapAfter(lastSeenSequence);
    }

    List<ReplayEvent> replayEventsAfter(long lastSeenSequence) {
        return replayStore.replayEventsAfter(lastSeenSequence);
    }

    interface ReplayStore {

        long nextSequence();

        ReplayEvent remember(long sequence, Map<String, Object> envelope);

        long latestSequence();

        long earliestSequence();

        boolean replayGapAfter(long lastSeenSequence);

        List<ReplayEvent> replayEventsAfter(long lastSeenSequence);
    }

    static final class InMemoryReplayStore implements ReplayStore {

        private final AtomicLong sequence = new AtomicLong();
        private final Object replayLock = new Object();
        private final Deque<ReplayEvent> replayBuffer = new ArrayDeque<>();
        private final int replayCapacity;

        InMemoryReplayStore(int replayCapacity) {
            if (replayCapacity < 1) {
                throw new IllegalArgumentException("replayCapacity must be at least 1");
            }
            this.replayCapacity = replayCapacity;
        }

        @Override
        public long nextSequence() {
            return sequence.incrementAndGet();
        }

        @Override
        public ReplayEvent remember(long eventSequence, Map<String, Object> envelope) {
            var replayEvent = immutableReplayEvent(eventSequence, envelope);
            synchronized (replayLock) {
                replayBuffer.addLast(replayEvent);
                while (replayBuffer.size() > replayCapacity) {
                    dropOldestReplayEvent();
                }
            }
            return replayEvent;
        }

        @Override
        public long latestSequence() {
            return sequence.get();
        }

        @Override
        public long earliestSequence() {
            synchronized (replayLock) {
                if (replayBuffer.isEmpty()) return sequence.get();
                long earliest = Long.MAX_VALUE;
                for (var event : replayBuffer) {
                    earliest = Math.min(earliest, event.sequence());
                }
                return earliest;
            }
        }

        @Override
        public boolean replayGapAfter(long lastSeenSequence) {
            synchronized (replayLock) {
                if (lastSeenSequence <= 0 && sequence.get() > replayBuffer.size()) return true;
                if (replayBuffer.isEmpty() || lastSeenSequence <= 0) return false;
                return lastSeenSequence < earliestSequence() - 1;
            }
        }

        @Override
        public List<ReplayEvent> replayEventsAfter(long lastSeenSequence) {
            synchronized (replayLock) {
                var events = new ArrayList<ReplayEvent>();
                for (var event : replayBuffer) {
                    if (event.sequence() > lastSeenSequence) {
                        events.add(event);
                    }
                }
                events.sort((left, right) -> Long.compare(left.sequence(), right.sequence()));
                return List.copyOf(events);
            }
        }

        private void dropOldestReplayEvent() {
            ReplayEvent oldest = null;
            for (var event : replayBuffer) {
                if (oldest == null || event.sequence() < oldest.sequence()) {
                    oldest = event;
                }
            }
            if (oldest != null) {
                replayBuffer.remove(oldest);
            }
        }
    }

    private static ReplayEvent immutableReplayEvent(long sequence, Map<String, Object> envelope) {
        return new ReplayEvent(sequence, Collections.unmodifiableMap(new LinkedHashMap<>(envelope)));
    }

    record ReplayEvent(long sequence, Map<String, Object> envelope) {}
}
