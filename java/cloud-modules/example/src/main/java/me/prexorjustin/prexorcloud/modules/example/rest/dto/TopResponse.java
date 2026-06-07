package me.prexorjustin.prexorcloud.modules.example.rest.dto;

import java.util.List;

import me.prexorjustin.prexorcloud.modules.example.data.TopEntry;

/** Response shape for {@code GET /top}. */
public record TopResponse(int count, List<TopEntry> items) {}
