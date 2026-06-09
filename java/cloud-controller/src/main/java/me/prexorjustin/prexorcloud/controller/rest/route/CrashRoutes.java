package me.prexorjustin.prexorcloud.controller.rest.route;

import static io.javalin.apibuilder.ApiBuilder.*;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.requireFound;

import java.time.Duration;
import java.time.Instant;

import me.prexorjustin.prexorcloud.controller.PrexorController;
import me.prexorjustin.prexorcloud.controller.auth.Permission;
import me.prexorjustin.prexorcloud.controller.crash.CrashTrendBucketer;
import me.prexorjustin.prexorcloud.controller.rest.ApiResponse;
import me.prexorjustin.prexorcloud.controller.rest.dto.CrashDetailDto;
import me.prexorjustin.prexorcloud.controller.rest.dto.CrashDtoMapper;
import me.prexorjustin.prexorcloud.controller.rest.dto.CrashSummaryPage;
import me.prexorjustin.prexorcloud.controller.rest.dto.CrashTrendDto;
import me.prexorjustin.prexorcloud.controller.rest.dto.ShareRequestDto;
import me.prexorjustin.prexorcloud.controller.rest.dto.ShareResultDto;
import me.prexorjustin.prexorcloud.controller.rest.middleware.JwtAuthMiddleware;
import me.prexorjustin.prexorcloud.controller.share.ShareContext;
import me.prexorjustin.prexorcloud.controller.share.ShareRequest;

import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiResponse;
import io.javalin.openapi.OpenApiSecurity;

public final class CrashRoutes {

    private final PrexorController controller;

    public CrashRoutes(PrexorController controller) {
        this.controller = controller;
    }

    public void register() {
        path("/api/v1/crashes", () -> {
            get(this::listCrashes);
            get("/trends", this::getCrashTrends);
            get("/{id}", this::getCrash);
            post("/{id}/share", this::shareCrash);
        });
    }

