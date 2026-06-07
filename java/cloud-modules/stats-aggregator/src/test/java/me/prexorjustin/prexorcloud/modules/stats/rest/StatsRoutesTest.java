package me.prexorjustin.prexorcloud.modules.stats.rest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import me.prexorjustin.prexorcloud.api.module.rest.ApiRequest;
import me.prexorjustin.prexorcloud.api.module.rest.ApiResponse;
import me.prexorjustin.prexorcloud.api.module.rest.RouteHandler;
import me.prexorjustin.prexorcloud.api.module.rest.RouteRegistrar;
import me.prexorjustin.prexorcloud.modules.stats.config.StatsConfig;
import me.prexorjustin.prexorcloud.modules.stats.data.PlayerStat;
import me.prexorjustin.prexorcloud.modules.stats.data.SessionRecord;
import me.prexorjustin.prexorcloud.modules.stats.data.StatsRepository;
import me.prexorjustin.prexorcloud.modules.stats.metrics.PrometheusExporter;
import me.prexorjustin.prexorcloud.modules.stats.rest.dto.JoinRequest;
import me.prexorjustin.prexorcloud.modules.stats.rest.dto.LeaveRequest;
import me.prexorjustin.prexorcloud.modules.stats.rest.dto.PlayerDetailResponse;
import me.prexorjustin.prexorcloud.modules.stats.rest.dto.TopPlayersResponse;
import me.prexorjustin.prexorcloud.modules.stats.service.JourneyEnricher;
import me.prexorjustin.prexorcloud.modules.stats.service.LeaderboardService;
import me.prexorjustin.prexorcloud.modules.stats.service.SessionAggregator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("StatsRoutes")
class StatsRoutesTest {

    @Mock
    StatsRepository repo;

    @Mock
    SessionAggregator aggregator;

    @Mock
    LeaderboardService leaderboard;

    @Mock
    PrometheusExporter prometheus;

    StatsConfig config;
    FakeRegistrar registrar;
    Clock clock;

    @BeforeEach
    void setUp() {
        config = StatsConfig.defaults();
        registrar = new FakeRegistrar();
        clock = Clock.fixed(Instant.parse("2026-04-13T12:00:00Z"), ZoneOffset.UTC);
        new StatsRoutes(repo, aggregator, leaderboard, new JourneyEnricher(null), prometheus, config, clock)
                .register(registrar);
    }

    @Nested
    @DisplayName("GET /players/top")
    class GetTopPlayers {

        @Test
        @DisplayName("Returns leaderboard contents")
        void returnsLeaderboard() throws Exception {
            PlayerStat entry = new PlayerStat(UUID.randomUUID(), "alice", 1_000L, 1, null, null);
            when(leaderboard.topPlayers(config.leaderboardSize())).thenReturn(List.of(entry));

            FakeResponse res = invokeGet("/players/top", Map.of(), Map.of());

            assertNull(res.status);
            assertInstanceOf(TopPlayersResponse.class, res.body);
            assertEquals(1, ((TopPlayersResponse) res.body).count());
        }

        @Test
        @DisplayName("Returns 400 when limit is non-numeric")
        void invalidLimit() throws Exception {
            FakeResponse res = invokeGet("/players/top", Map.of(), Map.of("limit", "abc"));

            assertEquals(400, res.status);
            verify(leaderboard, never()).topPlayers(anyInt());
        }
    }

    @Nested
    @DisplayName("GET /players/{uuid}")
    class GetPlayerDetail {

        @Test
        @DisplayName("Returns 400 on non-uuid path param")
        void invalidUuid() throws Exception {
            FakeResponse res = invokeGet("/players/{uuid}", Map.of("uuid", "not-a-uuid"), Map.of());
            assertEquals(400, res.status);
        }

        @Test
        @DisplayName("Returns 404 when both stat and sessions are empty")
        void notFound() throws Exception {
            UUID id = UUID.randomUUID();
            when(repo.playerStat(id)).thenReturn(Optional.empty());
            when(repo.recentSessionsForPlayer(eq(id), eq(10))).thenReturn(List.of());

            FakeResponse res = invokeGet("/players/{uuid}", Map.of("uuid", id.toString()), Map.of());

            assertEquals(404, res.status);
        }

        @Test
        @DisplayName("Returns aggregated payload when data exists")
        void returnsPayload() throws Exception {
            UUID id = UUID.randomUUID();
            PlayerStat stat = new PlayerStat(id, "alice", 1_000L, 1, null, null);
            SessionRecord session =
                    new SessionRecord(id, "alice", UUID.randomUUID(), "lobby", "lobby-1", Instant.now(), null, 0L);
            when(repo.playerStat(id)).thenReturn(Optional.of(stat));
            when(repo.recentSessionsForPlayer(eq(id), eq(10))).thenReturn(List.of(session));

            FakeResponse res = invokeGet("/players/{uuid}", Map.of("uuid", id.toString()), Map.of());

            assertNull(res.status);
            assertInstanceOf(PlayerDetailResponse.class, res.body);
            PlayerDetailResponse payload = (PlayerDetailResponse) res.body;
            assertEquals(stat, payload.stat());
            assertEquals(List.of(session), payload.recentSessions());
            assertTrue(payload.recentJourney().isEmpty());
        }
    }

