package me.prexorjustin.prexorcloud.controller.module;

import java.util.Objects;
import java.util.Optional;

import me.prexorjustin.prexorcloud.api.module.message.MessageDeliveryApi;
import me.prexorjustin.prexorcloud.controller.module.platform.PlatformModuleManager;

public final class ModuleRegistry {

    @FunctionalInterface
    public interface ServiceResolver {
        <T> Optional<T> resolve(Class<T> type);
    }

    private final ModuleFrontendManager frontendManager;
    private final PlatformModuleManager platformManager;
    private final ServiceResolver serviceResolver;

    public ModuleRegistry(ModuleFrontendManager frontendManager, PlatformModuleManager platformManager) {
        this(frontendManager, platformManager, new ServiceResolver() {
            @Override
            public <T> Optional<T> resolve(Class<T> type) {
                return Optional.empty();
            }
        });
    }

    public ModuleRegistry(
            ModuleFrontendManager frontendManager,
            PlatformModuleManager platformManager,
            ServiceResolver serviceResolver) {
        this.frontendManager = frontendManager;
        this.platformManager = platformManager;
        this.serviceResolver = Objects.requireNonNull(serviceResolver, "serviceResolver");
    }

    public ModuleFrontendManager frontendManager() {
        return frontendManager;
    }

    public PlatformModuleManager platformManager() {
        return platformManager;
    }

    public <T> Optional<T> resolveService(Class<T> type) {
        return serviceResolver.resolve(type);
    }

    public Optional<MessageDeliveryApi> messageDeliveryApi() {
        return resolveService(MessageDeliveryApi.class);
    }
}
