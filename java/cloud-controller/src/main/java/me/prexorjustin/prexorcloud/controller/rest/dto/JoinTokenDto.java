package me.prexorjustin.prexorcloud.controller.rest.dto;

public record JoinTokenDto(String tokenId, String nodeId, String expiresAt, String status) {}
