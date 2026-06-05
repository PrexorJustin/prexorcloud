package me.prexorjustin.prexorcloud.controller.rest.dto;

public record PlayerDto(
        String id,
        String name,
        String currentInstance,
        String currentGroup,
        String proxyInstance,
        String connectedSince,
        String edition) {}
