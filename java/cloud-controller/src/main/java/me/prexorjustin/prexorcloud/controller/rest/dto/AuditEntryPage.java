package me.prexorjustin.prexorcloud.controller.rest.dto;

import java.util.List;

public record AuditEntryPage(List<AuditEntryDto> data, int page, int pageSize, int total) {}
