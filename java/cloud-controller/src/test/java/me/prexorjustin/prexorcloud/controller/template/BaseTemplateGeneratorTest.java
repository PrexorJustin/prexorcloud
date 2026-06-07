package me.prexorjustin.prexorcloud.controller.template;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.state.StateStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("BaseTemplateGenerator")
class BaseTemplateGeneratorTest {

    @TempDir
    Path tempDir;

    @Mock
    StateStore stateStore;

    private TemplateManager templateManager;
    private BaseTemplateGenerator generator;

    @BeforeEach
    void setUp() {
        templateManager = new TemplateManager(tempDir, stateStore, new EventBus());
        generator = new BaseTemplateGenerator(templateManager);
    }

    @Test
    @DisplayName("installs the Geyser integration as an Extension (extensions/, not plugins/) with default config")
    void geyserTemplateInstallsExtensionInExtensionsDir() throws Exception {
        generator.ensureBaseTemplates();
        generator.ensurePlatformTemplate("GEYSER", "PROXY", "geyser");

        Path filesDir = templateManager.getTemplateFilesDir("base-geyser");
        assertTrue(
                Files.exists(filesDir.resolve("extensions/PrexorCloudGeyserExtension.jar")),
                "Geyser jar must be installed under extensions/");
        assertFalse(
                Files.exists(filesDir.resolve("plugins/PrexorCloudGeyserExtension.jar")),
                "Geyser jar must NOT be installed under plugins/");
        assertTrue(Files.exists(filesDir.resolve("config.yml")), "default geyser config.yml must be shipped");
    }
}
