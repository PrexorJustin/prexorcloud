package me.prexorjustin.prexorcloud.api.module.group;

import java.util.List;
import java.util.Map;

public record GroupCreateRequest(
        String name,
        String platform,
        int minInstances,
        int maxInstances,
        int maxPlayers,
        int memoryMb,
        double cpuReservation,
        long diskReservationMb,
        List<String> jvmArgs,
        Map<String, String> env,
        List<String> nodeAffinity,
        boolean isStatic,
        boolean isDefaultGroup) {}
