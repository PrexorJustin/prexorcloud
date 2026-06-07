package me.prexorjustin.prexorcloud.controller.rest.dto;

public record ErrorResponse(ErrorBody error) {

    public record ErrorBody(String code, String message, int status) {}
}
