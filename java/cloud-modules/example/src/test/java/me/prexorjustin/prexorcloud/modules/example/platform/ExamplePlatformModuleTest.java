package me.prexorjustin.prexorcloud.modules.example.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import java.nio.file.Path;
import java.util.List;
import java.util.function.ToLongFunction;

import me.prexorjustin.prexorcloud.api.module.data.IndexSpec;
import me.prexorjustin.prexorcloud.api.module.data.ModuleDataStore;
import me.prexorjustin.prexorcloud.api.module.platform.CapabilityDeclaration;
import me.prexorjustin.prexorcloud.api.module.platform.ModuleContext;
import me.prexorjustin.prexorcloud.api.module.platform.ModuleContexts;
import me.prexorjustin.prexorcloud.api.module.platform.ModuleStorageRequest;
import me.prexorjustin.prexorcloud.api.module.platform.PlatformModuleManifest;
import me.prexorjustin.prexorcloud.api.module.platform.PlatformModuleStorage;
import me.prexorjustin.prexorcloud.modules.example.data.PlaytimeRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("ExamplePlatformModule")
class ExamplePlatformModuleTest {

    @Mock
    ModuleDataStore store;

    @Test
    @DisplayName("Loads repository-backed query capability from scoped Mongo storage")
    void exposesQueryCapability() {
        ExamplePlatformModule module = new ExamplePlatformModule();
        ModuleContext context = context();

        module.onLoad(context);
        module.onStart(context);

        verify(store).ensureCollection(PlaytimeRepository.SESSIONS);
        verify(store).ensureCollection(PlaytimeRepository.TOTALS);
        verify(store, org.mockito.Mockito.atLeast(5)).createIndex(any(String.class), any(IndexSpec.class));
        assertTrue(module.started());
        var handles = module.capabilityHandles();
        assertEquals(1, handles.size());
        assertEquals(
                ExamplePlatformModule.QUERY_CAPABILITY_ID, handles.getFirst().id());
        assertInstanceOf(ToLongFunction.class, handles.getFirst().value());
    }

    @Test
    @DisplayName("Clears lifecycle state on unload")
    void clearsOnUnload() {
        ExamplePlatformModule module = new ExamplePlatformModule();
        ModuleContext context = context();

        module.onLoad(context);
        module.onStart(context);
        module.onUnload(context);

        assertFalse(module.started());
        assertTrue(module.capabilityHandles().isEmpty());
    }

    private ModuleContext context() {
        ModuleStorageRequest storageRequest = new ModuleStorageRequest(true, false);
        PlatformModuleManifest manifest = new PlatformModuleManifest(
                PlatformModuleManifest.CURRENT_MANIFEST_VERSION,
                "example-playtime",
                "1.0.0",
                new PlatformModuleManifest.Backend(ExamplePlatformModule.class.getName()),
                null,
                new CapabilityDeclaration(
                        List.of(new CapabilityDeclaration.Provides(ExamplePlatformModule.QUERY_CAPABILITY_ID, "1.0.0")),
                        List.of()),
                storageRequest,
                List.of());
        PlatformModuleStorage storage = new PlatformModuleStorage(
                "example-playtime", storageRequest, "prexorcloud", "mod_example_playtime_", null, store, null);
        return ModuleContexts.forTest(manifest, Path.of("example-playtime.jar"), null, java.util.Map.of(), storage);
    }
}
