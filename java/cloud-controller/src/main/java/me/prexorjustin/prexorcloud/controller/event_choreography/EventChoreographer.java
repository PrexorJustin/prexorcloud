package me.prexorjustin.prexorcloud.controller.event_choreography;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import me.prexorjustin.prexorcloud.api.domain.EventChoreography;
import me.prexorjustin.prexorcloud.api.event.events.ChoreographyOverlayActivatedEvent;
import me.prexorjustin.prexorcloud.api.event.events.ChoreographyOverlayDeactivatedEvent;
import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.group.GroupConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cron-shaped overlay engine for groups. Holds the configured
 * {@link EventChoreography} entries from {@code controller.yaml: events:},
 * evaluates which overlays are active at a given instant, and produces
 * choreographed copies of {@link GroupConfig} that the scheduler then treats
 * as the resolved group for the duration of the firing window.
 *
 * <p>Stateless w.r.t. firing windows except for transition tracking: when an
 * overlay flips from inactive→active or vice versa across two consecutive
 * {@link #refresh(Instant)} calls the choreographer publishes
 * {@link ChoreographyOverlayActivatedEvent}/{@link ChoreographyOverlayDeactivatedEvent}
 * on the {@link EventBus}.
 */
public final class EventChoreographer {

    private static final Logger logger = LoggerFactory.getLogger(EventChoreographer.class);

    private final List<Entry> entries;
    private final EventBus eventBus;
    private final Supplier<Instant> clock;
    /** Last observed active overlay name per group (snapshot for transition detection). */
    private final Map<String, AtomicReference<String>> lastActiveByGroup = new ConcurrentHashMap<>();

    public EventChoreographer(List<EventChoreography> events, EventBus eventBus) {
        this(events, eventBus, Instant::now);
    }

    public EventChoreographer(List<EventChoreography> events, EventBus eventBus, Supplier<Instant> clock) {
        this.eventBus = eventBus;
        this.clock = clock;
        if (events == null || events.isEmpty()) {
            this.entries = List.of();
            return;
        }
        var parsed = new ArrayList<Entry>(events.size());
        var seen = new HashMap<String, EventChoreography>();
        for (var event : events) {
            if (seen.put(event.name(), event) != null) {
                throw new IllegalArgumentException("duplicate event name: " + event.name());
            }
            CronExpression cron;
            try {
                cron = CronExpression.parse(event.cron());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "invalid cron in event '" + event.name() + "': " + e.getMessage(), e);
            }
            ZoneId zone;
            try {
                zone = ZoneId.of(event.timezone());
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "invalid timezone in event '" + event.name() + "': " + event.timezone(), e);
            }
            parsed.add(new Entry(event, cron, zone));
        }
        this.entries = List.copyOf(parsed);
    }

    /** All configured choreography entries (read-only). */
    public List<EventChoreography> events() {
        return entries.stream().map(Entry::config).toList();
    }

    /**
     * The active overlay for {@code groupName} at the given instant, or empty
     * when no entry's window covers that instant. When several entries cover
     * the same group, the one whose firing started most recently wins.
     */
    public Optional<ActiveOverlay> activeFor(String groupName, Instant at) {
        if (entries.isEmpty() || groupName == null) return Optional.empty();
        Entry winner = null;
        Instant winnerStart = null;
        for (Entry entry : entries) {
            if (!entry.config().group().equals(groupName)) continue;
            Instant start = entry.activeFiringStart(at);
            if (start == null) continue;
            if (winnerStart == null || start.isAfter(winnerStart)) {
                winner = entry;
                winnerStart = start;
            }
        }
        if (winner == null) return Optional.empty();
        return Optional.of(new ActiveOverlay(
                winner.config(),
                winnerStart,
                winnerStart.plusSeconds(winner.config().durationSeconds())));
    }

    /** All overlays active at {@code at}, indexed by group name. */
    public Map<String, ActiveOverlay> activeAt(Instant at) {
        if (entries.isEmpty()) return Map.of();
        var byGroup = new LinkedHashMap<String, ActiveOverlay>();
        for (Entry entry : entries) {
            Instant start = entry.activeFiringStart(at);
            if (start == null) continue;
            ActiveOverlay candidate = new ActiveOverlay(
                    entry.config(), start, start.plusSeconds(entry.config().durationSeconds()));
            ActiveOverlay existing = byGroup.get(entry.config().group());
            if (existing == null || candidate.activeSince().isAfter(existing.activeSince())) {
                byGroup.put(entry.config().group(), candidate);
            }
        }
        return Collections.unmodifiableMap(byGroup);
    }

    /**
     * Apply the active overlay (if any) on top of {@code resolved}, returning
     * a new {@link GroupConfig}. Pass the controller's wall-clock instant.
     */
    public GroupConfig apply(GroupConfig resolved, Instant at) {
        if (resolved == null || entries.isEmpty()) return resolved;
        return activeFor(resolved.name(), at)
                .map(active -> applyOverlay(resolved, active.event().overlay()))
                .orElse(resolved);
    }

    /**
     * Re-evaluate which overlays are currently active and emit transition
     * events for any group whose active overlay name changed since the last
     * refresh. Idempotent. Safe to call from the scheduler tick.
     */
    public void refresh() {
        refresh(clock.get());
    }

    public void refresh(Instant now) {
        if (entries.isEmpty()) return;
        Map<String, ActiveOverlay> active = activeAt(now);

        // Activate / change transitions
        for (var entry : active.entrySet()) {
            String group = entry.getKey();
            String currentName = entry.getValue().event().name();
            AtomicReference<String> ref = lastActiveByGroup.computeIfAbsent(group, _ -> new AtomicReference<>());
            String previous = ref.getAndSet(currentName);
            if (!currentName.equals(previous)) {
                if (previous != null) {
                    publishDeactivated(previous, group, "superseded");
                }
                publishActivated(currentName, group, entry.getValue().activeUntil());
            }
        }

        // Deactivation transitions: groups that had an active overlay last time
        // but no longer do.
        for (var ref : lastActiveByGroup.entrySet()) {
            if (active.containsKey(ref.getKey())) continue;
            String previous = ref.getValue().getAndSet(null);
            if (previous != null) {
                publishDeactivated(previous, ref.getKey(), "expired");
            }
        }
    }

    private void publishActivated(String eventName, String group, Instant activeUntil) {
        logger.info("Choreography overlay activated: event={} group={} until={}", eventName, group, activeUntil);
        if (eventBus != null) {
            eventBus.publish(new ChoreographyOverlayActivatedEvent(eventName, group, activeUntil));
        }
    }

    private void publishDeactivated(String eventName, String group, String reason) {
        logger.info("Choreography overlay deactivated: event={} group={} reason={}", eventName, group, reason);
        if (eventBus != null) {
            eventBus.publish(new ChoreographyOverlayDeactivatedEvent(eventName, group, reason));
        }
    }

    private static GroupConfig applyOverlay(GroupConfig source, EventChoreography.EventOverlay overlay) {
        int min = overlay.minInstances() != null ? overlay.minInstances() : source.minInstances();
        int max = overlay.maxInstances() != null ? overlay.maxInstances() : source.maxInstances();
        if (min > max) {
            // Overlay would invert the bounds. Clamp to a coherent pair: prefer
            // the overlay's stronger intent (whichever side the overlay set).
            if (overlay.minInstances() != null && overlay.maxInstances() == null) {
                max = min;
            } else if (overlay.maxInstances() != null && overlay.minInstances() == null) {
                min = Math.min(min, max);
            } else {
                min = Math.min(min, max);
            }
        }
        String scalingMode =
                overlay.scalingMode() != null ? overlay.scalingMode().toUpperCase(Locale.ROOT) : source.scalingMode();
        boolean maintenance = overlay.maintenance() != null ? overlay.maintenance() : source.maintenance();
        String maintenanceMessage = overlay.maintenanceMessage() != null
                        && !overlay.maintenanceMessage().isBlank()
                ? overlay.maintenanceMessage()
                : source.maintenanceMessage();

        return new GroupConfig(
                source.name(),
                source.parent(),
                source.platform(),
                source.platformVersion(),
                source.jarFile(),
                source.templates(),
                scalingMode,
                min,
                max,
                source.maxPlayers(),
                source.scaleUpThreshold(),
                source.scaleDownAfterSeconds(),
                source.scaleCooldownSeconds(),
                source.predictiveScaling(),
                source.scaleUpMargin(),
                source.burstCeiling(),
                source.routing(),
                source.portRangeStart(),
                source.portRangeEnd(),
                source.startupTimeoutSeconds(),
                source.shutdownGraceSeconds(),
                source.drainOnShutdown(),
                source.maxLifetimeSeconds(),
                source.isStatic(),
                source.staticInstanceNames(),
                source.protectedPaths(),
                source.fallbackGroup(),
                source.defaultGroup(),
                source.dependsOn(),
                source.startupWeight(),
                maintenance,
                maintenanceMessage,
                source.maintenanceBypass(),
                source.updateStrategy(),
                source.nodeAffinity(),
                source.nodeAntiAffinity(),
                source.spreadConstraint(),
                source.priority(),
                source.memoryMb(),
                source.cpuReservation(),
                source.diskReservationMb(),
                source.jvmArgs(),
                source.env(),
                source.motds(),
                source.motdMode(),
                source.motdIntervalSeconds(),
                source.attachedModules(),
                source.enabledModules(),
                source.disabledModules(),
                source.attachedExtensions(),
                source.enabledExtensions(),
                source.disabledExtensions(),
                source.configPatches(),
                source.bedrockProxyGroup());
    }

    /** A currently-active overlay snapshot. */
    public record ActiveOverlay(EventChoreography event, Instant activeSince, Instant activeUntil) {}

    private record Entry(EventChoreography config, CronExpression cron, ZoneId zone) {

        /**
         * The instant of the most recent firing whose active window still covers
         * {@code now}, or {@code null} if no window covers it.
         */
        Instant activeFiringStart(Instant now) {
            ZonedDateTime end = now.atZone(zone).truncatedTo(ChronoUnit.MINUTES);
            long lookback = Math.max(0, config.durationSeconds() / 60);
            ZonedDateTime cursor = end;
            for (long i = 0; i <= lookback; i++) {
                if (cron.matches(cursor)) {
                    Instant firing = cursor.toInstant();
                    Instant until = firing.plusSeconds(config.durationSeconds());
                    if (!now.isBefore(firing) && now.isBefore(until)) {
                        return firing;
                    }
                }
                cursor = cursor.minusMinutes(1);
            }
            return null;
        }
    }
}
