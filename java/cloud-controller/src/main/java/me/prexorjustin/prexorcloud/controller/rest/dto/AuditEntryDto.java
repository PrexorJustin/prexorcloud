package me.prexorjustin.prexorcloud.controller.rest.dto;

import java.time.Instant;

public record AuditEntryDto(
        long id,
        String username,
        String action,
        String resourceType,
        String resourceId,
        String details,
        Object before,
        Object after,
        String ipAddress,
        Instant createdAt) {}
