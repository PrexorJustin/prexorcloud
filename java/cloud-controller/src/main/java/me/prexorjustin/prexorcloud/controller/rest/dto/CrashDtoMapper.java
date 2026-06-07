package me.prexorjustin.prexorcloud.controller.rest.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import me.prexorjustin.prexorcloud.controller.crash.CrashRecord;
import me.prexorjustin.prexorcloud.controller.crash.CrashTrendBucketer;

public final class CrashDtoMapper {

    private CrashDtoMapper() {}

    public static Map<String, Object> toSummaryDto(CrashRecord crash) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", crash.id());
        dto.put("instanceId", crash.instanceId());
        dto.put("group", crash.group());
        dto.put("node", crash.nodeId());
        dto.put("exitCode", crash.exitCode());
        dto.put("classification", crash.classification());
        dto.put("causeSummary", crash.causeSummary());
        dto.put("signature", crash.signature());
        dto.put("uptimeMs", crash.uptimeMs());
        dto.put("crashedAt", crash.crashedAt().toString());
        return dto;
    }

    public static Map<String, Object> toDetailDto(CrashRecord crash) {
        Map<String, Object> dto = new LinkedHashMap<>(toSummaryDto(crash));
        dto.put("logTail", crash.logTail());
        return dto;
    }

    public static Map<String, Object> toTrendDto(CrashTrendBucketer.Trend trend) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("windowStart", trend.windowStart().toString());
        dto.put("windowEnd", trend.windowEnd().toString());
        dto.put("windowSeconds", trend.windowSeconds());
        dto.put("bucketSeconds", trend.bucketSeconds());
        dto.put("total", trend.total());
        dto.put("totalsByClassification", trend.totalsByClassification());

        List<Map<String, Object>> buckets = new ArrayList<>(trend.buckets().size());
        for (var b : trend.buckets()) {
            Map<String, Object> bucket = new LinkedHashMap<>();
            bucket.put("ts", b.ts().toString());
            bucket.put("count", b.count());
            bucket.put("byClassification", b.byClassification());
            buckets.add(bucket);
        }
        dto.put("buckets", buckets);
        return dto;
    }
}
