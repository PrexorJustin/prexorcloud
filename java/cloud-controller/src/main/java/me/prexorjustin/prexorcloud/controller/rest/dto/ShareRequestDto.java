package me.prexorjustin.prexorcloud.controller.rest.dto;

/**
 * Operator-supplied overrides on a {@code --share} call. Every field is
 * optional; null falls back to the corresponding {@code share.default*}
 * configured on the controller.
 *
 * @param expiry        optional pste expiry preset ({@code 1h}/{@code 1d}/{@code 30d}/{@code never})
 * @param isPrivate     optional visibility override (true = private)
 * @param burnAfterRead optional burn-after-read override
 */
public record ShareRequestDto(String expiry, Boolean isPrivate, Boolean burnAfterRead) {}
