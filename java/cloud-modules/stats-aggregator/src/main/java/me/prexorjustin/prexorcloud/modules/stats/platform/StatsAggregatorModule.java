package me.prexorjustin.prexorcloud.modules.stats.platform;

import java.time.Clock;
import java.util.List;

import me.prexorjustin.prexorcloud.api.module.capability.PlayerJourneyTracker;
import me.prexorjustin.prexorcloud.api.module.platform.CapabilityHandle;
import me.prexorjustin.prexorcloud.api.module.platform.ModuleContext;
import me.prexorjustin.prexorcloud.api.module.platform.PlatformModule;
import me.prexorjustin.prexorcloud.api.module.rest.RouteRegistrar;
import me.prexorjustin.prexorcloud.modules.stats.config.StatsConfig;
import me.prexorjustin.prexorcloud.modules.stats.data.StatsRepository;
import me.prexorjustin.prexorcloud.modules.stats.metrics.PrometheusExporter;
import me.prexorjustin.prexorcloud.modules.stats.rest.StatsRoutes;
import me.prexorjustin.prexorcloud.modules.stats.service.JourneyEnricher;
import me.prexorjustin.prexorcloud.modules.stats.service.LeaderboardService;
import me.prexorjustin.prexorcloud.modules.stats.service.SessionAggregator;

/**
 * First-party reference module: aggregates Player Journey Bus events into
 * playtime / session / leaderboard projections, exposes REST + Prometheus.
 */
public final class StatsAggregatorModule implements PlatformModule {

    public static final String LEADERBOARD_CAPABILITY_ID = "stats-aggregator-leaderboard";

    private StatsConfig config;
    private StatsRepository repository;
    private SessionAggregator aggregator;
    private LeaderboardService leaderboard;
    private JourneyEnricher journey;
    private PrometheusExporter prometheus;
    private StatsRoutes routes;
    private boolean started;

    @Override
    public void onLoad(ModuleContext context) {
        config = StatsConfig.defaults();
        repository = new StatsRepository(context.requireMongoStorage());
        aggregator = new SessionAggregator(repository);
        leaderboard = new LeaderboardService(repository);
        PlayerJourneyTracker tracker = context.findCapability(
                        PlayerJourneyTracker.CAPABILITY_ID, PlayerJourneyTracker.class)
                .orElse(null);
        journey = new JourneyEnricher(tracker);
        prometheus = new PrometheusExporter(repository, config.leaderboardSize());
        routes = new StatsRoutes(repository, aggregator, leaderboard, journey, prometheus, config, Clock.systemUTC());
    }

    @Override
    public void onRegisterRoutes(RouteRegistrar registrar) {
        if (routes != null) {
            routes.register(registrar);
        }
    }

    @Override
    public void onStart(ModuleContext context) {
        started = true;
    }

    @Override
    public void onStop(ModuleContext context) {
        started = false;
    }

    @Override
    public void onUnload(ModuleContext context) {
        routes = null;
        prometheus = null;
        journey = null;
        leaderboard = null;
        aggregator = null;
        repository = null;
        config = null;
        started = false;
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public List<CapabilityHandle<?>> capabilityHandles() {
        if (leaderboard == null) {
            return List.of();
        }
        return List.of(CapabilityHandle.of(LEADERBOARD_CAPABILITY_ID, (Class) LeaderboardService.class, leaderboard));
    }

    public boolean started() {
        return started;
    }

    public StatsRoutes routes() {
        return routes;
    }
}
