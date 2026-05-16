package me.prexorjustin.prexorcloud.api.event.events;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;

/**
 * Fired when a group's running instance count or total player count changes.
 * Lets dashboards patch cached aggregates without re-fetching the full group
 * detail on every player join/disconnect or instance transition.
 */
public record GroupAggregatesUpdatedEvent(String groupName, int runningInstances, int totalPlayers)
        implements CloudEvent {

    @Override
    public String type() {
        return "GROUP_AGGREGATES_UPDATED";
    }
}
