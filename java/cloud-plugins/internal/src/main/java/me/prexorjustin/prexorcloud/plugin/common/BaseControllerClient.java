package me.prexorjustin.prexorcloud.plugin.common;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import me.prexorjustin.prexorcloud.api.domain.NetworkComposition;
import me.prexorjustin.prexorcloud.common.io.HttpClients;
import me.prexorjustin.prexorcloud.common.io.ObjectMappers;
import me.prexorjustin.prexorcloud.plugin.common.dto.GroupDto;
import me.prexorjustin.prexorcloud.plugin.common.dto.InstanceDto;
import me.prexorjustin.prexorcloud.plugin.common.dto.PlayerDto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract HTTP client for communicating with the controller. Subclasses
 * provide the API prefix (/api/proxy or /api/plugin) and platform-specific
 * endpoints.
 *
 * Workload tokens are short-lived sliding-window bearers. On a 401 response
 * the client exchanges the current token for a fresh one via the auth/refresh
 * endpoint under its prefix and retries the original request once. Refresh is
 * single-flight across threads so a burst of in-flight requests hitting the
 * expiry window rotates the token only once.
 */
public abstract class BaseControllerClient {

    private static final Logger logger = LoggerFactory.getLogger(BaseControllerClient.class);
    private static final String SEQUENCE_HEADER = "X-Prexor-Sequence";

    protected static final ObjectMapper objectMapper = ObjectMappers.standard();

    protected final String controllerUrl;
    private final AtomicReference<String> pluginTokenRef;
    private final AtomicLong requestSequence = new AtomicLong();
    private final Object refreshLock = new Object();
    protected final HttpClient httpClient;

    protected BaseControllerClient(String controllerUrl, String pluginToken) {
        this.controllerUrl = controllerUrl;
        this.pluginTokenRef = new AtomicReference<>(pluginToken);
        this.httpClient = HttpClients.defaultClient();
    }

    /** Returns the API prefix, e.g. "/api/proxy" or "/api/plugin". */
    protected abstract String apiPrefix();

    protected final String pluginToken() {
        return pluginTokenRef.get();
    }

    // --- Shared endpoints ---

    public List<InstanceDto> fetchInstances() {
        return get(apiPrefix() + "/instances", new TypeReference<>() {});
    }

    public List<GroupDto> fetchGroups() {
        return get(apiPrefix() + "/groups", new TypeReference<>() {});
    }

    public List<NetworkComposition> fetchNetworks() {
        return get(apiPrefix() + "/networks", new TypeReference<>() {});
    }

    public List<PlayerDto> fetchPlayers() {
        return get(apiPrefix() + "/players", new TypeReference<>() {});
    }

    /** Fire a custom event on the controller's event bus. */
    public void fireEvent(String type, Map<String, Object> data) {
        try {
            String body = objectMapper.writeValueAsString(Map.of("type", type, "data", data != null ? data : Map.of()));
            postAsync(apiPrefix() + "/events", body);
        } catch (Exception e) {
            logger.warn("Failed to serialize custom event '{}': {}", type, e.getMessage());
        }
    }

