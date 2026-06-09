package me.prexorjustin.prexorcloud.controller.scheduler;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import me.prexorjustin.prexorcloud.controller.redis.RedisKeys;
import me.prexorjustin.prexorcloud.controller.state.StartRetryIntent;

import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;

/**
 * Redis-backed wakeup queue for transient start retries. Durable retry intent
 * remains in Mongo via {@code WorkflowStateStore}; Redis only coordinates due
 * wakeups and short-lived cross-controller claims.
 */
public final class RedisStartRetryWakeupQueue implements StartRetryWakeupQueue {

    private static final String WAKEUP_KEY = RedisKeys.START_RETRY_WAKEUP;

    private final RedisCommands<String, String> commands;
    private final String controllerId;
    private final long claimTtlSeconds;

    public RedisStartRetryWakeupQueue(
            RedisCommands<String, String> commands, String controllerId, long schedulerEvaluationIntervalSeconds) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.controllerId = Objects.requireNonNull(controllerId, "controllerId");
        this.claimTtlSeconds =
                RedisKeys.startRetryClaimTtl(schedulerEvaluationIntervalSeconds).getSeconds();
    }

    @Override
    public void schedule(StartRetryIntent intent) {
        Objects.requireNonNull(intent, "intent");
        commands.del(RedisKeys.startRetryClaim(intent.instanceId()));
        commands.zadd(WAKEUP_KEY, intent.retryAt().toEpochMilli(), intent.instanceId());
    }

    @Override
    public void cancel(String instanceId) {
        Objects.requireNonNull(instanceId, "instanceId");
        commands.zrem(WAKEUP_KEY, instanceId);
        commands.del(RedisKeys.startRetryClaim(instanceId));
    }

    @Override
    public List<String> claimDue(Instant now, int limit) {
        Objects.requireNonNull(now, "now");
        if (limit < 1) {
            return List.of();
        }
        var due = commands.zrangebyscore(WAKEUP_KEY, 0, now.toEpochMilli());
        if (due.isEmpty()) {
            return List.of();
        }
        var claimed = new ArrayList<String>(Math.min(limit, due.size()));
        int count = 0;
        for (String instanceId : due) {
            if (count >= limit) {
                break;
            }
            String claimKey = RedisKeys.startRetryClaim(instanceId);
            String result = commands.set(
                    claimKey,
                    controllerId + "|" + now.toEpochMilli(),
                    SetArgs.Builder.nx().ex(claimTtlSeconds));
            if (!"OK".equals(result)) {
                continue;
            }
            Long removed = commands.zrem(WAKEUP_KEY, instanceId);
            if (removed == null || removed == 0L) {
                commands.del(claimKey);
                continue;
            }
            claimed.add(instanceId);
            count++;
        }
        return List.copyOf(claimed);
    }
}