    @Nested
    @DisplayName("POST /sessions/join")
    class PostJoin {

        @Test
        @DisplayName("Returns 400 when body is invalid")
        void invalidBody() throws Exception {
            FakeResponse res = invokePostThrowing("/sessions/join", new RuntimeException("bad json"));
            assertEquals(400, res.status);
        }

        @Test
        @DisplayName("Returns 400 when required field missing")
        void missingField() throws Exception {
            JoinRequest body = new JoinRequest(null, "alice", UUID.randomUUID(), "lobby", "lobby-1", Instant.now());
            FakeResponse res = invokePost("/sessions/join", body);
            assertEquals(400, res.status);
        }

        @Test
        @DisplayName("Delegates to aggregator on valid input")
        void delegates() throws Exception {
            UUID playerId = UUID.randomUUID();
            UUID sessionId = UUID.randomUUID();
            Instant now = Instant.parse("2026-04-13T12:00:00Z");
            JoinRequest body = new JoinRequest(playerId, "alice", sessionId, "lobby", "lobby-1", now);
            FakeResponse res = invokePost("/sessions/join", body);

            assertEquals(202, res.status);
            verify(aggregator).onJoin(playerId, "alice", sessionId, "lobby", "lobby-1", now);
        }
    }

    @Nested
    @DisplayName("POST /sessions/leave")
    class PostLeave {

        @Test
        @DisplayName("Returns 400 when durationMs is negative")
        void negativeDuration() throws Exception {
            LeaveRequest body = new LeaveRequest(UUID.randomUUID(), Instant.now(), -1L);
            FakeResponse res = invokePost("/sessions/leave", body);
            assertEquals(400, res.status);
        }

        @Test
        @DisplayName("Returns 404 when aggregator reports NOT_FOUND")
        void notFound() throws Exception {
            UUID sessionId = UUID.randomUUID();
            Instant quitAt = Instant.now();
            LeaveRequest body = new LeaveRequest(sessionId, quitAt, null);
            when(aggregator.onLeave(sessionId, quitAt, null)).thenReturn(SessionAggregator.CloseOutcome.NOT_FOUND);

            FakeResponse res = invokePost("/sessions/leave", body);

            assertEquals(404, res.status);
        }

        @Test
        @DisplayName("Returns 202 on close")
        void closes() throws Exception {
            UUID sessionId = UUID.randomUUID();
            Instant quitAt = Instant.now();
            LeaveRequest body = new LeaveRequest(sessionId, quitAt, 1_000L);
            when(aggregator.onLeave(sessionId, quitAt, 1_000L)).thenReturn(SessionAggregator.CloseOutcome.CLOSED);

            FakeResponse res = invokePost("/sessions/leave", body);

            assertEquals(202, res.status);
        }
    }

    @Test
    @DisplayName("GET /metrics returns Prometheus content type when enabled")
    void metricsEnabled() throws Exception {
        when(prometheus.render()).thenReturn("# HELP foo bar\nfoo 1\n");

        FakeRequest req = new FakeRequest("GET", "/metrics", Map.of(), Map.of(), null, null);
        FakeResponse res = new FakeResponse();
        registrar.gets.get("/metrics").handle(req, res);

        assertNull(res.status);
        assertEquals("# HELP foo bar\nfoo 1\n", res.body);
        assertEquals("text/plain; version=0.0.4; charset=utf-8", res.headers.get("Content-Type"));
    }

    @Test
    @DisplayName("POST /aggregates/rebuild returns rebuild counts")
    void rebuildResponse() throws Exception {
        when(aggregator.rebuild(any(Instant.class))).thenReturn(new StatsRepository.RebuildResult(3, 2, 10));

        FakeResponse res = invokePost("/aggregates/rebuild", null);

        assertNull(res.status);
        assertNotNull(res.body);
    }

    // ── doubles ─────────────────────────────────────────────────────────────

    private FakeResponse invokeGet(String path, Map<String, String> pathParams, Map<String, String> queryParams)
            throws Exception {
        RouteHandler handler = registrar.gets.get(path);
        assertNotNull(handler, "no GET handler at " + path);
        FakeRequest req = new FakeRequest("GET", path, pathParams, queryParams, null, null);
        FakeResponse res = new FakeResponse();
        handler.handle(req, res);
        return res;
    }

    private FakeResponse invokePost(String path, Object body) throws Exception {
        RouteHandler handler = registrar.posts.get(path);
        assertNotNull(handler, "no POST handler at " + path);
        FakeRequest req = new FakeRequest("POST", path, Map.of(), Map.of(), body, null);
        FakeResponse res = new FakeResponse();
        handler.handle(req, res);
        return res;
    }

    private FakeResponse invokePostThrowing(String path, RuntimeException toThrow) throws Exception {
        RouteHandler handler = registrar.posts.get(path);
        assertNotNull(handler, "no POST handler at " + path);
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
        Map<String, String> headers = new HashMap<>();

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
            headers.put(name, value);
            return this;
        }
    }
}
