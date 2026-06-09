package me.prexorjustin.prexorcloud.controller.scheduler;

import java.util.List;
import java.util.Map;

/**
 * Request to place a new instance on a node.
 *
 * <p>{@code spreadConstraint} carries the bare node-label key that the group
 * wants to spread instances across. {@code existingByBucket} maps each
 * label-value bucket to the number of existing instances of this group already
 * placed in that bucket; the selector uses it to prefer underrepresented
 * buckets. The empty string means no spread preference.
 */
public record InstanceRequest(
        String group,
        int memoryMb,
        double cpuReservation,
        long diskReservationMb,
        int portRangeStart,
        int portRangeEnd,
        List<String> nodeAffinity,
        List<String> nodeAntiAffinity,
        String spreadConstraint,
        Map<String, Integer> existingByBucket) {

    public InstanceRequest(String group, int memoryMb, int portRangeStart, int portRangeEnd) {
        this(group, memoryMb, 0.0, 0, portRangeStart, portRangeEnd, List.of(), List.of(), "", Map.of());
    }

    public InstanceRequest(
            String group,
            int memoryMb,
            int portRangeStart,
            int portRangeEnd,
            List<String> nodeAffinity,
            List<String> nodeAntiAffinity) {
        this(group, memoryMb, 0.0, 0, portRangeStart, portRangeEnd, nodeAffinity, nodeAntiAffinity, "", Map.of());
    }

    public InstanceRequest(
            String group,
            int memoryMb,
            double cpuReservation,
            long diskReservationMb,
            int portRangeStart,
            int portRangeEnd,
            List<String> nodeAffinity,
            List<String> nodeAntiAffinity) {
        this(
                group,
                memoryMb,
                cpuReservation,
                diskReservationMb,
                portRangeStart,
                portRangeEnd,
                nodeAffinity,
                nodeAntiAffinity,
                "",
                Map.of());
    }

    public InstanceRequest {
        if (nodeAffinity == null) nodeAffinity = List.of();
        if (nodeAntiAffinity == null) nodeAntiAffinity = List.of();
        if (spreadConstraint == null) spreadConstraint = "";
        if (existingByBucket == null) existingByBucket = Map.of();
    }
}
