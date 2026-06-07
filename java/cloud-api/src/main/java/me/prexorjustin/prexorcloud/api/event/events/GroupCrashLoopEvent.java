package me.prexorjustin.prexorcloud.api.event.events;

import java.time.Instant;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;

/** Fired when a group's instances are detected to be crash-looping. */
public record GroupCrashLoopEvent(String group, int crashCount, Instant windowStart) implements CloudEvent {

    @Override
    public String type() {
        return "GROUP_CRASH_LOOP";
    }
}
