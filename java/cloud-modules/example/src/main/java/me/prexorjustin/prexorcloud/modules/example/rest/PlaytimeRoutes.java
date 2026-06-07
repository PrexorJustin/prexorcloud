package me.prexorjustin.prexorcloud.modules.example.rest;

import java.util.UUID;

import me.prexorjustin.prexorcloud.api.module.rest.RouteRegistrar;
import me.prexorjustin.prexorcloud.modules.example.config.Config;
import me.prexorjustin.prexorcloud.modules.example.data.PlaytimeRepository;
import me.prexorjustin.prexorcloud.modules.example.data.Session;
import me.prexorjustin.prexorcloud.modules.example.data.TopEntry;
import me.prexorjustin.prexorcloud.modules.example.rest.dto.PlayerResponse;
import me.prexorjustin.prexorcloud.modules.example.rest.dto.SessionEndRequest;
import me.prexorjustin.prexorcloud.modules.example.rest.dto.SessionStartRequest;
import me.prexorjustin.prexorcloud.modules.example.rest.dto.TopResponse;

/**
 * HTTP routes exposed by the example-playtime module.
 *
 * <p>STEP 8 — Every module route is mounted under {@code
 * /api/v1/modules/<module-name>/} automatically by the controller's route
 * registrar, so only pass the intra-module suffix here (e.g. {@code "/top"}).
 * Platform-module route registration is pending controller API support; this
 * class is kept as the reusable route surface for that integration point.
 */
public final class PlaytimeRoutes {

    private final PlaytimeRepository repo;
    private final Config config;

    public PlaytimeRoutes(PlaytimeRepository repo, Config config) {
        this.repo = repo;
        this.config = config;
    }

    public void register(RouteRegistrar routes) {
        // ── STEP 8d — Input validation pattern ─────────────────────────────
        //
        // Module routes run inside the controller's HTTP pipeline, which wraps
        // any uncaught exception as a 500. That is fine for genuine server
        // faults but wrong for bad client input, so every handler below owns
        // its own parse/validate gate and returns a structured 400 (or 404)
        // before touching the repository. The shape {"error": "..."} is
        // intentionally minimal — pick whichever error envelope your project
        // standardises on and mirror it across every module.

        // STEP 8a — Read routes: top leaderboard + single-player detail.
        routes.get("/top", (req, res) -> {
            int limit;
            try {
                limit = req.queryParam("limit").map(Integer::parseInt).orElse(config.topSize());
            } catch (NumberFormatException _) {
                res.status(400).json(java.util.Map.of("error", "invalid limit"));
                return;
            }
            // Clamp: /top is an unauthenticated-friendly read, so bound the
            // fan-out even if a caller passes limit=99999.
            limit = Math.max(1, Math.min(limit, Math.max(1, config.topSize()) * 4));
            var top = repo.top(limit);
            res.json(new TopResponse(top.size(), top));
        });

        routes.get("/player/{uuid}", (req, res) -> {
            UUID playerId;
            try {
                playerId = UUID.fromString(req.pathParam("uuid"));
            } catch (IllegalArgumentException _) {
                res.status(400).json(java.util.Map.of("error", "invalid uuid"));
                return;
            }
            var total = repo.totalFor(playerId);
            var recent = repo.recentSessions(playerId, 10);
            if (total.isEmpty() && recent.isEmpty()) {
                res.status(404).json(java.util.Map.of("error", "player not found"));
                return;
            }
            res.json(new PlayerResponse(
                    playerId,
                    total.map(TopEntry::totalMs).orElse(0L),
                    total.map(TopEntry::sessionCount).orElse(0),
                    total.map(TopEntry::lastSeen).orElse(null),
                    recent));
        });

        // STEP 8b — Write routes: alternative ingress path for plugins that can't
        // (or don't want to) use the event bus. Only used when
        // config.reportVia == "rest"; the event bus is the live path otherwise.
        // The typed overloads (Class<T>, TypedRouteHandler<T>) parse the JSON
        // body and emit the standard 400 envelope on parse failure — handlers
        // only own field-level validation.
        routes.post("/session/start", SessionStartRequest.class, (req, body, res) -> {
            if (body == null || body.playerId() == null || body.sessionId() == null || body.joinAt() == null) {
                res.status(400).json(java.util.Map.of("error", "missing required field: playerId, sessionId, joinAt"));
                return;
            }
            repo.openSession(
                    new Session(body.playerId(), body.sessionId(), body.joinAt(), null, 0L, body.serverName()));
            res.status(202).json(java.util.Map.of("ok", true));
        });

        routes.post("/session/end", SessionEndRequest.class, (req, body, res) -> {
            if (body == null || body.sessionId() == null || body.quitAt() == null) {
                res.status(400).json(java.util.Map.of("error", "missing required field: sessionId, quitAt"));
                return;
            }
            if (body.durationMs() < 0) {
                res.status(400).json(java.util.Map.of("error", "durationMs must be >= 0"));
                return;
            }
            repo.closeSession(body.sessionId(), body.quitAt(), body.durationMs());
            res.status(202).json(java.util.Map.of("ok", true));
        });

        // STEP 8c — Inspect route: returns the config the module is currently
        // running with. Handy for debugging "is my new YAML reload live?".
        routes.get("/config", (req, res) -> res.json(config));
    }
}
