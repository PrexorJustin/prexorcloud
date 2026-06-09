package me.prexorjustin.prexorcloud.harness;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * SSE client that connects to the controller's event stream and collects events.
 * Supports waiting for specific event types with timeouts.
 */
public final class SseListener implements AutoCloseable {

    private final List<SseEvent> events = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final ObjectMapper mapper = new ObjectMapper();
    private Thread readerThread;

    public record SseEvent(String eventType, String data, JsonNode json) {}

    /**
     * Connect to the main event stream at /api/v1/events/stream.
     */
    public static SseListener connectToEventStream(String baseUrl, String jwtToken) {
        return connectToEventStream(baseUrl, jwtToken, null);
    }

    public static SseListener connectToEventStream(String baseUrl, String jwtToken, Long lastSequence) {
        var listener = new SseListener();
        String ticket = issueTicket(baseUrl, jwtToken);
        listener.connect(buildSseUrl(baseUrl + "/api/v1/events/stream", ticket, lastSequence));
        return listener;
    }

    /**
     * Connect to a per-instance console stream.
     */
    public static SseListener connectToConsole(String baseUrl, String jwtToken, String instanceId) {
        var listener = new SseListener();
        String ticket = issueTicket(baseUrl, jwtToken);
        listener.connect(buildSseUrl(baseUrl + "/api/v1/services/" + instanceId + "/console", ticket, null));
        return listener;
    }

    private static String issueTicket(String baseUrl, String jwtToken) {
        if (jwtToken == null || jwtToken.isBlank()) {
            return null;
        }
        try {
            var client = HttpClient.newHttpClient();
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/events/ticket"))
                    .header("Authorization", "Bearer " + jwtToken)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            JsonNode body = new ObjectMapper().readTree(response.body());
            return body.path("ticket").asText(null);
        } catch (Exception _) {
            return null;
        }
    }

    private static String buildSseUrl(String baseUrl, String ticket, Long lastSequence) {
        StringBuilder url = new StringBuilder(baseUrl);
        boolean hasQuery = false;
        if (ticket != null && !ticket.isBlank()) {
            url.append("?ticket=").append(URLEncoder.encode(ticket, StandardCharsets.UTF_8));
            hasQuery = true;
        }
        if (lastSequence != null && lastSequence > 0) {
            url.append(hasQuery ? "&" : "?").append("lastSequence=").append(lastSequence);
        }
        return url.toString();
    }

    private void connect(String url) {
        readerThread = Thread.ofVirtual().name("sse-listener").start(() -> {
            try {
                var client = HttpClient.newHttpClient();
                var request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Accept", "text/event-stream")
                        .GET()
                        .build();

                var response = client.send(request, HttpResponse.BodyHandlers.ofLines());

                String currentEvent = null;
                StringBuilder currentData = new StringBuilder();

                var lines = response.body().iterator();
                while (running.get() && lines.hasNext()) {
                    String line = lines.next();

                    if (line.startsWith("event:")) {
                        currentEvent = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        if (currentData.length() > 0) currentData.append("\n");
                        currentData.append(line.substring(5).trim());
                    } else if (line.isEmpty() && currentData.length() > 0) {
                        // End of SSE message
                        String data = currentData.toString();
                        String eventType = currentEvent != null ? currentEvent : "message";
                        JsonNode json = null;
                        try {
                            json = mapper.readTree(data);
                        } catch (Exception _) {
                            // Not JSON, store as-is
                        }
                        events.add(new SseEvent(eventType, data, json));

                        if ("connected".equals(eventType)) {
                            connected.set(true);
                        }

                        currentEvent = null;
                        currentData.setLength(0);
                    } else if (line.isEmpty()) {
                        currentEvent = null;
                        currentData.setLength(0);
                    }
                }
            } catch (Exception e) {
                if (running.get()) {
                    // Only log if we didn't intentionally close
                    System.err.println("SSE listener error: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Wait until connected event is received, or timeout.
     */
    public boolean awaitConnected(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!connected.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        return connected.get();
    }

    /**
     * Wait for an event of the given type to appear. Returns the first match after the call.
     * Checks both the SSE event-type header AND the JSON "type" field in the data payload.
     */
    public SseEvent awaitEvent(String eventType, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            for (var event : events) {
                // Check SSE event type header
                if (event.eventType().equals(eventType)) {
                    return event;
                }
                // Check JSON "type" field in data (Javalin SSE sends events as generic messages)
                if (event.json() != null
                        && event.json().has("type")
                        && eventType.equals(event.json().get("type").asText())) {
                    return event;
                }
            }
            Thread.sleep(50);
        }
        return null;
    }

    /**
     * Wait for an event whose JSON contains a field with a specific value.
     */
    public SseEvent awaitEvent(String eventType, String jsonField, String jsonValue, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            for (var event : events) {
                if (event.eventType().equals(eventType) && event.json() != null) {
                    var node = event.json().get(jsonField);
                    if (node != null && jsonValue.equals(node.asText())) {
                        return event;
                    }
                }
            }
            Thread.sleep(50);
        }
        return null;
    }

    public List<SseEvent> getAll() {
        return List.copyOf(events);
    }

    public List<SseEvent> getByType(String eventType) {
        return events.stream().filter(e -> e.eventType().equals(eventType)).toList();
    }

    public void clear() {
        events.clear();
    }

    public int size() {
        return events.size();
    }

    @Override
    public void close() {
        running.set(false);
        if (readerThread != null) {
            readerThread.interrupt();
        }
    }
}
