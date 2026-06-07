package me.prexorjustin.prexorcloud.daemon.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import me.prexorjustin.prexorcloud.daemon.config.HealthConfig;

import org.junit.jupiter.api.Test;

class HealthServerTest {

    @Test
    void servesHealthAndReadiness() throws Exception {
        int port = findOpenPort();
        try (HealthServer server = new HealthServer(
                new HealthConfig(true, "127.0.0.1", port), "node-1", "127.0.0.1", 9090, () -> true, () -> 3)) {
            server.start();

            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<String> health = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode());
            assertTrue(health.body().contains("\"status\":\"UP\""));
            assertTrue(health.body().contains("\"instanceCount\":3"));

            HttpResponse<String> ready = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/ready"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ready.statusCode());
            assertTrue(ready.body().contains("\"status\":\"READY\""));
        }
    }

    @Test
    void returnsServiceUnavailableWhenNotReady() throws Exception {
        int port = findOpenPort();
        try (HealthServer server = new HealthServer(
                new HealthConfig(true, "127.0.0.1", port), "node-1", "127.0.0.1", 9090, () -> false, () -> 0)) {
            server.start();

            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> ready = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/ready"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(503, ready.statusCode());
            assertTrue(ready.body().contains("\"status\":\"NOT_READY\""));
        }
    }

    private static int findOpenPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
