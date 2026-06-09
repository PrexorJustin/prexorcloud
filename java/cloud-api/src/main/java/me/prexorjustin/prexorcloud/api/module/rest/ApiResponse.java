package me.prexorjustin.prexorcloud.api.module.rest;

/**
 * Mutable response object passed to {@link RouteHandler}. The controller
 * provides the Javalin-backed implementation.
 */
public interface ApiResponse {

    ApiResponse status(int code);

    void json(Object body);

    void text(String body);

    ApiResponse header(String name, String value);
}
