package me.prexorjustin.prexorcloud.controller.group.spec;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import me.prexorjustin.prexorcloud.controller.group.GroupConfig;
import me.prexorjustin.prexorcloud.controller.group.spec.GroupSpec.Aggregation;
import me.prexorjustin.prexorcloud.controller.group.spec.GroupSpec.Identity;
import me.prexorjustin.prexorcloud.controller.group.spec.GroupSpec.Lifecycle;
import me.prexorjustin.prexorcloud.controller.group.spec.GroupSpec.ModulePolicy;
import me.prexorjustin.prexorcloud.controller.group.spec.GroupSpec.Ops;
import me.prexorjustin.prexorcloud.controller.group.spec.GroupSpec.Persistence;
import me.prexorjustin.prexorcloud.controller.group.spec.GroupSpec.Placement;
import me.prexorjustin.prexorcloud.controller.group.spec.GroupSpec.Resources;
import me.prexorjustin.prexorcloud.controller.group.spec.GroupSpec.Rollout;
import me.prexorjustin.prexorcloud.controller.group.spec.GroupSpec.ScalingMode;
import me.prexorjustin.prexorcloud.controller.group.spec.GroupSpec.ScalingPolicy;
import me.prexorjustin.prexorcloud.controller.group.spec.GroupSpec.ScalingSignalSpec;
import me.prexorjustin.prexorcloud.controller.group.spec.GroupSpec.SignalKind;
import me.prexorjustin.prexorcloud.controller.group.spec.GroupSpec.TemplateLayerRef;
import me.prexorjustin.prexorcloud.controller.group.spec.GroupSpec.UpdateStrategy;
import me.prexorjustin.prexorcloud.controller.group.spec.VariableDef.Scope;
import me.prexorjustin.prexorcloud.controller.group.spec.VariableDef.Validation;
import me.prexorjustin.prexorcloud.controller.group.spec.VariableDef.VarType;
import me.prexorjustin.prexorcloud.controller.group.spec.VariableDef.Visibility;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("GroupSpecAdapter")
class GroupSpecAdapterTest {

    private static GroupSpec sampleSpec() {
        return new GroupSpec(
                new Identity("survival", "base-game", "PAPER", "1.21.4", "server.jar"),
                List.of(new TemplateLayerRef("world-data", null, Map.of())),
                new ScalingPolicy(
                        ScalingMode.DYNAMIC, 2, 8, 80,
                        List.of(new ScalingSignalSpec(SignalKind.TPS, null)),
                        Aggregation.AVG, 0.75, 2, 240, 45,
                        new ScalingPolicy.WarmPool(1, 600),
                        new ScalingPolicy.Predictive(false, "", 0)),
                new Placement(List.of("region=eu"), List.of("gpu=true"), "rack", 5, 31000, 31099),
                new Resources(2048, 1.5, 4096L, List.of("-XX:+UseZGC"), Map.of("MODE", "hard")),
                new Lifecycle(150, 45, true, 3600),
                new Persistence(true, List.of("survival-1"), List.of("world/")),
                new Rollout(UpdateStrategy.CANARY, 1, true, true),
                new Ops(true, "patching", List.of("uuid-1"), "lobby", true, List.of("lobby"), 3,
                        List.of("Survival!"), "RANDOM", 20),
                new ModulePolicy(
                        List.of("stats"), List.of(), List.of("debug"),
                        List.of("ext-a"), List.of(), List.of(),
                        Map.of("server.properties", Map.of("difficulty", "hard"))),
                List.of(new VariableDef("MAX_TPS", VarType.INT, "20", false,
                        new Validation(null, 1L, 20L, null), Scope.GROUP, Visibility.OPERATOR, "target tps")),
                "");
    }

