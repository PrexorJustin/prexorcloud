package me.prexorjustin.prexorcloud.api.event.events;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;

/** Fired when a deployment revision completes (success or failure). */
public record DeploymentCompletedEvent(String groupName, int revision, String outcome) implements CloudEvent {

    @Override
    public String type() {
        return "DEPLOYMENT_COMPLETED";
    }
}
