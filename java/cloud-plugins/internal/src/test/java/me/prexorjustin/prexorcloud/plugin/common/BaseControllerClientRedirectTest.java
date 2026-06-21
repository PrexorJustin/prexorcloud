package me.prexorjustin.prexorcloud.plugin.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Single-writer leader following on the plugin client. A follower answers with a 307 to the leader,
 * and the configured controller may be the one that died — so the client must follow the redirect
 * (re-attaching the bearer the JDK strips on a cross-host hop) and rotate to the next seed on a
 * connection failure. Without this, a leader failover strands the plugin behind 401s / dead sockets.
 */
@DisplayName("BaseControllerClient leader following")
class BaseControllerClientRedirectTest {

    private static final class TestClient extends BaseControllerClient {
        TestClient(String controllerUrl) {
            super(controllerUrl, "tok-123");
        }

        @Override
        protected String apiPrefix() {
            return "/api/plugin";
        }
    }

    /** A leader endpoint that records the bearer it saw and 401s if the token did not survive the hop. */
    private static HttpServer leaderServer(AtomicReference<String> seenAuth) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/plugin/instances", ex -> {
            seenAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            if (seenAuth.get() == null) {
                ex.sendResponseHeaders(401, -1);
                ex.close();
                return;
            }
            byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        return server;
    }

    private static String url(HttpServer s) {
        return "http://127.0.0.1:" + s.getAddress().getPort();
    }

    @Test
    @DisplayName("follows a follower's 307 to the leader and re-attaches the bearer")
    void followsRedirectReattachingAuth() throws Exception {
        AtomicReference<String> seenAuth = new AtomicReference<>();
        HttpServer leader = leaderServer(seenAuth);
        HttpServer follower = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        follower.createContext("/api/plugin/instances", ex -> {
            ex.getResponseHeaders().add("Location", url(leader) + "/api/plugin/instances");
            ex.sendResponseHeaders(307, -1);
            ex.close();
        });
        follower.start();
        try {
            var client = new TestClient(url(follower));
            assertNotNull(client.fetchInstances());
            assertEquals("Bearer tok-123", seenAuth.get(), "bearer must survive the cross-host redirect");
        } finally {
            leader.stop(0);
            follower.stop(0);
        }
    }

    @Test
    @DisplayName("rotates to the next seed when the first controller is unreachable")
    void rotatesToNextSeedWhenFirstIsDown() throws Exception {
        AtomicReference<String> seenAuth = new AtomicReference<>();
        HttpServer leader = leaderServer(seenAuth);
        int deadPort;
        try (ServerSocket s = new ServerSocket(0)) {
            deadPort = s.getLocalPort();
        } // socket closed → that port now refuses connections
        try {
            var client = new TestClient("http://127.0.0.1:" + deadPort + "," + url(leader));
            assertNotNull(client.fetchInstances());
            assertEquals("Bearer tok-123", seenAuth.get());
        } finally {
            leader.stop(0);
        }
    }
}
