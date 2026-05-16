package me.prexorjustin.prexorcloud.controller.auth;

import io.javalin.http.Context;

/**
 * Immutable snapshot of the authenticated user for the current request.
 * Extracted from Javalin context attributes set by
 * {@link me.prexorjustin.prexorcloud.controller.rest.middleware.JwtAuthMiddleware}.
 */
public record RequestContext(String username, String role) {

    /**
     * Extract the request context from Javalin context attributes.
     */
    public static RequestContext from(Context ctx) {
        String username = ctx.attribute("username");
        String role = ctx.attribute("role");
        if (username == null || role == null) {
            throw new IllegalStateException("No authenticated user in request context");
        }
        return new RequestContext(username, role);
    }

    /**
     * Check if the current user has a specific permission.
     */
    public boolean hasPermission(String permission) {
        return Role.hasPermission(role, permission);
    }
}
