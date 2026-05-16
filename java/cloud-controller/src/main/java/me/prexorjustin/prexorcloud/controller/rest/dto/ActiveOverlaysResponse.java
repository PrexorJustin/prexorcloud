package me.prexorjustin.prexorcloud.controller.rest.dto;

import java.time.Instant;
import java.util.List;

public record ActiveOverlaysResponse(List<ActiveEventOverlayDto> active) {

    public record ActiveEventOverlayDto(String eventName, String group, Instant activeSince, Instant activeUntil) {}
}
