package me.prexorjustin.prexorcloud.modules.example.rest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import me.prexorjustin.prexorcloud.api.module.rest.ApiRequest;
import me.prexorjustin.prexorcloud.api.module.rest.ApiResponse;
import me.prexorjustin.prexorcloud.api.module.rest.RouteHandler;
import me.prexorjustin.prexorcloud.api.module.rest.RouteRegistrar;
import me.prexorjustin.prexorcloud.modules.example.config.Config;
import me.prexorjustin.prexorcloud.modules.example.data.PlaytimeRepository;
import me.prexorjustin.prexorcloud.modules.example.data.Session;
import me.prexorjustin.prexorcloud.modules.example.data.TopEntry;
import me.prexorjustin.prexorcloud.modules.example.rest.dto.PlayerResponse;
import me.prexorjustin.prexorcloud.modules.example.rest.dto.SessionEndRequest;
import me.prexorjustin.prexorcloud.modules.example.rest.dto.SessionStartRequest;
import me.prexorjustin.prexorcloud.modules.example.rest.dto.TopResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests for {@link PlaytimeRoutes}. We roll a tiny in-memory {@link
 * RouteRegistrar} + {@link ApiRequest} / {@link ApiResponse} double instead of
 * mocking them, so each test can invoke a handler by path and assert against
 * the captured response. PlaytimeRepository is mocked at the boundary.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PlaytimeRoutes")
class PlaytimeRoutesTest {

    @Mock
    PlaytimeRepository repo;

    Config config;
    FakeRegistrar registrar;

    @BeforeEach
    void setUp() {
        config = Config.defaults();
        registrar = new FakeRegistrar();
        new PlaytimeRoutes(repo, config).register(registrar);
    }

    @Nested
    @DisplayName("GET /top")
    class GetTop {

        @Test
        @DisplayName("Returns repository contents with count")
        void returnsContents() throws Exception {
            TopEntry entry = new TopEntry(UUID.randomUUID(), 1_000L, 1, Instant.now());
            when(repo.top(config.topSize())).thenReturn(List.of(entry));

            FakeResponse res = invokeGet("/top", Map.of(), Map.of());

            assertNull(res.status);
            assertInstanceOf(TopResponse.class, res.body);
            TopResponse top = (TopResponse) res.body;
            assertEquals(1, top.count());
            assertEquals(List.of(entry), top.items());
        }

        @Test
        @DisplayName("Clamps oversized limit to topSize * 4")
        void clampsLimit() throws Exception {
            when(repo.top(anyInt())).thenReturn(List.of());

            invokeGet("/top", Map.of(), Map.of("limit", "99999"));

            int expectedCap = Math.max(1, config.topSize()) * 4;
            verify(repo).top(expectedCap);
        }

        @Test
        @DisplayName("Returns 400 when limit is not a number")
        void badLimit() throws Exception {
            FakeResponse res = invokeGet("/top", Map.of(), Map.of("limit", "abc"));

            assertEquals(400, res.status);
            verify(repo, never()).top(anyInt());
        }
    }

    @Nested
    @DisplayName("GET /player/{uuid}")
    class GetPlayer {

        @Test
        @DisplayName("Returns 400 when the path param is not a UUID")
        void invalidUuid() throws Exception {
            FakeResponse res = invokeGet("/player/{uuid}", Map.of("uuid", "not-a-uuid"), Map.of());

            assertEquals(400, res.status);
            verify(repo, never()).totalFor(any(UUID.class));
        }

        @Test
        @DisplayName("Returns 404 when both totals and recent sessions are empty")
        void notFound() throws Exception {
            UUID playerId = UUID.randomUUID();
            when(repo.totalFor(playerId)).thenReturn(Optional.empty());
            when(repo.recentSessions(eq(playerId), eq(10))).thenReturn(List.of());

            FakeResponse res = invokeGet("/player/{uuid}", Map.of("uuid", playerId.toString()), Map.of());

            assertEquals(404, res.status);
        }

        @Test
        @DisplayName("Returns aggregated payload when data is present")
        void returnsPayload() throws Exception {
            UUID playerId = UUID.randomUUID();
            Instant lastSeen = Instant.parse("2026-04-13T12:00:00Z");
            TopEntry total = new TopEntry(playerId, 12_000L, 4, lastSeen);
            Session session = new Session(playerId, UUID.randomUUID(), lastSeen, null, 0L, "lobby-1");
            when(repo.totalFor(playerId)).thenReturn(Optional.of(total));
            when(repo.recentSessions(eq(playerId), eq(10))).thenReturn(List.of(session));

            FakeResponse res = invokeGet("/player/{uuid}", Map.of("uuid", playerId.toString()), Map.of());

            assertNull(res.status);
            assertInstanceOf(PlayerResponse.class, res.body);
            PlayerResponse payload = (PlayerResponse) res.body;
            assertEquals(playerId, payload.playerId());
            assertEquals(12_000L, payload.totalMs());
            assertEquals(4, payload.sessionCount());
            assertEquals(List.of(session), payload.recentSessions());
        }
    }

    @Nested
    @DisplayName("POST /session/start")
    class PostSessionStart {

        @Test
        @DisplayName("Returns 400 when the body cannot be parsed")
        void invalidJson() throws Exception {
            FakeResponse res = invokePostThrowing("/session/start", new RuntimeException("bad json"));

            assertEquals(400, res.status);
        }

