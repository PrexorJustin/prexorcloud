---
title: REST routes
description: PlatformModule#onRegisterRoutes, the RouteRegistrar/ApiRequest/ApiResponse API, and the per-module wildcard dispatcher that mounts module routes under /api/v1/modules/{moduleId}/.
---

Platform modules expose REST endpoints by overriding
[`PlatformModule#onRegisterRoutes`](/reference/module-sdk/platform-module/#onregisterroutes).
Routes mount under `/api/v1/modules/{moduleId}/`, share the controller's
auth and rate-limit middleware, and are dropped automatically on
uninstall, upgrade, and reload. The mounted module id is the module's
manifest `id`.

This page is the API contract: every method on `RouteRegistrar`,
`ApiRequest`, and `ApiResponse`, the path-template matcher, the
dispatcher's exception-to-status mapping, and the lifecycle that
registers and clears routes.

Source:

- `java/cloud-api/.../api/module/rest/` — `RouteRegistrar`, `RouteHandler`,
  `TypedRouteHandler`, `ApiRequest`, `ApiResponse`.
- `java/cloud-modules/runtime/.../ModuleRouteRegistry.java` — the route
  table and matcher.
- `java/cloud-controller/.../rest/RestServer.java` — the wildcard
  dispatcher and the Javalin-backed `ApiRequest`/`ApiResponse` adapters.
- `java/cloud-modules/example/.../rest/PlaytimeRoutes.java` — the worked
  example.

## Registering routes

Override `onRegisterRoutes(RouteRegistrar)` on your
`PlatformModule`. It is called once after `onLoad` and before `onStart`.

```java
@Override
public void onRegisterRoutes(RouteRegistrar registrar) {
    new PlaytimeRoutes(repository, Config.defaults()).register(registrar);
}
```

The `RouteRegistrar` is a short-lived view tied to your module id. Do not
hold a reference to it past `onRegisterRoutes` — registration happens
inside this call, and the controller re-supplies a fresh registrar on
every upgrade and reload. Routes registered after `onStart` are not
recorded; the hook is the only registration point.

## `RouteRegistrar`

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

### Verbs

| Method | Signature | Records |
|---|---|---|
| `get`    | `get(String path, RouteHandler handler)`    | `GET`    |
| `post`   | `post(String path, RouteHandler handler)`   | `POST`   |
| `put`    | `put(String path, RouteHandler handler)`    | `PUT`    |
| `delete` | `delete(String path, RouteHandler handler)` | `DELETE` |
| `patch`  | `patch(String path, RouteHandler handler)`  | `PATCH`  |

There is no `head` or `options` overload. `OPTIONS` preflight is handled
by the controller's CORS middleware, not by module routes.

### `path`

`path` is the intra-module suffix. `/sessions/join` on module
`stats-aggregator` resolves to
`POST /api/v1/modules/stats-aggregator/sessions/join`.

Path rules, enforced by `ModuleRouteRegistry`:

- A leading `/` is optional. `"top"` and `"/top"` register the same
  route; the template is normalized to a leading `/`.
- The template must not be blank — `IllegalArgumentException("route template must not be blank")`.
- The template must not contain `?` or `#` —
  `IllegalArgumentException("route template must not contain '?' or '#': <template>")`.
  Query strings are parsed by the dispatcher, not declared in the
  template.

### Path parameters

Path parameters use `{name}` syntax and bind one path segment:

```java
routes.get("/player/{uuid}", (req, res) -> {
    String raw = req.pathParam("uuid"); // captured segment
    // ...
});
```

The matcher (`ModuleRouteRegistry#matchTemplate`) splits both the
template and the request path on `/`, requires an identical segment
count, matches literal segments exactly, and captures `{name}` segments
into the path-param map. A segment is treated as a parameter only when it
both starts with `{` and ends with `}`. Consequences:

- `/player/{uuid}` matches `/player/abc` but not `/player/abc/extra`
  (segment count differs) and not `/player` (segment count differs).
- There is no wildcard or catch-all segment and no regex constraint on a
  parameter. Validate the captured value in your handler (see the
  worked example below).
- Routes are matched in registration order; the first
  `(method, template)` whose segments match wins. Register more specific
  paths before broader ones if two templates could both match a request.

### Typed-body overloads

The typed overloads parse the request body into `bodyType` via
`ApiRequest#bodyAs(Class)` before calling your handler. On parse failure
they short-circuit with a `400` envelope and the handler is never
invoked:

```json
{"error": "invalid json body", "details": "<parser message>"}
```

`details` is included only when the underlying parser supplies a
non-blank message. Both `bodyType` and `handler` must be non-null —
either being null throws `IllegalArgumentException` at registration time.

The four typed overloads (`post`, `put`, `patch`, `delete`) are `default`
methods that wrap the body parse and delegate to the corresponding
untyped verb. There is no typed `get` overload; `GET` requests carry no
parsed body.

```java
routes.post("/session/start", SessionStartRequest.class, (req, body, res) -> {
    if (body == null || body.playerId() == null) {
        res.status(400).json(Map.of("error", "missing required field: playerId"));
        return;
    }
    repo.openSession(body);
    res.status(202).json(Map.of("ok", true));
});
```

The body is parsed with a Jackson `ObjectMapper` that has
`JavaTimeModule` registered, so `java.time` types
(`Instant`, `LocalDateTime`, ...) deserialize without extra
configuration.

## `RouteHandler`

```java
@FunctionalInterface
public interface RouteHandler {
    void handle(ApiRequest request, ApiResponse response) throws Exception;
}
```

`handle` may throw. The dispatcher converts thrown exceptions to HTTP
status codes (see [Exception mapping](#exception-mapping)).

## `TypedRouteHandler<T>`

```java
@FunctionalInterface
public interface TypedRouteHandler<T> {
    void handle(ApiRequest request, T body, ApiResponse response) throws Exception;
}
```

Used only through the typed `RouteRegistrar` overloads. `body` is the
deserialized request body; the handler runs only when parsing succeeded.

## `ApiRequest`

```java
package me.prexorjustin.prexorcloud.api.module.rest;

public interface ApiRequest {

    String method();
    String path();
    Map<String, String> pathParams();
    Map<String, String> queryParams();
    Map<String, String> headers();
    String body();
    <T> T bodyAs(Class<T> type);

    default String           pathParam(String name);   // throws if missing
    default Optional<String> queryParam(String name);
    default Optional<String> header(String name);
    default Optional<String> userId();                 // X-User-Id header
}
```

| Member | Returns | Notes |
|---|---|---|
| `method()` | `String` | The HTTP method, e.g. `"GET"`. |
| `path()` | `String` | The full request path including `/api/v1/modules/...`. |
| `pathParams()` | `Map<String,String>` | Captured `{name}` segments. Immutable copy. |
| `queryParams()` | `Map<String,String>` | Query string flattened to first value per key. |
| `headers()` | `Map<String,String>` | Request headers. |
| `body()` | `String` | Raw request body. |
| `bodyAs(Class<T>)` | `T` | Jackson-deserialized body. Throws `IllegalArgumentException("invalid request body: ...")` on parse failure. |
| `pathParam(String)` | `String` | Looks up `pathParams()`; throws `IllegalArgumentException("Missing path param: <name>")` if absent. |
| `queryParam(String)` | `Optional<String>` | Empty when the key is absent. |
| `header(String)` | `Optional<String>` | Empty when the header is absent. |
| `userId()` | `Optional<String>` | Reads the `X-User-Id` request header. |

Notes that bite:

- `queryParams()` keeps the **first** value per key. A repeated query
  parameter (`?id=a&id=b`) returns only `a`. There is no multi-value
  accessor on this interface.
- `userId()` returns the client-supplied `X-User-Id` header — it is **not**
  the authenticated principal. The controller's JWT middleware records
  the authenticated subject as a request attribute the module API does
  not surface; do not treat `userId()` as proof of identity. Authorization
  is the controller's middleware (see
  [Authentication and rate limiting](#authentication-and-rate-limiting)).
- `bodyAs` and the raw `bodyAs(Class)` parse with the dispatcher's
  `ObjectMapper` (with `JavaTimeModule`). When called directly it throws
  `IllegalArgumentException`, which the dispatcher maps to `422`. When
  called through a typed overload the wrapper catches that and emits the
  `400 {"error":"invalid json body"}` envelope instead.

## `ApiResponse`

```java
package me.prexorjustin.prexorcloud.api.module.rest;

public interface ApiResponse {
    ApiResponse status(int code);
    void        json(Object body);
    void        text(String body);
    ApiResponse header(String name, String value);
}
```

| Member | Returns | Notes |
|---|---|---|
| `status(int)` | `ApiResponse` | Sets the status code. Chainable. |
| `header(String, String)` | `ApiResponse` | Sets a response header. Chainable. |
| `json(Object)` | `void` | Serializes `body` to JSON via Javalin's mapper and sets `Content-Type: application/json`. |
| `text(String)` | `void` | Writes `body` as the raw response result. |

`status` and `header` return `this` for chaining; `json` and `text` are
terminal. The default status when you never call `status` is `200`. Call
`status` before `json`/`text`:

```java
res.status(202).header("X-Aggregated", "true").json(Map.of("ok", true));
```

## The wildcard dispatcher

The controller does not register one Javalin route per module endpoint.
`RestServer#registerModuleApiDispatcher` mounts exactly **one wildcard
handler per HTTP method**:

```java
get   ("/api/v1/modules/{moduleId}/<sub>", ctx -> dispatchModuleRoute(ctx, "GET"));
post  ("/api/v1/modules/{moduleId}/<sub>", ctx -> dispatchModuleRoute(ctx, "POST"));
put   ("/api/v1/modules/{moduleId}/<sub>", ctx -> dispatchModuleRoute(ctx, "PUT"));
delete("/api/v1/modules/{moduleId}/<sub>", ctx -> dispatchModuleRoute(ctx, "DELETE"));
patch ("/api/v1/modules/{moduleId}/<sub>", ctx -> dispatchModuleRoute(ctx, "PATCH"));
```

On each request the dispatcher:

1. Reads `moduleId` from the path. If it is a reserved segment (`platform`),
   it returns `404 NOT_FOUND` without consulting any module. A module
   that picks a reserved id is rejected at install time.
2. Reads the `<sub>` remainder and calls
   `ModuleRouteRegistry#resolve(moduleId, method, subpath)`.
3. On no match, returns `404 {"error":{"code":"NOT_FOUND","message":"Module route not found","status":404}}`.
4. On match, wraps the Javalin `Context` in the `ApiRequest`/`ApiResponse`
   adapters (injecting the captured path params) and invokes the handler.

The dispatcher delegates to `ModuleRouteRegistry`, which holds a
per-module list of `RegisteredRoute(httpMethod, template, handler)`. This
indirection exists because Javalin does not gracefully unmount routes
after startup: keeping all module routes behind one wildcard per method
lets a module's routes follow its install/upgrade/uninstall/reload
lifecycle without ever touching Javalin's route table at runtime.

### Exception mapping

The handler runs inside the dispatcher's try/catch. Thrown exceptions map
to status codes with the controller's standard error envelope
`{"error":{"code","message","status"}}`:

| Thrown from handler | Status | `code` | `message` |
|---|---|---|---|
| `IllegalArgumentException` | `422` | `VALIDATION_ERROR` | the exception message |
| `io.javalin.http.NotFoundException` | `404` | `NOT_FOUND` | the exception message |
| any other `Exception` | `500` | `INTERNAL_ERROR` | `An internal error occurred` (the real message is logged, not returned) |

This is the safety net for genuine faults. It is **not** the path for
client-input errors: a `400`/`404`/`409` you want a caller to see should
be set explicitly with `res.status(...).json(...)` and returned, exactly
as the example handlers do. Leaning on the exception net for validation
turns a bad `limit=abc` into a `422` (because `Integer.parseInt` throws
`NumberFormatException`, a subclass of `IllegalArgumentException`) rather
than the `400` you intend.

## Route lifecycle

`ModuleLifecycleManager` clears and re-registers routes around the module
lifecycle. The route table is keyed by module id, so a module's routes
live and die with the module:

| Transition | Route action |
|---|---|
| Install (`onLoad` → `onRegisterRoutes`) | clear, then register |
| Upgrade (`onStop` → `onUnload` → `onLoad` → `onUpgrade` → `onRegisterRoutes`) | clear the old routes, then register the new |
| Reload (`onReload` → `onRegisterRoutes`) | clear, then register |
| Uninstall / unload (`onUnload`) | clear |
| Any of the above throwing | clear |

Routes are cleared on upgrade and reload because handlers are classes
loaded by the outgoing module's classloader; they cannot be carried
across a jar swap. After clearing, the new entrypoint re-registers from
its own `onRegisterRoutes`. There is no rollback of routes if a lifecycle
hook fails — a failed transition leaves the module with no routes.

## Authentication and rate limiting

Module routes sit under `/api/v1/*`, so the controller's `before`
middleware chain runs ahead of the dispatcher, in order: CORS, subnet
guard, request-id, IP rate limit, JWT auth, per-user rate limit. By the
time your handler runs the request has passed JWT authentication and both
rate-limit tiers.

Do not implement your own authentication. If you need authorization, the
module REST API does not expose roles or the authenticated subject —
gate sensitive operations at the capability boundary or behind a separate
controller mechanism, not inside the route handler off `userId()`.

## Worked example: example-playtime routes

From `java/cloud-modules/example/.../rest/PlaytimeRoutes.java`. It shows
read routes with a path param, query-param parsing with an explicit
`400`, and typed-body write routes.

```java
public final class PlaytimeRoutes {

    private final PlaytimeRepository repo;
    private final Config config;

    public PlaytimeRoutes(PlaytimeRepository repo, Config config) {
        this.repo = repo;
        this.config = config;
    }

    public void register(RouteRegistrar routes) {

        // Read: leaderboard with a clamped, validated query param.
        routes.get("/top", (req, res) -> {
            int limit;
            try {
                limit = req.queryParam("limit").map(Integer::parseInt).orElse(config.topSize());
            } catch (NumberFormatException _) {
                res.status(400).json(Map.of("error", "invalid limit"));
                return;
            }
            limit = Math.max(1, Math.min(limit, Math.max(1, config.topSize()) * 4));
            var top = repo.top(limit);
            res.json(new TopResponse(top.size(), top));
        });

        // Read: path param, validated, with an explicit 404.
        routes.get("/player/{uuid}", (req, res) -> {
            UUID playerId;
            try {
                playerId = UUID.fromString(req.pathParam("uuid"));
            } catch (IllegalArgumentException _) {
                res.status(400).json(Map.of("error", "invalid uuid"));
                return;
            }
            var total = repo.totalFor(playerId);
            if (total.isEmpty()) {
                res.status(404).json(Map.of("error", "player not found"));
                return;
            }
            res.json(/* ... */);
        });

        // Write: typed body. JSON parse failures short-circuit with a 400
        // before this handler runs; the handler owns field-level validation.
        routes.post("/session/start", SessionStartRequest.class, (req, body, res) -> {
            if (body == null || body.playerId() == null || body.sessionId() == null || body.joinAt() == null) {
                res.status(400).json(Map.of("error", "missing required field: playerId, sessionId, joinAt"));
                return;
            }
            repo.openSession(new Session(body.playerId(), body.sessionId(), body.joinAt(), null, 0L, body.serverName()));
            res.status(202).json(Map.of("ok", true));
        });
    }
}
```

Wired into the lifecycle:

```java
@Override
public void onRegisterRoutes(RouteRegistrar registrar) {
    new PlaytimeRoutes(repository, Config.defaults()).register(registrar);
}
```

Calling the routes (module installed as `example-playtime`):

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/modules/example-playtime/top?limit=5
```

```json
{"size": 2, "entries": [/* ... */]}
```

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"playerId":"…","sessionId":"…","joinAt":"2026-06-07T10:00:00Z"}' \
  http://localhost:8080/api/v1/modules/example-playtime/session/start
```

```json
{"ok": true}
```

A malformed body returns the typed-overload envelope:

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d 'not json' \
  http://localhost:8080/api/v1/modules/example-playtime/session/start
```

```json
{"error": "invalid json body", "details": "…"}
```

## Conventions

- **JSON by default.** Use `json` for everything; reserve `text` for
  plain-text endpoints (`/metrics`-style).
- **Status codes.** `2xx` on success, `400` for input validation, `404`
  for a missing resource, `409` for conflict, `422` for semantically
  invalid input. Set these explicitly — do not rely on the exception net
  except for genuine `500`-class faults.
- **Validate every captured value.** The matcher does not constrain path
  params or query params; parse and bound them in the handler.
- **Stateless handlers.** A handler may be invoked concurrently. The
  registry's `resolve` is synchronized, but your handler body is not —
  guard shared mutable state yourself.

## Related

- [PlatformModule](/reference/module-sdk/platform-module/) —
  `onRegisterRoutes` and the rest of the lifecycle.
- [Module context](/reference/module-sdk/module-context/) — storage,
  events, and the primitives handlers usually call into.
