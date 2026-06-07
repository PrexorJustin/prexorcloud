package me.prexorjustin.prexorcloud.api.event.events;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;

/** Fired when a line of console output is captured from an instance. */
public record InstanceConsoleOutputEvent(String instanceId, String line, long timestampMs) implements CloudEvent {

    @Override
    public String type() {
        return "INSTANCE_CONSOLE_OUTPUT";
    }
}
