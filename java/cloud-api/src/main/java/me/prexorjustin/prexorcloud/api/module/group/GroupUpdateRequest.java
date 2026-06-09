package me.prexorjustin.prexorcloud.api.module.group;

import java.util.List;
import java.util.Map;

/** Partial update — null fields are ignored by the controller. */
public record GroupUpdateRequest(
        Integer minInstances,
        Integer maxInstances,
        Integer maxPlayers,
        Integer memoryMb,
        Double cpuReservation,
        Long diskReservationMb,
        List<String> jvmArgs,
        Map<String, String> env,
        List<String> nodeAffinity) {}
