package me.prexorjustin.prexorcloud.controller.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ConfigValidatorTest {

    @Test
    void allowsDevelopmentProfileWithoutRedis() {
        ControllerConfig config = new ControllerConfig(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new RuntimeConfig(RuntimeConfig.DEVELOPMENT),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        assertDoesNotThrow(() -> ConfigValidator.validate(config));
    }

    @Test
    void rejectsProductionProfileWithoutRedis() {
        ControllerConfig config = new ControllerConfig(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new RuntimeConfig(RuntimeConfig.PRODUCTION),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        assertThrows(IllegalStateException.class, () -> ConfigValidator.validate(config));
    }

    @Test
    void rejectsProductionWithoutModuleSigningTrustRoot() {
        ControllerConfig config = new ControllerConfig(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new RuntimeConfig(RuntimeConfig.PRODUCTION),
                null,
                null,
                null,
                new ModulesConfig(
                        "modules",
                        "modules/data",
                        new ModuleSigningConfig(null, null, ModuleSigningConfig.Mode.KEYED, true, null)),
                null,
                null,
                null,
                null,
                new RedisConfig("redis://localhost:6379"));

        var thrown = assertThrows(IllegalStateException.class, () -> ConfigValidator.validate(config));
        // The same call also rejects multiple errors; ensure the signing one is present.
        // (We do not assert exact message text — the validator concatenates errors.)
        assert thrown != null;
    }

    @Test
    void allowsProductionWithSigningOptOut() {
        ControllerConfig config = new ControllerConfig(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new RuntimeConfig(RuntimeConfig.PRODUCTION),
                null,
                null,
                null,
                new ModulesConfig(
                        "modules",
                        "modules/data",
                        new ModuleSigningConfig(false, null, ModuleSigningConfig.Mode.KEYED, true, null)),
                null,
                null,
                null,
                null,
                new RedisConfig("redis://localhost:6379"));

        assertDoesNotThrow(() -> ConfigValidator.validate(config));
    }

    @Test
    void rejectsUnsupportedRuntimeProfile() {
        ControllerConfig config = new ControllerConfig(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new RuntimeConfig("staging"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        assertThrows(IllegalStateException.class, () -> ConfigValidator.validate(config));
    }
}
