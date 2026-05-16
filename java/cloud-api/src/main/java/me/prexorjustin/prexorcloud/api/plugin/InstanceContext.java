package me.prexorjustin.prexorcloud.api.plugin;

import me.prexorjustin.prexorcloud.api.client.CloudClient;
import me.prexorjustin.prexorcloud.api.domain.InstanceView;

/** Self-info for the currently running server instance. */
public interface InstanceContext {

    String instanceId();

    String group();

    String nodeId();

    int port();

    /** Current snapshot of this instance's view from the cluster. */
    InstanceView snapshot();

    /** Low-level cloud client scoped to this instance. */
    CloudClient client();
}
