package me.prexorjustin.prexorcloud.modules.backup.platform;

import java.nio.file.Path;
import java.nio.file.Paths;

import me.prexorjustin.prexorcloud.api.ScheduledTask;
import me.prexorjustin.prexorcloud.api.module.capability.InstanceFileAccess;
import me.prexorjustin.prexorcloud.api.module.platform.ModuleContext;
import me.prexorjustin.prexorcloud.api.module.platform.PlatformModule;
import me.prexorjustin.prexorcloud.api.module.rest.RouteRegistrar;
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
 * variable. Per-instance schedules and pattern filters land in a future
 * release; v1 exposes manual REST triggering only.
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
        // Periodic scheduling intentionally not wired in v1 — REST triggers
        // are the only entrypoint. The scheduler primitive is here so a
        // follow-up can attach per-group schedules without re-architecting.
        this.periodicTask = null;
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
