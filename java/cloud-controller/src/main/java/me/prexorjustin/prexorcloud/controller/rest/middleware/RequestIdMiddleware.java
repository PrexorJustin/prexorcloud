package me.prexorjustin.prexorcloud.controller.rest.middleware;

import me.prexorjustin.prexorcloud.common.logging.CorrelationContext;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import org.jetbrains.annotations.NotNull;
import org.slf4j.MDC;

/**
 * Generates a unique request ID for every incoming REST request. The ID is
 * placed in the SLF4J MDC as "requestId" and "correlationId" for structured
 * logging, then returned as response headers for client-side correlation.
 */
public final class RequestIdMiddleware implements Handler {

    public static final String REQUEST_HEADER = "X-Request-Id";
    public static final String CORRELATION_HEADER = "X-Correlation-Id";

    @Override
    public void handle(@NotNull Context ctx) {
        String requestId = CorrelationContext.newId();
        String correlationId = CorrelationContext.sanitize(ctx.header(CORRELATION_HEADER));
        ctx.attribute(CorrelationContext.REQUEST_ID, requestId);
        ctx.attribute(CorrelationContext.CORRELATION_ID, correlationId);
        MDC.put(CorrelationContext.REQUEST_ID, requestId);
        MDC.put(CorrelationContext.CORRELATION_ID, correlationId);
        MDC.put("httpMethod", ctx.method().name());
        MDC.put("httpPath", ctx.path());
        ctx.header(REQUEST_HEADER, requestId);
        ctx.header(CORRELATION_HEADER, correlationId);
    }

    /**
     * Clears the MDC after the request completes. Register as an afterMatched
     * handler.
     */
    public static void clearMdc(@NotNull Context ctx) {
        MDC.remove(CorrelationContext.REQUEST_ID);
        MDC.remove(CorrelationContext.CORRELATION_ID);
        MDC.remove("httpMethod");
        MDC.remove("httpPath");
    }
}