    @Test
    @DisplayName("maps every nested policy down to the legacy GroupConfig the planner consumes")
    void mapsAllPoliciesToLegacyConfig() {
        GroupConfig c = GroupSpecAdapter.toGroupConfig(sampleSpec());

        // Identity + templates
        assertEquals("survival", c.name());
        assertEquals("base-game", c.parent());
        assertEquals("PAPER", c.platform());
        assertEquals("1.21.4", c.platformVersion());
        assertEquals("server.jar", c.jarFile());
        assertEquals(List.of("world-data"), c.templates());

        // Scaling (targetUtilization -> scaleUpThreshold; cooldownSeconds -> scaleCooldownSeconds)
        assertEquals("DYNAMIC", c.scalingMode());
        assertEquals(2, c.minInstances());
        assertEquals(8, c.maxInstances());
        assertEquals(80, c.maxPlayers());
        assertEquals(0.75, c.scaleUpThreshold());
        assertEquals(240, c.scaleDownAfterSeconds());
        assertEquals(45, c.scaleCooldownSeconds());

        // Placement
        assertEquals(31000, c.portRangeStart());
        assertEquals(31099, c.portRangeEnd());
        assertEquals(List.of("region=eu"), c.nodeAffinity());
        assertEquals(List.of("gpu=true"), c.nodeAntiAffinity());
        assertEquals("rack", c.spreadConstraint());
        assertEquals(5, c.priority());

        // Lifecycle
        assertEquals(150, c.startupTimeoutSeconds());
        assertEquals(45, c.shutdownGraceSeconds());
        assertEquals(3600, c.maxLifetimeSeconds());

        // Persistence (enabled -> "static")
        assertTrue(c.isStatic());
        assertEquals(List.of("survival-1"), c.staticInstanceNames());
        assertEquals(List.of("world/"), c.protectedPaths());

        // Ops
        assertTrue(c.maintenance());
        assertEquals("patching", c.maintenanceMessage());
        assertEquals(List.of("uuid-1"), c.maintenanceBypass());
        assertEquals("lobby", c.fallbackGroup());
        assertTrue(c.defaultGroup());
        assertEquals(List.of("lobby"), c.dependsOn());
        assertEquals(3, c.startupWeight());
        assertEquals(List.of("Survival!"), c.motds());
        assertEquals("RANDOM", c.motdMode());
        assertEquals(20, c.motdIntervalSeconds());

        // Rollout
        assertEquals("CANARY", c.updateStrategy());

        // Resources
        assertEquals(2048, c.memoryMb());
        assertEquals(1.5, c.cpuReservation());
        assertEquals(4096L, c.diskReservationMb());
        assertEquals(List.of("-XX:+UseZGC"), c.jvmArgs());
        assertEquals(Map.of("MODE", "hard"), c.env());

        // Module policy + config patches
        assertEquals(List.of("stats"), c.attachedModules());
        assertEquals(List.of("debug"), c.disabledModules());
        assertEquals(List.of("ext-a"), c.attachedExtensions());
        assertEquals(Map.of("server.properties", Map.of("difficulty", "hard")), c.configPatches());

        assertEquals("", c.bedrockProxyGroup());
    }

    @Test
    @DisplayName("the resulting config is accepted by GroupConfig's own invariants (uppercasing, defaults)")
    void resultHonoursLegacyInvariants() {
        // lower-case mode/platform must be normalised by GroupConfig's compact constructor.
        GroupSpec spec = new GroupSpec(
                new Identity("g", null, "paper", "", "server.jar"),
                List.of(),
                new ScalingPolicy(ScalingMode.DYNAMIC, 0, 0, 0, List.of(), Aggregation.ALL, 0.0, 1, 0, 0, null, null),
                new Placement(List.of(), List.of(), "", 0, 0, 0),
                new Resources(0, 0, 0, List.of(), Map.of()),
                new Lifecycle(0, 0, false, 0),
                new Persistence(false, List.of(), List.of()),
                new Rollout(UpdateStrategy.ROLLING, 1, true, false),
                new Ops(false, "", List.of(), "", false, List.of(), 0, List.of(), "STATIC", 0),
                new ModulePolicy(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Map.of()),
                List.of(),
                "");

        GroupConfig c = GroupSpecAdapter.toGroupConfig(spec);

        assertEquals("PAPER", c.platform(), "platform uppercased by GroupConfig invariant");
        assertEquals(10, c.maxInstances(), "non-positive maxInstances defaulted to 10");
        assertEquals(100, c.maxPlayers(), "non-positive maxPlayers defaulted to 100");
        assertEquals(30000, c.portRangeStart(), "non-positive port range defaulted");
    }
}
