package me.prexorjustin.prexorcloud.api.event.events;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;

/** Fired when a server template file is updated. */
public record TemplateUpdatedEvent(String templateName, String oldHash, String newHash) implements CloudEvent {

    @Override
    public String type() {
        return "TEMPLATE_UPDATED";
    }
}
