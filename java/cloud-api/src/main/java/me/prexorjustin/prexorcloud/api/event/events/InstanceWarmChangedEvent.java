package me.prexorjustin.prexorcloud.api.event.events;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;

/**
 * Fired when an instance's warm-pool membership flips without a lifecycle state
 * change. A warm instance is RUNNING but held back from automatic routing;
 * promoting it (warm {@code true -> false}) makes it routable instantly, with no
 * JVM cold start. Proxies consume this to keep their routing filter current,
 * since no {@link InstanceStateChangedEvent} fires for a RUNNING->RUNNING flip.
 */
public record InstanceWarmChangedEvent(String instanceId, String group, String nodeId, boolean warm)
        implements CloudEvent {

    @Override
    public String type() {
        return "INSTANCE_WARM_CHANGED";
    }
}
