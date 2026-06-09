package me.prexorjustin.prexorcloud.controller.rest.dto;

/**
 * Share-request payload for controller- and daemon-log share endpoints.
 * Combines the standard {@link ShareRequestDto} fields with the log filter
 * params already accepted by {@code GET /api/v1/system/logs}.
 *
 * @param level         minimum log level (TRACE/DEBUG/INFO/WARN/ERROR) — defaults to INFO server-side
 * @param logger        logger-name substring filter
 * @param limit         max records to include (clamped to 1000 server-side)
 * @param expiry        passthrough — see {@link ShareRequestDto#expiry()}
 * @param isPrivate     passthrough — see {@link ShareRequestDto#isPrivate()}
 * @param burnAfterRead passthrough — see {@link ShareRequestDto#burnAfterRead()}
 */
public record ShareLogRequestDto(
        String level, String logger, Integer limit, String expiry, Boolean isPrivate, Boolean burnAfterRead) {}
