package me.prexorjustin.prexorcloud.controller.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Per-module soft resource quota (northstar-plan Track C.2, stage 2 — "Soft-Limits").
 *
 * <p>Configured under {@code modules.quotas.<moduleId>} in {@code controller.yml}. The
 * {@code ModuleQuotaEnforcer} reads the per-minute resource rates derived from the stage-1
 * {@code ModuleResourceTracker.Snapshot} and compares them against these limits. A breach is
 * <em>advisory</em>: it raises a WARN log and the {@code prexorcloud.module.quota.exceeded}
 * metric, but does not throttle or kill the module. Hard isolation (stage 3) is what enforces.
 *
 * <p>Each limit treats {@code 0} (or any non-positive value) as <em>unlimited</em>, so an empty
 * quota block disables enforcement for that module while still leaving the tracker running.
 *
 * <p>Note on naming: the plan sketched {@code maxOpenFiles} as the third dimension, but the
 * stage-1 tracker samples live <em>threads</em>, not open file descriptors — there is no
 * fd accounting to compare against. {@code maxThreads} is the honest, enforceable equivalent;
 * open-file limits would need extra sampling and are deferred.
 */
public record ModuleQuota(
        @JsonProperty("maxCpuMillisPerMinute") long maxCpuMillisPerMinute,
        @JsonProperty("maxAllocatedMbPerMinute") long maxAllocatedMbPerMinute,
        @JsonProperty("maxThreads") int maxThreads) {

    public boolean limitsCpu() {
        return maxCpuMillisPerMinute > 0;
    }

    public boolean limitsAllocation() {
        return maxAllocatedMbPerMinute > 0;
    }

    public boolean limitsThreads() {
        return maxThreads > 0;
    }

    /** True if at least one dimension is constrained — an all-zero quota enforces nothing. */
    public boolean enforcesAnything() {
        return limitsCpu() || limitsAllocation() || limitsThreads();
    }
}
