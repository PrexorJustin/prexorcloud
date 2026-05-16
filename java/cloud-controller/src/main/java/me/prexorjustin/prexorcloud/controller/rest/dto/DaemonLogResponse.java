package me.prexorjustin.prexorcloud.controller.rest.dto;

import java.util.List;

public record DaemonLogResponse(
        String nodeId, List<Object> records, int size, int capacity, String level, String logger) {}
