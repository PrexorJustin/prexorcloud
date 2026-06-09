package me.prexorjustin.prexorcloud.controller.rest.dto;

import java.util.List;
import java.util.Map;

public record CrashTrendDto(
        String windowStart,
        String windowEnd,
        long windowSeconds,
        long bucketSeconds,
        long total,
        Map<String, Long> totalsByClassification,
        List<CrashTrendBucket> buckets) {

    public record CrashTrendBucket(String ts, long count, Map<String, Long> byClassification) {}
}
