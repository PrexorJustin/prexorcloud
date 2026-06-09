package me.prexorjustin.prexorcloud.daemon.health;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

import me.prexorjustin.prexorcloud.daemon.config.HealthConfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HealthServer implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(HealthServer.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer server;
    private final String nodeId;
    private final String controllerHost;
    private final int controllerPort;
    private final BooleanSupplier connectedSupplier;
    private final IntSupplier instanceCountSupplier;

    public HealthServer(
            HealthConfig config,
            String nodeId,
            String controllerHost,
            int controllerPort,
            BooleanSupplier connectedSupplier,
            IntSupplier instanceCountSupplier)
            throws IOException {
        this.nodeId = nodeId;
        this.controllerHost = controllerHost;
        this.controllerPort = controllerPort;
        this.connectedSupplier = connectedSupplier;
        this.instanceCountSupplier = instanceCountSupplier;
        this.server = HttpServer.create(new InetSocketAddress(config.bindAddress(), config.port()), 0);
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        this.server.createContext("/health", this::handleHealth);
        this.server.createContext("/ready", this::handleReady);
    }

    public void start() {
        server.start();
        logger.info("Daemon health endpoint listening on http://{}:{}", address(), port());
    }

    public String address() {
        return server.getAddress().getAddress().getHostAddress();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        writeJson(
                exchange,
                200,
                Map.of(
                        "status", "UP",
                        "nodeId", nodeId,
                        "connected", connectedSupplier.getAsBoolean(),
                        "instanceCount", instanceCountSupplier.getAsInt(),
                        "controller", Map.of("host", controllerHost, "grpcPort", controllerPort)));
    }

    private void handleReady(HttpExchange exchange) throws IOException {
        boolean connected = connectedSupplier.getAsBoolean();
        writeJson(
                exchange,
                connected ? 200 : 503,
                Map.of(
                        "status",
                        connected ? "READY" : "NOT_READY",
                        "nodeId",
                        nodeId,
                        "connected",
                        connected,
                        "instanceCount",
                        instanceCountSupplier.getAsInt()));
    }

    private static void writeJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        } finally {
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
