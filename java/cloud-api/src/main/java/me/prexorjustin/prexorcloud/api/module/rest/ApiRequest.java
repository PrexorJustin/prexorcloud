package me.prexorjustin.prexorcloud.api.module.rest;

import java.util.Map;
import java.util.Optional;

public interface ApiRequest {

    String method();

    String path();

    Map<String, String> pathParams();

    Map<String, String> queryParams();

    Map<String, String> headers();

    String body();

    <T> T bodyAs(Class<T> type);

    /** Direct path param lookup (throws if missing). */
    default String pathParam(String name) {
        String value = pathParams().get(name);
        if (value == null) throw new IllegalArgumentException("Missing path param: " + name);
        return value;
    }

    default Optional<String> queryParam(String name) {
        return Optional.ofNullable(queryParams().get(name));
    }

    default Optional<String> header(String name) {
        return Optional.ofNullable(headers().get(name));
    }

    default Optional<String> userId() {
        return header("X-User-Id");
    }
}
