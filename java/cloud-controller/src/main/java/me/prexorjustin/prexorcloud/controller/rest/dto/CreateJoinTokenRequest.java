package me.prexorjustin.prexorcloud.controller.rest.dto;

public record CreateJoinTokenRequest(String nodeId, Integer ttlSeconds, String ttl) {}
