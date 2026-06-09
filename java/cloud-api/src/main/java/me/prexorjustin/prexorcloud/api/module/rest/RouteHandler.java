package me.prexorjustin.prexorcloud.api.module.rest;

@FunctionalInterface
public interface RouteHandler {

    void handle(ApiRequest request, ApiResponse response) throws Exception;
}
