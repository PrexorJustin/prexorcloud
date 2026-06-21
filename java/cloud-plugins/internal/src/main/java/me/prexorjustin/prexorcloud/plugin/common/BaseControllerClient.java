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
 *
 * <p>Single-writer leader following: only the leader serves the API, and a
 * follower answers with a {@code 307} pointing at the leader. The configured
 * controller address is a comma-separated <em>seed list</em>; the client tries
 * the base it last reached the leader on, rotates to the next seed on a
 * connection failure (the configured controller may be the one that died), and
 * follows a {@code 307}/{@code 308} <em>manually</em> — re-attaching the bearer,
 * which the JDK client strips on a cross-host redirect — then caches the leader
 * base for subsequent calls. Without this a leader failover strands the plugin:
 * the JDK auto-follow drops the token, so the new leader answers 401.
 */
public abstract class BaseControllerClient {

    private static final Logger logger = LoggerFactory.getLogger(BaseControllerClient.class);
    private static final String SEQUENCE_HEADER = "X-Prexor-Sequence";
    private static final int MAX_REDIRECT_HOPS = 3;

    protected static final ObjectMapper objectMapper = ObjectMappers.standard();

    /** First configured seed; retained for subclasses/tests that reference it. */
    protected final String controllerUrl;

    private final List<String> controllerSeeds;
    private final AtomicReference<String> leaderBase;
    private final AtomicReference<String> pluginTokenRef;
    private final AtomicLong requestSequence = new AtomicLong();
    private final Object refreshLock = new Object();
    protected final HttpClient httpClient;

    protected BaseControllerClient(String controllerUrl, String pluginToken) {
        this.controllerSeeds = parseSeeds(controllerUrl);
        this.controllerUrl = controllerSeeds.get(0);
        this.leaderBase = new AtomicReference<>(controllerSeeds.get(0));
        this.pluginTokenRef = new AtomicReference<>(pluginToken);
        // Follow redirects ourselves so the bearer survives the cross-host hop to the leader.
        this.httpClient = HttpClients.defaults()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** Builds a request against a given controller base URL (re-callable per seed / per redirect hop). */
    @FunctionalInterface
    protected interface RequestFactory {
        HttpRequest build(String baseUrl);
    }

    private static List<String> parseSeeds(String configured) {
        var seeds = new java.util.ArrayList<String>();
        if (configured != null) {
            for (String part : configured.split(",")) {
                String s = part.trim();
                while (s.endsWith("/")) {
                    s = s.substring(0, s.length() - 1);
                }
                if (!s.isEmpty() && !seeds.contains(s)) {
                    seeds.add(s);
                }
            }
        }
        if (seeds.isEmpty()) {
            seeds.add(configured == null ? "" : configured.trim());
        }
        return List.copyOf(seeds);
    }

    private static String baseOf(String url) {
        try {
            URI u = URI.create(url);
            if (u.getScheme() == null || u.getHost() == null) {
                return null;
            }
            int port = u.getPort();
            return u.getScheme() + "://" + u.getHost() + (port > 0 ? ":" + port : "");
        } catch (RuntimeException e) {
            return null;
        }
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
            HttpResponse<String> response = sendWithRefresh(base -> HttpRequest.newBuilder()
                    .uri(URI.create(base + apiPrefix() + "/events/ticket"))
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
            String query = "/api/v1/events/stream?ticket=" + URLEncoder.encode(ticket, StandardCharsets.UTF_8)
                    + (lastSequence > 0 ? "&lastSequence=" + lastSequence : "");
            RequestFactory factory = b -> {
                var rb = HttpRequest.newBuilder()
                        .uri(URI.create(b + query))
                        .header("Accept", "text/event-stream")
                        .GET();
                if (lastSequence > 0) {
                    rb.header("Last-Event-ID", Long.toString(lastSequence));
                }
                return rb.build();
            };
            // The ticket is leader-issued, so start at the cached leader; follow a 307 manually in case
            // leadership moved between the ticket and the stream open.
            String base = leaderBase.get();
            HttpResponse<InputStream> response =
                    httpClient.send(factory.build(base), HttpResponse.BodyHandlers.ofInputStream());
            int hops = 0;
            while (isRedirect(response.statusCode()) && hops++ < MAX_REDIRECT_HOPS) {
                String location = response.headers().firstValue("Location").orElse(null);
                String redirectBase = location == null ? null : baseOf(location);
                if (redirectBase == null) break;
                base = redirectBase;
                response = httpClient.send(factory.build(base), HttpResponse.BodyHandlers.ofInputStream());
            }
            leaderBase.set(base);
            return response;
        } catch (Exception e) {
            throw new RuntimeException("Failed to open SSE stream: " + e.getMessage(), e);
        }
    }

    // --- HTTP helpers ---

    protected <T> T get(String path, TypeReference<T> typeRef) {
        try {
            HttpResponse<String> response = sendWithRefresh(base -> HttpRequest.newBuilder()
                    .uri(URI.create(base + path))
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
            HttpResponse<String> response = sendWithRefresh(base -> HttpRequest.newBuilder()
                    .uri(URI.create(base + path))
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

    /**
     * Send a request to the controller, trying the cached leader base first and rotating to the
     * other seeds on a connection failure (the configured controller may be the one that died), and
     * manually following a leader {@code 307}/{@code 308} — the JDK client strips the bearer on the
     * cross-host hop. The base that ultimately served the request is cached as the leader.
     */
    private HttpResponse<String> send(RequestFactory factory) throws Exception {
        var order = new java.util.ArrayList<String>();
        order.add(leaderBase.get());
        for (String s : controllerSeeds) {
            if (!order.contains(s)) order.add(s);
        }
        java.io.IOException lastError = null;
        for (String seed : order) {
            try {
                String base = seed;
                HttpResponse<String> response =
                        httpClient.send(factory.build(base), HttpResponse.BodyHandlers.ofString());
                int hops = 0;
                while (isRedirect(response.statusCode()) && hops++ < MAX_REDIRECT_HOPS) {
                    String location = response.headers().firstValue("Location").orElse(null);
                    String redirectBase = location == null ? null : baseOf(location);
                    if (redirectBase == null) break;
                    base = redirectBase;
                    response = httpClient.send(factory.build(base), HttpResponse.BodyHandlers.ofString());
                }
                leaderBase.set(base);
                return response;
            } catch (java.io.IOException e) {
                lastError = e; // this controller is unreachable; try the next seed
            }
        }
        throw lastError != null ? lastError : new java.io.IOException("no controller reachable");
    }

    private static boolean isRedirect(int status) {
        return status == 307 || status == 308;
    }

    private HttpResponse<String> sendWithRefresh(RequestFactory factory) throws Exception {
        HttpResponse<String> response = send(factory);
        if (response.statusCode() == 401 && refreshPluginToken()) {
            response = send(factory);
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
                HttpResponse<String> refreshResponse = send(base -> HttpRequest.newBuilder()
                        .uri(URI.create(base + apiPrefix() + "/auth/refresh"))
                        .header("Authorization", "Bearer " + pluginTokenRef.get())
                        .header(SEQUENCE_HEADER, nextSequence())
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .timeout(Duration.ofSeconds(10))
                        .build());
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
