package me.prexorjustin.prexorcloud.controller.rest.dto;

import java.util.List;

/**
 * Paginated list of {@link ShareRecordDto} entries.
 */
public record ShareListPage(List<ShareRecordDto> data, int total, int page, int pageSize) {}
