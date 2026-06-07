package me.prexorjustin.prexorcloud.controller.rest.dto;

public record CreateJoinTokenResponse(String tokenId, String token, String nodeId, String expiresAt) {}
