package me.prexorjustin.prexorcloud.controller.rest.dto;

import java.util.List;

public record CrashSummaryPage(List<CrashSummaryDto> data, int page, int pageSize, int total) {}
