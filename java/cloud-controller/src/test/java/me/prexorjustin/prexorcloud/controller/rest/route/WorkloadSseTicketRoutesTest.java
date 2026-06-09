package me.prexorjustin.prexorcloud.controller.rest.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;

import me.prexorjustin.prexorcloud.controller.PrexorController;
import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.rest.middleware.WorkloadAuthFilter;
import me.prexorjustin.prexorcloud.controller.rest.sse.SseTicketManager;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.controller.state.InstanceInfo;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Workload SSE ticket routes")
final class WorkloadSseTicketRoutesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newHttpClient();

    private Javalin app;
    private String baseUrl;
    private SseTicketManager ticketManager;
    private String serverToken;
    private String proxyToken;

    @BeforeEach
    void setUp() {
        ClusterState clusterState = new ClusterState(new EventBus());
        clusterState.addInstance(
                new InstanceInfo("server-1", "lobby", "node-a", InstanceState.RUNNING, 25565, 0, 0, Instant.now()));
        clusterState.addInstance(
                new InstanceInfo("proxy-1", "proxy", "node-a", InstanceState.RUNNING, 25577, 0, 0, Instant.now()));
        serverToken = clusterState.issuePluginToken("server-1");
        proxyToken = clusterState.issuePluginToken("proxy-1");

        PrexorController controller = mock(PrexorController.class);
        when(controller.clusterState()).thenReturn(clusterState);
        ticketManager = new SseTicketManager();

        var workloadAuthFilter = new WorkloadAuthFilter(controller);
        app = Javalin.create(config -> config.routes.apiBuilder(() -> {
            io.javalin.apibuilder.ApiBuilder.before("/api/proxy/*", workloadAuthFilter);
            io.javalin.apibuilder.ApiBuilder.before("/api/plugin/*", workloadAuthFilter);
            new PluginRoutes(controller, ticketManager).register();
            new ProxyRoutes(controller, ticketManager).register();
        }));
        app.start(0);
        baseUrl = "http://127.0.0.1:" + app.port();
    }

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    @Test
    void pluginTicketEndpointIssuesSingleUseTicketForValidWorkloadToken() throws Exception {
        HttpResponse<String> response = post("/api/plugin/events/ticket", serverToken);

        assertEquals(200, response.statusCode());
        String ticket = readTicket(response.body());
        assertNotNull(ticket);
        assertEquals("workload:server-1", ticketManager.validate(ticket));
        assertEquals(null, ticketManager.validate(ticket));
    }

    @Test
    void proxyTicketEndpointIssuesSingleUseTicketForValidWorkloadToken() throws Exception {
        HttpResponse<String> response = post("/api/proxy/events/ticket", proxyToken);

        assertEquals(200, response.statusCode());
        String ticket = readTicket(response.body());
        assertNotNull(ticket);
        assertEquals("workload:proxy-1", ticketManager.validate(ticket));
    }

    @Test
    void ticketEndpointRejectsInvalidWorkloadToken() throws Exception {
        HttpResponse<String> response = post("/api/plugin/events/ticket", "not-a-valid-token");

        assertEquals(401, response.statusCode());
        assertTrue(response.body().contains("\"code\":\"UNAUTHORIZED\""));
    }

    @Test
    void proxyGetIsRejectedWithoutAuthHeader() throws Exception {
        // Why: with WorkloadAuthFilter registered as a before(...) filter, /api/proxy/* and
        // /api/plugin/* are structurally authenticated — a handler that forgets to call the
        // auth helper is impossible to reach unauthenticated.
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/proxy/instances"))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(401, response.statusCode());
        assertTrue(response.body().contains("\"code\":\"UNAUTHORIZED\""));
    }

    @Test
    void pluginGetIsRejectedWithoutAuthHeader() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/plugin/instances"))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(401, response.statusCode());
        assertTrue(response.body().contains("\"code\":\"UNAUTHORIZED\""));
    }

    private HttpResponse<String> post(String path, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String readTicket(String body) throws Exception {
        Map<String, String> payload = MAPPER.readValue(body, new TypeReference<Map<String, String>>() {});
        return payload.get("ticket");
    }
}
