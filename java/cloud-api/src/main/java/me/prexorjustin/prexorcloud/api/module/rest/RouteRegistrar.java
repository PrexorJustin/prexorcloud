package me.prexorjustin.prexorcloud.api.module.rest;

import java.util.LinkedHashMap;
import java.util.Map;

/** Registers REST routes on the controller's built-in HTTP API. */
public interface RouteRegistrar {

    void get(String path, RouteHandler handler);

    void post(String path, RouteHandler handler);

    void put(String path, RouteHandler handler);

    void delete(String path, RouteHandler handler);

    void patch(String path, RouteHandler handler);

    /**
     * Typed POST: parses the request body as {@code bodyType} via
     * {@link ApiRequest#bodyAs(Class)} before calling {@code handler}. JSON
     * parse failures short-circuit with a standard {@code 400} envelope and
     * the handler is never invoked.
     */
    default <T> void post(String path, Class<T> bodyType, TypedRouteHandler<T> handler) {
        post(path, wrapTyped(bodyType, handler));
    }

    default <T> void put(String path, Class<T> bodyType, TypedRouteHandler<T> handler) {
        put(path, wrapTyped(bodyType, handler));
    }

    default <T> void patch(String path, Class<T> bodyType, TypedRouteHandler<T> handler) {
        patch(path, wrapTyped(bodyType, handler));
    }

    default <T> void delete(String path, Class<T> bodyType, TypedRouteHandler<T> handler) {
        delete(path, wrapTyped(bodyType, handler));
    }

    private static <T> RouteHandler wrapTyped(Class<T> bodyType, TypedRouteHandler<T> handler) {
        if (bodyType == null) throw new IllegalArgumentException("bodyType");
        if (handler == null) throw new IllegalArgumentException("handler");
        return (req, res) -> {
            T body;
            try {
                body = req.bodyAs(bodyType);
            } catch (Exception parseFailure) {
                Map<String, Object> envelope = new LinkedHashMap<>();
                envelope.put("error", "invalid json body");
                String details = parseFailure.getMessage();
                if (details != null && !details.isBlank()) {
                    envelope.put("details", details);
                }
                res.status(400).json(envelope);
                return;
            }
            handler.handle(req, body, res);
        };
    }
}
