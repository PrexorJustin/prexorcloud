package me.prexorjustin.prexorcloud.controller.rest.dto;

import java.util.Map;

/** Body for {@code POST /groups/{name}/start}: how many to start and optional per-instance variables. */
public record StartGroupRequest(Integer count, Map<String, String> variables) {}
