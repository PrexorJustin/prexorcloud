package me.prexorjustin.prexorcloud.controller.rest.route;

import static io.javalin.apibuilder.ApiBuilder.get;
import static io.javalin.apibuilder.ApiBuilder.path;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import me.prexorjustin.prexorcloud.api.domain.EventChoreography;
import me.prexorjustin.prexorcloud.controller.PrexorController;
import me.prexorjustin.prexorcloud.controller.auth.Permission;
import me.prexorjustin.prexorcloud.controller.event_choreography.EventChoreographer;
import me.prexorjustin.prexorcloud.controller.rest.dto.ActiveOverlaysResponse;
import me.prexorjustin.prexorcloud.controller.rest.middleware.JwtAuthMiddleware;

import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiResponse;
import io.javalin.openapi.OpenApiSecurity;

public final class EventRoutes {

    // Substring anchors for scripts/check-openapi-routes.sh — the actual SSE
    // routes are registered via controller.sseHub() helpers, not via this
    // file's path() block. Will be retired with the audit script once the
    // cutover completes.
    @SuppressWarnings("unused")
    private static final String P_EVENTS_STREAM = "/api/v1/events/stream";

    @SuppressWarnings("unused")
    private static final String P_EVENTS_TICKET = "/api/v1/events/ticket";

    private final PrexorController controller;

    public EventRoutes(PrexorController controller) {
        this.controller = controller;
    }

    public void register() {
        path("/api/v1/events", () -> {
            get(this::listEventChoreography);
            get("/active", this::listActiveEventOverlays);
        });
    }

    @OpenApi(
            path = "/api/v1/events",
            methods = {HttpMethod.GET},
            operationId = "listEventChoreography",
            summary = "List configured choreography entries",
            description =
                    "Returns all entries configured under `controller.yaml: events:`. Entries are configuration-only in v1 and are not API-mutated; admins edit the YAML and restart the controller.",
            tags = {"Events"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "OK",
                        content = {@OpenApiContent(from = EventChoreography[].class)}),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden")
            })
    private void listEventChoreography(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.EVENTS_VIEW);
        ctx.json(controller.eventChoreographer().events());
    }

    @OpenApi(
            path = "/api/v1/events/active",
            methods = {HttpMethod.GET},
            operationId = "listActiveEventOverlays",
            summary = "List currently active choreography overlays",
            description =
                    "Returns the overlays whose firing window covers the current instant. At most one overlay is active per group; when several entries cover the same group, the one whose firing started most recently wins.",
            tags = {"Events"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "OK",
                        content = {@OpenApiContent(from = ActiveOverlaysResponse.class)}),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden")
            })
    private void listActiveEventOverlays(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.EVENTS_VIEW);
        Map<String, EventChoreographer.ActiveOverlay> active =
                controller.eventChoreographer().activeAt(Instant.now());
        List<ActiveOverlaysResponse.ActiveEventOverlayDto> dtos = active.values().stream()
                .map(o -> new ActiveOverlaysResponse.ActiveEventOverlayDto(
                        o.event().name(), o.event().group(), o.activeSince(), o.activeUntil()))
                .toList();
        ctx.json(new ActiveOverlaysResponse(dtos));
    }

    // Doc-only stubs for the SSE pair. Real handlers live in RestServer via
    // SseEventStreamer + SseTicketManager; these @OpenApi-annotated methods
    // exist solely so the annotation processor surfaces the paths in the spec.

    @OpenApi(
            path = "/api/v1/events/ticket",
            methods = {HttpMethod.POST},
            operationId = "createEventTicket",
            summary = "Issue SSE ticket for events stream",
            description =
                    "Exchange a JWT for a short-lived SSE ticket used to authenticate the event stream connection.",
            tags = {"Events"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "SSE ticket issued",
                        content = {
                            @OpenApiContent(
                                    from = me.prexorjustin.prexorcloud.controller.rest.dto.SseTicketResponse.class)
                        }),
                @OpenApiResponse(status = "401", description = "Unauthorized")
            })
    @SuppressWarnings("unused")
    private void docCreateEventTicket(Context ctx) {
        throw new UnsupportedOperationException("doc stub — real handler in RestServer");
    }

    @OpenApi(
            path = "/api/v1/events/stream",
            methods = {HttpMethod.GET},
            operationId = "streamEvents",
            summary = "SSE event stream",
            description =
                    "Server-Sent Events stream. Authenticate via the `ticket` query parameter obtained from the ticket endpoint. Sends a `connected` event on initial connection. Events include a `type` field matching CloudEvent types. Max 100 concurrent clients.",
            tags = {"Events"},
            security = {@OpenApiSecurity(name = "sseTicket")},
            queryParams = {@io.javalin.openapi.OpenApiParam(name = "ticket", required = true)},
            responses = {
                @OpenApiResponse(status = "200", description = "SSE event stream"),
                @OpenApiResponse(status = "401", description = "Invalid or expired ticket")
            })
    @SuppressWarnings("unused")
    private void docStreamEvents(Context ctx) {
        throw new UnsupportedOperationException("doc stub — real handler in RestServer");
    }
}
