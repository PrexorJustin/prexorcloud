package me.prexorjustin.prexorcloud.api.module.rest;

/**
 * Variant of {@link RouteHandler} that receives a pre-parsed body. The
 * {@link RouteRegistrar} typed overloads parse the JSON body via
 * {@link ApiRequest#bodyAs(Class)} and on parse failure emit a standard
 * {@code 400 {"error":"invalid json body","details":"..."}} envelope without
 * ever invoking the handler.
 */
@FunctionalInterface
public interface TypedRouteHandler<T> {

    void handle(ApiRequest request, T body, ApiResponse response) throws Exception;
}
