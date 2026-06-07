---
title: REST Routes
description: PlatformModule#onRegisterRoutes and the per-module wildcard dispatcher — how modules expose REST endpoints under /api/v1/modules/{moduleId}/.
---

Platform modules expose REST endpoints by overriding
[`PlatformModule#onRegisterRoutes`](/reference/module-sdk/platform-module/#onregisterroutes).
Routes are mounted under `/api/v1/modules/{moduleId}/`, share the
controller's auth and rate-limit middleware, and are dropped
automatically on uninstall or upgrade.

## What you'll learn

- The `RouteRegistrar` API for registering verbs.
- Typed-body handlers vs. raw handlers.
- The dispatcher contract — how the controller routes inbound requests
  back into your module.

## API surface

### `RouteRegistrar`

```java
package me.prexorjustin.prexorcloud.api.module.rest;

public interface RouteRegistrar {
    void get   (String path, RouteHandler handler);
    void post  (String path, RouteHandler handler);
    void put   (String path, RouteHandler handler);
    void delete(String path, RouteHandler handler);
    void patch (String path, RouteHandler handler);

    default <T> void post  (String path, Class<T> bodyType, TypedRouteHandler<T> handler);
    default <T> void put   (String path, Class<T> bodyType, TypedRouteHandler<T> handler);
    default <T> void patch (String path, Class<T> bodyType, TypedRouteHandler<T> handler);
    default <T> void delete(String path, Class<T> bodyType, TypedRouteHandler<T> handler);
}
```

`path` is in-module — `/sessions/join` resolves to
`POST /api/v1/modules/<id>/sessions/join`. Path parameters use
`{name}` syntax: `/players/{uuid}` → `req.pathParam("uuid")`.

### `RouteHandler`

```java
@FunctionalInterface
public interface RouteHandler {
    void handle(ApiRequest req, ApiResponse res) throws Exception;
}
```

`ApiRequest` exposes:

- `Optional<String> queryParam(String name)`
- `String pathParam(String name)`
- `<T> T bodyAs(Class<T> type)` — Jackson-deserialised, throws on
  parse failure.
- `Map<String, String> headers()`
- `String authenticatedUserId()` / `String[] roles()`

`ApiResponse` exposes:

- `ApiResponse status(int code)`
- `ApiResponse header(String name, String value)`
- `void json(Object body)`
- `void text(String body)`

### Typed-body handlers

The typed `post(path, bodyType, handler)` overload parses the request
body into `bodyType` via Jackson before calling the handler. Parse
failures short-circuit with a standard `400 {"error":"invalid json
body"}` envelope and your handler is never invoked.

```java
@FunctionalInterface
public interface TypedRouteHandler<T> {
    void handle(ApiRequest req, T body, ApiResponse res) throws Exception;
}
```

## Dispatcher contract

The controller mounts a single wildcard handler per HTTP method at
`/api/v1/modules/{moduleId}/<sub>`. On dispatch it walks the
recorded routes for `(moduleId, method, subpath)` and invokes the
first match. This means:

- Routes follow the module's install / upgrade / uninstall lifecycle
  — when the module is unloaded, its routes vanish without touching
  the controller's HTTP server.
- Adding a route at runtime is allowed; you can register additional
  routes after `onStart` if you need lazy registration.
- The controller's auth middleware runs **before** dispatch, so your
  handler is guaranteed an authenticated principal.

The dispatcher is implemented by `ModuleRouteRegistry` in
`cloud-cloud-modules:runtime`; see `StatsAggregatorInstallTest` in the test
harness for an end-to-end exercise.

## Example: stats-aggregator routes

```java
public final class StatsRoutes {

    private final StatsRepository repo;
    private final SessionAggregator aggregator;
    private final LeaderboardService leaderboard;

    public StatsRoutes(
            StatsRepository repo,
            SessionAggregator aggregator,
            LeaderboardService leaderboard) {
        this.repo = repo;
        this.aggregator = aggregator;
        this.leaderboard = leaderboard;
    }

    public void register(RouteRegistrar routes) {
        routes.get("/players/top", (req, res) -> {
            int limit = parseLimit(req.queryParam("limit").orElse(null));
            res.json(new TopPlayersResponse(leaderboard.topPlayers(limit)));
        });

        routes.get("/players/{uuid}", (req, res) -> {
            UUID playerId;
            try {
                playerId = UUID.fromString(req.pathParam("uuid"));
            } catch (IllegalArgumentException _) {
                res.status(400).json(Map.of("error", "invalid uuid"));
                return;
            }
            var stat = repo.playerStat(playerId);
            if (stat.isEmpty()) {
                res.status(404).json(Map.of("error", "player not found"));
                return;
            }
            res.json(stat.get());
        });

        // Typed body — JSON parse failures short-circuit with a 400.
        routes.post("/sessions/join", JoinRequest.class, (req, body, res) -> {
            if (body.playerId() == null || body.sessionId() == null) {
                res.status(400).json(Map.of("error", "missing fields"));
                return;
            }
            aggregator.onJoin(body);
            res.status(202).json(Map.of("ok", true));
        });
    }
}
```

The module hooks the registrar into the lifecycle:

```java
@Override
public void onRegisterRoutes(RouteRegistrar registrar) {
    routes.register(registrar);   // routes is the StatsRoutes instance
}
```

## Conventions

- **JSON only**: emit JSON for everything except `/metrics`-style
  endpoints (`text` is for plain-text).
- **Status codes**: `2xx` on success, `400` on validation, `404` on
  missing resource, `409` on conflict, `422` on semantic invalid,
  `500` on unexpected.
- **Authentication**: rely on the controller's middleware. Don't roll
  your own; if you need permission checks, read `req.roles()`.
- **Logging**: SLF4J at `INFO` for state-changing routes, `DEBUG` for
  reads.

## Next up

- [PlatformModule](/reference/module-sdk/platform-module/) —
  `onRegisterRoutes`.
- [Concepts → Module REST](/reference/module-sdk/rest-routes/) — how
  the dispatcher slots into Javalin.