        @Test
        @DisplayName("Returns 400 when a required field is missing")
        void missingField() throws Exception {
            SessionStartRequest body = new SessionStartRequest(null, UUID.randomUUID(), "lobby-1", Instant.now());

            FakeResponse res = invokePost("/session/start", body);

            assertEquals(400, res.status);
            verify(repo, never()).openSession(any(Session.class));
        }

        @Test
        @DisplayName("Inserts the session when the body is valid")
        void insertsSession() throws Exception {
            UUID playerId = UUID.randomUUID();
            UUID sessionId = UUID.randomUUID();
            Instant joinAt = Instant.parse("2026-04-13T12:00:00Z");
            SessionStartRequest body = new SessionStartRequest(playerId, sessionId, "lobby-1", joinAt);

            FakeResponse res = invokePost("/session/start", body);

            assertEquals(202, res.status);
            verify(repo).openSession(any(Session.class));
        }
    }

    @Nested
    @DisplayName("POST /session/end")
    class PostSessionEnd {

        @Test
        @DisplayName("Returns 400 when durationMs is negative")
        void negativeDuration() throws Exception {
            SessionEndRequest body = new SessionEndRequest(UUID.randomUUID(), Instant.now(), -1L);

            FakeResponse res = invokePost("/session/end", body);

            assertEquals(400, res.status);
            verify(repo, never()).closeSession(any(UUID.class), any(Instant.class), anyLong());
        }

        @Test
        @DisplayName("Closes the session when the body is valid")
        void closesSession() throws Exception {
            UUID sessionId = UUID.randomUUID();
            Instant quitAt = Instant.parse("2026-04-13T13:00:00Z");
            SessionEndRequest body = new SessionEndRequest(sessionId, quitAt, 3_600_000L);

            FakeResponse res = invokePost("/session/end", body);

            assertEquals(202, res.status);
            verify(repo).closeSession(sessionId, quitAt, 3_600_000L);
        }
    }

    // ── Test doubles ────────────────────────────────────────────────────────

    private FakeResponse invokeGet(String path, Map<String, String> pathParams, Map<String, String> queryParams)
            throws Exception {
        RouteHandler handler = registrar.gets.get(path);
        assertNotNull(handler, "no GET handler registered at " + path);
        FakeRequest req = new FakeRequest("GET", path, pathParams, queryParams, null, null);
        FakeResponse res = new FakeResponse();
        handler.handle(req, res);
        return res;
    }

    private FakeResponse invokePost(String path, Object body) throws Exception {
        RouteHandler handler = registrar.posts.get(path);
        assertNotNull(handler, "no POST handler registered at " + path);
        FakeRequest req = new FakeRequest("POST", path, Map.of(), Map.of(), body, null);
        FakeResponse res = new FakeResponse();
        handler.handle(req, res);
        return res;
    }

    private FakeResponse invokePostThrowing(String path, RuntimeException toThrow) throws Exception {
        RouteHandler handler = registrar.posts.get(path);
        assertNotNull(handler, "no POST handler registered at " + path);
        FakeRequest req = new FakeRequest("POST", path, Map.of(), Map.of(), null, toThrow);
        FakeResponse res = new FakeResponse();
        handler.handle(req, res);
        return res;
    }

    static final class FakeRegistrar implements RouteRegistrar {

        final Map<String, RouteHandler> gets = new HashMap<>();
        final Map<String, RouteHandler> posts = new HashMap<>();

        @Override
        public void get(String path, RouteHandler handler) {
            gets.put(path, handler);
        }

        @Override
        public void post(String path, RouteHandler handler) {
            posts.put(path, handler);
        }

        @Override
        public void put(String path, RouteHandler handler) {}

        @Override
        public void delete(String path, RouteHandler handler) {}

        @Override
        public void patch(String path, RouteHandler handler) {}
    }

    static final class FakeRequest implements ApiRequest {

        private final String method;
        private final String path;
        private final Map<String, String> pathParams;
        private final Map<String, String> queryParams;
        private final Object body;
        private final RuntimeException bodyFailure;

        FakeRequest(
                String method,
                String path,
                Map<String, String> pathParams,
                Map<String, String> queryParams,
                Object body,
                RuntimeException bodyFailure) {
            this.method = method;
            this.path = path;
            this.pathParams = pathParams;
            this.queryParams = queryParams;
            this.body = body;
            this.bodyFailure = bodyFailure;
        }

        @Override
        public String method() {
            return method;
        }

        @Override
        public String path() {
            return path;
        }

        @Override
        public Map<String, String> pathParams() {
            return pathParams;
        }

        @Override
        public Map<String, String> queryParams() {
            return queryParams;
        }

        @Override
        public Map<String, String> headers() {
            return Map.of();
        }

        @Override
        public String body() {
            return body == null ? "" : body.toString();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T bodyAs(Class<T> type) {
            if (bodyFailure != null) throw bodyFailure;
            return (T) body;
        }
    }

    static final class FakeResponse implements ApiResponse {

        Integer status;
        Object body;

        @Override
        public ApiResponse status(int code) {
            this.status = code;
            return this;
        }

        @Override
        public void json(Object b) {
            this.body = b;
        }

        @Override
        public void text(String b) {
            this.body = b;
        }

        @Override
        public ApiResponse header(String name, String value) {
            return this;
        }
    }
}
