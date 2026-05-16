package me.prexorjustin.prexorcloud.controller.scheduler;

import java.time.Instant;
import java.util.List;

import me.prexorjustin.prexorcloud.controller.state.StartRetryIntent;

/**
 * Coordinates transient start-retry wakeups independently from the durable
 * workflow intent store so due retries can be claimed safely across
 * controllers.
 */
public interface StartRetryWakeupQueue {

    void schedule(StartRetryIntent intent);

    void cancel(String instanceId);

    List<String> claimDue(Instant now, int limit);
}