    @OpenApi(
            path = "/api/v1/crashes",
            methods = {HttpMethod.GET},
            operationId = "listCrashes",
            summary = "List crash reports",
            description =
                    "Returns crash report summaries with optional filtering by group and node. Pagination prefers `page` and `pageSize`; `limit` remains as a deprecated compatibility alias for `pageSize`.",
            tags = {"Crashes"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            queryParams = {
                @OpenApiParam(name = "page", type = Integer.class),
                @OpenApiParam(name = "pageSize", type = Integer.class),
                @OpenApiParam(name = "group", description = "Filter by group name"),
                @OpenApiParam(name = "node", description = "Filter by node name"),
                @OpenApiParam(
                        name = "limit",
                        type = Integer.class,
                        deprecated = true,
                        description = "Legacy alias for `pageSize`.")
            },
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "Crash report list",
                        content = {@OpenApiContent(from = CrashSummaryPage.class)}),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden")
            })
    private void listCrashes(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.CRASHES_VIEW);
        String group = ctx.queryParam("group");
        String node = ctx.queryParam("node");
        CrashPageRequest request = resolveCrashPageRequest(
                ctx.queryParam("page") != null
                        ? ctx.queryParamAsClass("page", Integer.class).getOrDefault(1)
                        : null,
                ctx.queryParam("pageSize") != null
                        ? ctx.queryParamAsClass("pageSize", Integer.class).getOrDefault(100)
                        : null,
                ctx.queryParam("limit") != null
                        ? ctx.queryParamAsClass("limit", Integer.class).getOrDefault(100)
                        : null,
                500);

        var crashes = controller.stateStore().getCrashes(group, node, request.limit(), request.offset()).stream()
                .map(CrashDtoMapper::toSummaryDto)
                .toList();
        ApiResponse.paginated(
                ctx, crashes, controller.stateStore().countCrashes(group, node), request.page(), request.pageSize());
    }

    @OpenApi(
            path = "/api/v1/crashes/trends",
            methods = {HttpMethod.GET},
            operationId = "getCrashTrends",
            summary = "Crash trend buckets",
            description =
                    "Returns crash counts bucketed over the requested window for sparkline rendering. Default window is 24h with 24 hourly buckets. Window is clamped to the crash retention TTL (30 days).",
            tags = {"Crashes"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            queryParams = {
                @OpenApiParam(
                        name = "window",
                        description = "Window duration with suffix `s|m|h|d` (e.g. `24h`, `7d`)."),
                @OpenApiParam(
                        name = "buckets",
                        type = Integer.class,
                        description = "Number of buckets to divide the window into (1-288)."),
                @OpenApiParam(name = "group"),
                @OpenApiParam(name = "node")
            },
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "Crash trend buckets",
                        content = {@OpenApiContent(from = CrashTrendDto.class)}),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden")
            })
    private void getCrashTrends(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.CRASHES_VIEW);
        String group = ctx.queryParam("group");
        String node = ctx.queryParam("node");
        Duration window = parseWindow(ctx.queryParam("window"), Duration.ofHours(24));
        int buckets = clamp(
                ctx.queryParam("buckets") != null
                        ? ctx.queryParamAsClass("buckets", Integer.class).getOrDefault(24)
                        : 24,
                1,
                288);
        Instant now = Instant.now();
        var points = controller.stateStore().getCrashTrend(group, node, now.minus(window));
        var trend = CrashTrendBucketer.bucket(points, now, window, buckets);
        ctx.json(CrashDtoMapper.toTrendDto(trend));
    }

    @OpenApi(
            path = "/api/v1/crashes/{id}",
            methods = {HttpMethod.GET},
            operationId = "getCrash",
            summary = "Get crash detail",
            description = "Returns a single crash report including the log tail.",
            tags = {"Crashes"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "id", required = true)},
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "Crash detail with log tail",
                        content = {@OpenApiContent(from = CrashDetailDto.class)}),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden"),
                @OpenApiResponse(status = "404", description = "Crash report not found")
            })
    private void getCrash(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.CRASHES_VIEW);
        String id = ctx.pathParam("id");
        var crash = requireFound(controller.stateStore().getCrash(id), "Crash", id);
        ctx.json(CrashDtoMapper.toDetailDto(crash));
    }

    @OpenApi(
            path = "/api/v1/crashes/{id}/share",
            methods = {HttpMethod.POST},
            operationId = "shareCrash",
            summary = "Share a crash report via pste",
            description =
                    "Uploads the crash detail (including redacted log tail) to the configured paste service and returns the resulting link.",
            tags = {"Crashes"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "id", required = true)},
            requestBody =
                    @io.javalin.openapi.OpenApiRequestBody(content = {@OpenApiContent(from = ShareRequestDto.class)}),
            responses = {
                @OpenApiResponse(
                        status = "201",
                        description = "Paste created",
                        content = {@OpenApiContent(from = ShareResultDto.class)}),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden"),
                @OpenApiResponse(status = "404", description = "Crash not found"),
                @OpenApiResponse(status = "409", description = "Sharing not configured"),
                @OpenApiResponse(status = "502", description = "Paste service unreachable")
            })
    private void shareCrash(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.CRASHES_VIEW);
        JwtAuthMiddleware.requirePermission(ctx, Permission.SHARE_INVOKE);
        String id = ctx.pathParam("id");
        var crash = requireFound(controller.stateStore().getCrash(id), "Crash", id);
        ShareRequestDto dto = parseShareRequest(ctx);
        String username = ctx.attribute("username");
        var result =
                controller.shareService().shareCrash(crash, toServiceRequest(dto), ShareContext.of(username, ctx.ip()));
        ctx.status(201).json(ShareResultDto.from(result));
    }

    private static ShareRequestDto parseShareRequest(Context ctx) {
        String body = ctx.body();
        if (body == null || body.isBlank()) return new ShareRequestDto(null, null, null);
        return ctx.bodyAsClass(ShareRequestDto.class);
    }

    static ShareRequest toServiceRequest(ShareRequestDto dto) {
        if (dto == null) return ShareRequest.empty();
        return new ShareRequest(dto.expiry(), dto.isPrivate(), dto.burnAfterRead());
    }

    static Duration parseWindow(String raw, Duration fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        var matcher = java.util.regex.Pattern.compile("^(\\d+)([smhd])$")
                .matcher(raw.trim().toLowerCase());
        if (!matcher.matches()) return fallback;
        long value = Long.parseLong(matcher.group(1));
        Duration d =
                switch (matcher.group(2)) {
                    case "s" -> Duration.ofSeconds(value);
                    case "m" -> Duration.ofMinutes(value);
                    case "h" -> Duration.ofHours(value);
                    case "d" -> Duration.ofDays(value);
                    default -> fallback;
                };
        if (d.isZero() || d.isNegative()) return fallback;
        if (d.compareTo(Duration.ofDays(30)) > 0) return Duration.ofDays(30);
        return d;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static CrashPageRequest resolveCrashPageRequest(
            Integer pageParam, Integer pageSizeParam, Integer limitParam, int maxPageSize) {
        int page = Math.max(1, pageParam != null ? pageParam : 1);
        int pageSize;
        if (pageSizeParam != null) {
            pageSize = Math.clamp(pageSizeParam, 1, maxPageSize);
        } else if (limitParam != null && pageParam == null) {
            pageSize = Math.clamp(limitParam, 1, maxPageSize);
        } else {
            pageSize = maxPageSize;
        }
        return new CrashPageRequest(page, pageSize, (page - 1) * pageSize, pageSize);
    }

    record CrashPageRequest(int page, int pageSize, int offset, int limit) {}
}