    String issueEventStreamTicket() {
        try {
            HttpResponse<String> response = sendWithRefresh(() -> HttpRequest.newBuilder()
                    .uri(URI.create(controllerUrl + apiPrefix() + "/events/ticket"))
                    .header("Authorization", "Bearer " + pluginTokenRef.get())
                    .header(SEQUENCE_HEADER, nextSequence())
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(10))
                    .build());
            if (response.statusCode() != 200) {
                throw new RuntimeException("HTTP " + response.statusCode());
            }
            Map<String, String> body =
                    objectMapper.readValue(response.body(), new TypeReference<Map<String, String>>() {});
            String ticket = body.get("ticket");
            if (ticket == null || ticket.isBlank()) {
                throw new RuntimeException("empty SSE ticket");
            }
            return ticket;
        } catch (Exception e) {
            throw new RuntimeException("Failed to obtain SSE ticket: " + e.getMessage(), e);
        }
    }

    HttpResponse<InputStream> openEventStream(long lastSequence) {
        try {
            String ticket = issueEventStreamTicket();
            StringBuilder query = new StringBuilder("/api/v1/events/stream?ticket=")
                    .append(URLEncoder.encode(ticket, StandardCharsets.UTF_8));
            if (lastSequence > 0) {
                query.append("&lastSequence=").append(lastSequence);
            }
            var requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(controllerUrl + query))
                    .header("Accept", "text/event-stream")
                    .GET();
            if (lastSequence > 0) {
                requestBuilder.header("Last-Event-ID", Long.toString(lastSequence));
            }
            return httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (Exception e) {
            throw new RuntimeException("Failed to open SSE stream: " + e.getMessage(), e);
        }
    }

    // --- HTTP helpers ---

    protected <T> T get(String path, TypeReference<T> typeRef) {
        try {
            HttpResponse<String> response = sendWithRefresh(() -> HttpRequest.newBuilder()
                    .uri(URI.create(controllerUrl + path))
                    .header("Authorization", "Bearer " + pluginTokenRef.get())
                    .header("traceparent", W3CTraceparent.random())
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build());
            if (response.statusCode() != 200) {
                throw new RuntimeException("HTTP " + response.statusCode() + " from " + path);
            }
            return objectMapper.readValue(response.body(), typeRef);
        } catch (Exception e) {
            throw new RuntimeException("Failed to GET " + path + ": " + e.getMessage(), e);
        }
    }

    protected void postAsync(String path, String body) {
        try {
            HttpResponse<String> response = sendWithRefresh(() -> HttpRequest.newBuilder()
                    .uri(URI.create(controllerUrl + path))
                    .header("Authorization", "Bearer " + pluginTokenRef.get())
                    .header(SEQUENCE_HEADER, nextSequence())
                    .header("Content-Type", "application/json")
                    .header("traceparent", W3CTraceparent.random())
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(10))
                    .build());
            if (response.statusCode() >= 400) {
                logger.warn("POST {} failed: HTTP {}", path, response.statusCode());
            }
        } catch (Exception e) {
            logger.warn("POST {} failed: {}", path, e.getMessage());
        }
    }

    private HttpResponse<String> sendWithRefresh(java.util.function.Supplier<HttpRequest> requestBuilder)
            throws Exception {
        HttpResponse<String> response = httpClient.send(requestBuilder.get(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401 && refreshPluginToken()) {
            response = httpClient.send(requestBuilder.get(), HttpResponse.BodyHandlers.ofString());
        }
        return response;
    }

    /**
     * Exchange the current token for a fresh one. Single-flight: concurrent
     * callers wait for the first rotation and then see the new token via
     * {@link #pluginTokenRef}. Returns true on success.
     */
    private boolean refreshPluginToken() {
        String tokenBeforeLock = pluginTokenRef.get();
        synchronized (refreshLock) {
            if (pluginTokenRef.get() != tokenBeforeLock) {
                // Another thread already rotated; our retry will pick it up.
                return true;
            }
            try {
                HttpRequest refreshRequest = HttpRequest.newBuilder()
                        .uri(URI.create(controllerUrl + apiPrefix() + "/auth/refresh"))
                        .header("Authorization", "Bearer " + pluginTokenRef.get())
                        .header(SEQUENCE_HEADER, nextSequence())
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .timeout(Duration.ofSeconds(10))
                        .build();
                HttpResponse<String> refreshResponse =
                        httpClient.send(refreshRequest, HttpResponse.BodyHandlers.ofString());
                if (refreshResponse.statusCode() != 200) {
                    logger.warn("Plugin token refresh failed: HTTP {}", refreshResponse.statusCode());
                    return false;
                }
                Map<String, String> body =
                        objectMapper.readValue(refreshResponse.body(), new TypeReference<Map<String, String>>() {});
                String newToken = body.get("token");
                if (newToken == null || newToken.isBlank()) {
                    logger.warn("Plugin token refresh returned empty token");
                    return false;
                }
                pluginTokenRef.set(newToken);
                return true;
            } catch (Exception e) {
                logger.warn("Plugin token refresh failed: {}", e.getMessage());
                return false;
            }
        }
    }

    private String nextSequence() {
        return Long.toString(requestSequence.incrementAndGet());
    }
}
