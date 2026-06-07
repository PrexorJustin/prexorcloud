package me.prexorjustin.prexorcloud.modules.journey.platform;

import java.time.Clock;
import java.util.List;

import me.prexorjustin.prexorcloud.api.module.capability.PlayerJourneyTracker;
import me.prexorjustin.prexorcloud.api.module.platform.CapabilityHandle;
import me.prexorjustin.prexorcloud.api.module.platform.ModuleContext;
import me.prexorjustin.prexorcloud.api.module.platform.PlatformModule;
import me.prexorjustin.prexorcloud.modules.journey.data.JourneyRepository;
import me.prexorjustin.prexorcloud.modules.journey.service.JourneyRecorder;
import me.prexorjustin.prexorcloud.modules.journey.service.MongoPlayerJourneyTracker;

/**
 * First-party module that owns the {@code prexor.player.journey} capability.
 * Replaces the controller-internal {@code PlayerJourneyService} extracted in
 * Layer 5 of the API_OVERHAUL plan.
 *
 * <p>Subscribes to the three {@code PLAYER_*} events on activation, persists
 * one journey entry per observation in its own Mongo collection, and exposes
 * the tracker to other modules (notably {@code stats-aggregator}) via the
 * {@link CapabilityHandle} returned from {@link #capabilityHandles()}.
 */
public final class PlayerJourneyModule implements PlatformModule {

    private JourneyRepository repository;
    private MongoPlayerJourneyTracker tracker;
    private JourneyRecorder recorder;

    @Override
    public void onLoad(ModuleContext context) {
        repository = new JourneyRepository(context.requireMongoStorage());
        tracker = new MongoPlayerJourneyTracker(repository);
        recorder = new JourneyRecorder(context.events(), repository, Clock.systemUTC());
    }

    @Override
    public void onStart(ModuleContext context) {
        recorder.start();
    }

    @Override
    public void onStop(ModuleContext context) {
        if (recorder != null) recorder.stop();
    }

    @Override
    public void onUnload(ModuleContext context) {
        recorder = null;
        tracker = null;
        repository = null;
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public List<CapabilityHandle<?>> capabilityHandles() {
        if (tracker == null) return List.of();
        return List.of(
                CapabilityHandle.of(PlayerJourneyTracker.CAPABILITY_ID, (Class) PlayerJourneyTracker.class, tracker));
    }
}
