package me.prexorjustin.prexorcloud.modules.backup.platform;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import me.prexorjustin.prexorcloud.api.ScheduledTask;
import me.prexorjustin.prexorcloud.api.module.capability.InstanceFileAccess;
import me.prexorjustin.prexorcloud.api.module.platform.ModuleContext;
import me.prexorjustin.prexorcloud.api.module.platform.PlatformModule;
import me.prexorjustin.prexorcloud.api.module.rest.RouteRegistrar;
import me.prexorjustin.prexorcloud.modules.backup.data.SnapshotMetadata;
import me.prexorjustin.prexorcloud.modules.backup.data.SnapshotRepository;
import me.prexorjustin.prexorcloud.modules.backup.rest.BackupRoutes;
import me.prexorjustin.prexorcloud.modules.backup.service.SnapshotService;

import org.slf4j.Logger;

/**
 * Backup-orchestrator entrypoint: produces periodic config-file snapshots of
 * remote instances via the {@link InstanceFileAccess} capability and stores
 * them as tar.gz under a controller-local archive root.
 *
 * <h2>Scope</h2>
 *
 * <p>v1 covers <strong>config and small state files</strong> only — see
 * {@link SnapshotService} for the daemon-side read cap that makes binary
 * world data out of reach until a {@code prexor.instance.snapshot}
 * capability lands (tracked in {@code daemon_service.proto}).
 *
 * <h2>Configuration</h2>
 *
 * <p>The archive root defaults to {@code /var/lib/prexorcloud/snapshots/}
 * and can be overridden via the {@code PREXORCLOUD_BACKUP_DIR} environment
 * variable. Manual REST triggering is always available; opt-in periodic
 * snapshots of a fixed target list are configured via the environment — see
 * {@link BackupSchedule}. Per-target pattern filters still land in a future
 * release (the periodic path uses the default pattern set).
 */
public final class BackupOrchestratorModule implements PlatformModule {

    private static final String DEFAULT_ARCHIVE_ROOT = "/var/lib/prexorcloud/snapshots";
    private static final String ARCHIVE_ROOT_ENV = "PREXORCLOUD_BACKUP_DIR";

    private SnapshotRepository repository;
    private SnapshotService service;
    private BackupRoutes routes;
    private Logger logger;
    private ScheduledTask periodicTask;

    @Override
    public void onLoad(ModuleContext context) {
        this.logger = context.logger();
        this.repository = new SnapshotRepository(context.requireMongoStorage());
        InstanceFileAccess files =
                context.requireCapability(InstanceFileAccess.CAPABILITY_ID, InstanceFileAccess.class);
        Path archiveRoot = resolveArchiveRoot();
        this.service = new SnapshotService(files, repository, archiveRoot, logger);
        this.routes = new BackupRoutes(service, repository);
        logger.info("backup-orchestrator: archive root = {}", archiveRoot);
    }

    @Override
    public void onRegisterRoutes(RouteRegistrar registrar) {
        if (routes != null) {
            routes.register(registrar);
        }
    }

    @Override
    public void onStart(ModuleContext context) {
        BackupSchedule schedule = BackupSchedule.fromEnv();
        if (!schedule.enabled()) {
            logger.info(
                    "backup-orchestrator: periodic snapshots disabled (set {} and {} to enable; REST triggers remain)",
                    BackupSchedule.INTERVAL_ENV,
                    BackupSchedule.TARGETS_ENV);
            this.periodicTask = null;
            return;
        }
        this.periodicTask = context.scheduler()
                .scheduleAtFixedRate(
                        schedule.initialDelay(), schedule.period(), () -> runScheduledSnapshots(schedule.targets()));
        logger.info(
                "backup-orchestrator: periodic snapshots every {} for {} target(s), first run in {}",
                schedule.period(),
                schedule.targets().size(),
                schedule.initialDelay());
    }

    /**
     * Snapshot each configured target on the scheduler thread. Per-target failures are logged and
     * skipped — a single unreachable instance must not abort the rest of the run or kill the task.
     */
    private void runScheduledSnapshots(List<BackupSchedule.Target> targets) {
        SnapshotService svc = this.service;
        if (svc == null) {
            return; // module stopped between tick scheduling and execution
        }
        for (BackupSchedule.Target target : targets) {
            try {
                SnapshotMetadata result =
                        svc.snapshotInstance(target.nodeId(), target.group(), target.instanceId(), null);
                if (!result.ok()) {
                    logger.warn("scheduled snapshot of {} failed: {}", target.instanceId(), result.error());
                }
            } catch (RuntimeException e) {
                logger.warn("scheduled snapshot of {} threw: {}", target.instanceId(), e.toString());
            }
        }
    }

    @Override
    public void onStop(ModuleContext context) {
        if (periodicTask != null) {
            periodicTask.cancel();
            periodicTask = null;
        }
    }

    @Override
    public void onUnload(ModuleContext context) {
        repository = null;
        service = null;
        routes = null;
        logger = null;
    }

    private static Path resolveArchiveRoot() {
        String env = System.getenv(ARCHIVE_ROOT_ENV);
        return Paths.get(env == null || env.isBlank() ? DEFAULT_ARCHIVE_ROOT : env);
    }
}
