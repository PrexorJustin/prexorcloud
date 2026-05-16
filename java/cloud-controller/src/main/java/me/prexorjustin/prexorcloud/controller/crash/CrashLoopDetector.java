package me.prexorjustin.prexorcloud.controller.crash;

import java.time.Instant;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import me.prexorjustin.prexorcloud.api.event.events.GroupCrashLoopEvent;
import me.prexorjustin.prexorcloud.controller.event.EventBus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detects crash loops per group using a sliding window. When a crash loop is
 * detected, the group is paused (scheduler skips auto-restart). After a
 * configurable cooldown period, the group is automatically unpaused for one
 * retry. If it crashes again immediately, it re-pauses with exponential
 * backoff.
 */
public final class CrashLoopDetector {

    private static final Logger logger = LoggerFactory.getLogger(CrashLoopDetector.class);
    private static final long DEFAULT_INITIAL_COOLDOWN_SECONDS = 60;
    private static final long MAX_COOLDOWN_SECONDS = 3600; // 1 hour cap

    private final int threshold;
    private final long windowSeconds;
    private final long initialCooldownSeconds;
    private final EventBus eventBus;
    private final ScheduledExecutorService cooldownScheduler;

    private final Map<String, Deque<Instant>> crashTimestamps = new ConcurrentHashMap<>();
    private final Set<String> pausedGroups = ConcurrentHashMap.newKeySet();
    /** Tracks the current cooldown multiplier per group for exponential backoff. */
    private final Map<String, Integer> cooldownAttempts = new ConcurrentHashMap<>();

    public CrashLoopDetector(int threshold, long windowSeconds, EventBus eventBus) {
        this(threshold, windowSeconds, DEFAULT_INITIAL_COOLDOWN_SECONDS, eventBus);
    }

    public CrashLoopDetector(int threshold, long windowSeconds, long initialCooldownSeconds, EventBus eventBus) {
        this.threshold = threshold;
        this.windowSeconds = windowSeconds;
        this.initialCooldownSeconds = initialCooldownSeconds;
        this.eventBus = eventBus;
        this.cooldownScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "crash-loop-cooldown");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Record a crash for a group and check if it enters crash loop.
     */
    public void recordCrash(String group) {
        Deque<Instant> times = crashTimestamps.computeIfAbsent(group, _ -> new ConcurrentLinkedDeque<>());
        synchronized (times) {
            times.addLast(Instant.now());

            // Clean old entries
            Instant cutoff = Instant.now().minusSeconds(windowSeconds);
            while (!times.isEmpty() && times.peekFirst().isBefore(cutoff)) {
                times.pollFirst();
            }

            if (times.size() >= threshold && !pausedGroups.contains(group)) {
                pausedGroups.add(group);
                int attempt = cooldownAttempts.merge(group, 1, Integer::sum);
                long cooldownSeconds = Math.min(initialCooldownSeconds * (1L << (attempt - 1)), MAX_COOLDOWN_SECONDS);
                logger.warn(
                        "Crash loop detected for group {} ({} crashes in {}s window, attempt={}, cooldown={}s)",
                        group,
                        times.size(),
                        windowSeconds,
                        attempt,
                        cooldownSeconds);
                eventBus.publish(new GroupCrashLoopEvent(group, times.size(), cutoff));
                scheduleAutoUnpause(group, cooldownSeconds);
            }
        }
    }

    /**
     * Check if a group is currently paused due to crash loop.
     */
    public boolean isCrashLoopPaused(String group) {
        return pausedGroups.contains(group);
    }

    /**
     * Unpause a group (manual operator action). Resets the backoff counter.
     */
    public void unpause(String group) {
        if (pausedGroups.remove(group)) {
            crashTimestamps.remove(group);
            cooldownAttempts.remove(group);
            logger.info("Crash loop pause cleared for group {} (manual)", group);
        }
    }

    private void scheduleAutoUnpause(String group, long cooldownSeconds) {
        cooldownScheduler.schedule(
                () -> {
                    if (pausedGroups.remove(group)) {
                        crashTimestamps.remove(group);
                        logger.info(
                                "Auto-unpause for group {} after {}s cooldown (allowing one retry)",
                                group,
                                cooldownSeconds);
                    }
                },
                cooldownSeconds,
                TimeUnit.SECONDS);
    }
}
