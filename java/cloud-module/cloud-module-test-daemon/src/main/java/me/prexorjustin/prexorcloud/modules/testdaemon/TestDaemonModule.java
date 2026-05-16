package me.prexorjustin.prexorcloud.modules.testdaemon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

import me.prexorjustin.prexorcloud.api.event.events.GroupCreatedEvent;
import me.prexorjustin.prexorcloud.api.module.platform.DaemonModule;
import me.prexorjustin.prexorcloud.api.module.platform.ExitInfo;
import me.prexorjustin.prexorcloud.api.module.platform.InstanceHandle;
import me.prexorjustin.prexorcloud.api.module.platform.InstanceSpec;
import me.prexorjustin.prexorcloud.api.module.platform.ModuleContext;

/**
 * Test-only daemon module that records every lifecycle and instance hook fire to a flat
 * file so integration tests running in a different classloader can assert against them.
 *
 * <p>The output path is taken from the {@code prexor.test.testDaemonModuleHooksFile}
 * system property (set by the test harness before the daemon starts). When unset, hooks
 * are recorded to {@code cache/test-daemon-module-hooks.log} relative to the daemon's
 * working directory; that file is harmless to leave around outside of tests.
 */
public final class TestDaemonModule implements DaemonModule {

    private static final String HOOKS_FILE_PROPERTY = "prexor.test.testDaemonModuleHooksFile";
    private static final Path DEFAULT_HOOKS_FILE = Path.of("cache", "test-daemon-module-hooks.log");

    @Override
    public void onLoad(ModuleContext context) {
        record("onLoad", context.manifest().id());
    }

    @Override
    public void onStart(ModuleContext context) {
        record("onStart", context.manifest().id());
        // Subscribe to a controller-bus event so the integration test can verify the
        // EventSubscribe → forward → publishFromController bridge end-to-end.
        context.events()
                .subscribe(GroupCreatedEvent.class, event -> record("event:GROUP_CREATED", event.groupName()));
    }

    @Override
    public void onStop(ModuleContext context) {
        record("onStop", context.manifest().id());
    }

    @Override
    public void onUnload(ModuleContext context) {
        record("onUnload", context.manifest().id());
    }

    @Override
    public void onUpgrade(ModuleContext context) {
        record("onUpgrade", context.manifest().id());
    }

    @Override
    public void onInstanceStarting(InstanceSpec spec) {
        // Echo a JVM arg mutation so the integration test can verify mutations are read back.
        spec.jvmArgs().add("-Dprexor.test.daemon.module.observed=true");
        spec.env().put("PREXOR_TEST_DAEMON_MODULE", "1");
        record("onInstanceStarting", spec.instanceId());
    }

    @Override
    public void onInstanceStarted(InstanceHandle handle) {
        record("onInstanceStarted", handle.instanceId());
    }

    @Override
    public void onInstanceStopping(InstanceHandle handle) {
        record("onInstanceStopping", handle.instanceId());
    }

    @Override
    public void onInstanceStopped(InstanceHandle handle, ExitInfo exit) {
        record("onInstanceStopped:" + (exit.crashed() ? "crashed" : "clean"), handle.instanceId());
    }

    private static void record(String hook, String subject) {
        Path target = hooksFile();
        try {
            Files.createDirectories(target.getParent() == null ? Path.of(".") : target.getParent());
            Files.write(
                    target,
                    (Instant.now().toString() + " " + hook + " " + subject + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException _) {
            // Best-effort recorder; never abort module lifecycle on I/O failure.
        }
    }

    private static Path hooksFile() {
        String configured = System.getProperty(HOOKS_FILE_PROPERTY);
        return configured == null || configured.isBlank() ? DEFAULT_HOOKS_FILE : Path.of(configured);
    }
}
