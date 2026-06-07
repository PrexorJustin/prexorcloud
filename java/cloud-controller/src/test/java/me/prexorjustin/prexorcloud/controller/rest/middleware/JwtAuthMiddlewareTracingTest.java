package me.prexorjustin.prexorcloud.controller.rest.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import me.prexorjustin.prexorcloud.controller.auth.JwtRevocationStore;
import me.prexorjustin.prexorcloud.controller.config.TelemetryConfig;
import me.prexorjustin.prexorcloud.controller.observability.telemetry.Telemetry;
import me.prexorjustin.prexorcloud.security.jwt.JwtManager;

import io.javalin.http.UnauthorizedResponse;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("JwtAuthMiddleware token-verify span")
class JwtAuthMiddlewareTracingTest {

    private static final String SECRET = Base64.getEncoder().encodeToString(new byte[32]);
    private static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("auth.outcome");

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private Telemetry telemetry;
    private JwtManager jwtManager;

    @BeforeEach
    void setup() {
        telemetry = Telemetry.fromExporter(new TelemetryConfig(true, "x", "svc", 1.0), exporter);
        jwtManager = new JwtManager(SECRET, 60);
    }

    @AfterEach
    void tearDown() {
        telemetry.close();
    }

    private JwtAuthMiddleware middleware(JwtRevocationStore revocationStore) {
        var middleware = new JwtAuthMiddleware(jwtManager, revocationStore);
        middleware.setTracer(telemetry.tracer());
        return middleware;
    }

    @Test
    @DisplayName("records a valid outcome and nests under the current server span")
    void validTokenNestsUnderServerSpan() {
        var middleware = middleware(null);
        String token = jwtManager.issue("admin", "ADMIN");

        // Simulate the HTTP server span being current (as it is during the before-filter chain).
        Span server = telemetry.tracer().spanBuilder("HTTP GET").startSpan();
        try (Scope ignored = server.makeCurrent()) {
            var claims = middleware.verifyToken(token);
            assertEquals("admin", claims.getSubject());
        } finally {
            server.end();
        }
        telemetry.flush();

        SpanData verify = span("auth.token-verify");
        SpanData http = span("HTTP GET");
        assertEquals("valid", verify.getAttributes().get(OUTCOME));
        assertEquals(http.getTraceId(), verify.getTraceId());
        assertEquals(http.getSpanId(), verify.getParentSpanId());
    }

    @Test
    @DisplayName("marks an invalid token as invalid and throws 401")
    void invalidToken() {
        var middleware = middleware(null);
        assertThrows(UnauthorizedResponse.class, () -> middleware.verifyToken("not-a-jwt"));
        telemetry.flush();
        assertEquals("invalid", span("auth.token-verify").getAttributes().get(OUTCOME));
    }

    @Test
    @DisplayName("marks a revoked token as revoked and throws 401")
    void revokedToken() {
        Set<String> revoked = new HashSet<>();
        var middleware = middleware(new JwtRevocationStore() {
            @Override
            public void revoke(String jti, Duration ttl) {
                revoked.add(jti);
            }

            @Override
            public boolean isRevoked(String jti) {
                return revoked.contains(jti);
            }
        });
        String token = jwtManager.issue("admin", "ADMIN");
        // The jti is embedded in the token; revoke every jti so the check trips.
        revoked.add(jwtManager.validate(token).orElseThrow().getId());

        assertThrows(UnauthorizedResponse.class, () -> middleware.verifyToken(token));
        telemetry.flush();
        assertEquals("revoked", span("auth.token-verify").getAttributes().get(OUTCOME));
    }

    private SpanData span(String name) {
        return exporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no span named " + name));
    }
}
