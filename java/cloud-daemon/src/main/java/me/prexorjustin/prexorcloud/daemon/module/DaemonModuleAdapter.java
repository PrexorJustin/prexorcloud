package me.prexorjustin.prexorcloud.daemon.module;

import java.util.List;
import java.util.Objects;

import me.prexorjustin.prexorcloud.api.module.platform.CapabilityHandle;
import me.prexorjustin.prexorcloud.api.module.platform.DaemonModule;
import me.prexorjustin.prexorcloud.api.module.platform.ModuleContext;
import me.prexorjustin.prexorcloud.api.module.platform.PlatformModule;

/**
 * Adapts a {@link DaemonModule} to the {@link PlatformModule} contract so the lifted
 * {@code ModuleLifecycleManager} (which only knows {@code PlatformModule}) can run a
 * daemon module's lifecycle. Instance-lifecycle hooks ({@code onInstanceStarting}, etc.)
 * are NOT dispatched through this adapter — they go directly through {@code DaemonModuleHost}
 * which holds the underlying {@link DaemonModule} reference.
 */
public final class DaemonModuleAdapter implements PlatformModule {

    private final DaemonModule delegate;

    public DaemonModuleAdapter(DaemonModule delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public DaemonModule delegate() {
        return delegate;
    }

    @Override
    public void onLoad(ModuleContext context) throws Exception {
        delegate.onLoad(context);
    }

    @Override
    public void onStart(ModuleContext context) throws Exception {
        delegate.onStart(context);
    }

    @Override
    public void onStop(ModuleContext context) throws Exception {
        delegate.onStop(context);
    }

    @Override
    public void onUnload(ModuleContext context) throws Exception {
        delegate.onUnload(context);
    }

    @Override
    public void onUpgrade(ModuleContext context) throws Exception {
        delegate.onUpgrade(context);
    }

    @Override
    public List<CapabilityHandle<?>> capabilityHandles() {
        return delegate.capabilityHandles();
    }
}
