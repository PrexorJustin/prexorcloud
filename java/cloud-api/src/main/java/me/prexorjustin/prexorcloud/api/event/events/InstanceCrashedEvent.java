package me.prexorjustin.prexorcloud.api.event.events;

import java.util.List;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;

/** Fired when a server instance terminates with a non-zero exit code. */
public record InstanceCrashedEvent(
        String instanceId,
        String group,
        String nodeId,
        int exitCode,
        String classification,
        List<String> logTail,
        long uptimeMs)
        implements CloudEvent {

    @Override
    public String type() {
        return "INSTANCE_CRASHED";
    }
}
