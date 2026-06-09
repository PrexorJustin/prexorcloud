package me.prexorjustin.prexorcloud.controller.rest.dto;

import java.util.List;
import java.util.Map;

public record MetricsTimeseriesDto(
        long windowMs, long bucketWidthMs, int buckets, long startedAtMs, Map<String, List<Double>> series) {}
