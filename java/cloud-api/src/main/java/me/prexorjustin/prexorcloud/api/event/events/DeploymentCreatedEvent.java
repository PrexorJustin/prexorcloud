package me.prexorjustin.prexorcloud.api.event.events;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;

/** Fired when a new deployment revision is created for a group. */
public record DeploymentCreatedEvent(String groupName, int revision, String strategy) implements CloudEvent {

    @Override
    public String type() {
        return "DEPLOYMENT_CREATED";
    }
}
