package me.prexorjustin.prexorcloud.controller.group;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

import me.prexorjustin.prexorcloud.controller.template.TemplateManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("GroupManager")
@ExtendWith(MockitoExtension.class)
class GroupManagerTest {

    @Mock
    TemplateManager templateManager;

    GroupManager manager;

    @BeforeEach
    void setUp() {
        manager = new GroupManager(templateManager);
    }

    @Test
    @DisplayName("create rejects a group whose typed variables fail validation (the 422 write-path gate)")
    void createRejectsInvalidVariables() {
        manager.setVariableValidator(config -> List.of("variable 'x' is invalid"));

        var ex = assertThrows(IllegalArgumentException.class, () -> manager.create(minimal("lobby")));
        assertTrue(ex.getMessage().contains("Invalid group variables"), ex::getMessage);
    }

    @Test
    @DisplayName("create accepts a group when the variable validator reports no problems")
    void createAcceptsValidVariables() {
        manager.setVariableValidator(config -> List.of());

        assertDoesNotThrow(() -> manager.create(minimal("lobby")));
    }

    /**
     * Build a minimal GroupConfig with the given name, no parent, and empty
     * template list.
     */
    private static GroupConfig minimal(String name) {
        return new GroupConfig(
                name,
                null,
                "PAPER",
                "1.21",
                "server.jar",
                List.of(),
                "DYNAMIC",
                1,
                10,
                100,
                0.8,
                300,
                30,
                false,
                0.2,
                0,
                "LOWEST_PLAYERS",
                30000,
                30100,
                120,
                30,
                false,
                0,
                false,
                List.of(),
                List.of(),
                null,
                false,
                List.of(),
                0,
                false,
                "",
                List.of(),
                "ROLLING",
                List.of(),
                List.of(),
                "",
                0,
                1024,
                List.of(),
                Map.of(),
                List.of(),
                "STATIC",
                30,
                List.of(),
                List.of(),
                List.of(),
                Map.of());
    }

    /** Build a GroupConfig with a parent. */
    private static GroupConfig withParent(String name, String parent) {
        return new GroupConfig(
                name,
                parent,
                "PAPER",
                "1.21",
                "server.jar",
                List.of(),
                "DYNAMIC",
                1,
                10,
                100,
                0.8,
                300,
                30,
                false,
                0.2,
                0,
                "LOWEST_PLAYERS",
                30000,
                30100,
                120,
                30,
                false,
                0,
                false,
                List.of(),
                List.of(),
                null,
                false,
                List.of(),
                0,
                false,
                "",
                List.of(),
                "ROLLING",
                List.of(),
                List.of(),
                "",
                0,
                1024,
                List.of(),
                Map.of(),
                List.of(),
                "STATIC",
                30,
                List.of(),
                List.of(),
                List.of(),
                Map.of());
    }

    /** Build a GroupConfig with custom env and maxPlayers for inheritance tests. */
    private static GroupConfig withEnvAndPlayers(String name, String parent, int maxPlayers, Map<String, String> env) {
        return new GroupConfig(
                name,
                parent,
                "PAPER",
                "1.21",
                "server.jar",
                List.of(),
                "DYNAMIC",
                1,
                10,
                maxPlayers,
                0.8,
                300,
                30,
                false,
                0.2,
                0,
                "LOWEST_PLAYERS",
                30000,
                30100,
                120,
                30,
                false,
                0,
                false,
                List.of(),
                List.of(),
                null,
                false,
                List.of(),
                0,
                false,
                "",
                List.of(),
                "ROLLING",
                List.of(),
                List.of(),
                "",
                0,
                1024,
                List.of(),
                env,
                List.of(),
                "STATIC",
                30,
                List.of(),
                List.of(),
                List.of(),
                Map.of());
    }

    private static GroupConfig withReservedField(
            String name,
            boolean predictiveScaling,
            double scaleUpMargin,
            int burstCeiling,
            String routing,
            boolean drainOnShutdown,
            String spreadConstraint,
            int priority) {
        return new GroupConfig(
                name,
                null,
                "PAPER",
                "1.21",
                "server.jar",
                List.of(),
                "DYNAMIC",
                1,
                10,
                100,
                0.8,
                300,
                30,
                predictiveScaling,
                scaleUpMargin,
                burstCeiling,
                routing,
                30000,
                30100,
                120,
                30,
                drainOnShutdown,
                0,
                false,
                List.of(),
                List.of(),
                null,
                false,
                List.of(),
                0,
                false,
                "",
                List.of(),
                "ROLLING",
                List.of(),
                List.of(),
                spreadConstraint,
                priority,
                1024,
                List.of(),
                Map.of(),
                List.of(),
                "STATIC",
                30,
                List.of(),
                List.of(),
                List.of(),
                Map.of());
    }

    private static GroupConfig withScalingPlacement(
            String name,
            String scalingMode,
            double scaleUpThreshold,
            int portRangeStart,
            int portRangeEnd,
            List<String> nodeAffinity,
            List<String> nodeAntiAffinity) {
        return new GroupConfig(
                name,
                null,
                "PAPER",
                "1.21",
                "server.jar",
                List.of(),
                scalingMode,
                1,
                10,
                100,
                scaleUpThreshold,
                300,
                30,
                false,
                0.2,
                0,
                "LOWEST_PLAYERS",
                portRangeStart,
                portRangeEnd,
                120,
                30,
                false,
                0,
                false,
                List.of(),
                List.of(),
                null,
                false,
                List.of(),
                0,
                false,
                "",
                List.of(),
                "ROLLING",
                nodeAffinity,
                nodeAntiAffinity,
                "",
                0,
                1024,
                List.of(),
                Map.of(),
                List.of(),
                "STATIC",
                30,
                List.of(),
                List.of(),
                List.of(),
                Map.of());
    }

    private record DisabledFieldCase(String field, GroupConfig config) {}

    @Nested
    @DisplayName("CRUD")
    class Crud {

        @Test
        @DisplayName("create() adds group, exists() returns true")
        void createAndExists() {
            manager.create(minimal("lobby"));
            assertTrue(manager.exists("lobby"));
        }

        @Test
        @DisplayName("get() returns the created group")
        void getReturnsSaved() {
            manager.create(minimal("game"));
            var result = manager.get("game");
            assertTrue(result.isPresent());
            assertEquals("game", result.get().name());
        }

        @Test
        @DisplayName("get() returns empty for unknown group")
        void getReturnsEmpty() {
            assertTrue(manager.get("unknown").isEmpty());
        }

        @Test
        @DisplayName("getAll() returns all groups")
        void getAllReturnsAll() {
            manager.create(minimal("a"));
            manager.create(minimal("b"));
            assertEquals(2, manager.getAll().size());
        }

        @Test
        @DisplayName("create() throws for duplicate group name")
        void duplicateThrows() {
            manager.create(minimal("lobby"));
            assertThrows(IllegalArgumentException.class, () -> manager.create(minimal("lobby")));
        }

        @Test
        @DisplayName("update() replaces the group config")
        void updateReplaces() {
            manager.create(minimal("lobby"));
            var updated = new GroupConfig(
                    "lobby",
                    null,
                    "PAPER",
                    "1.21",
                    "server.jar",
                    List.of(),
                    "STATIC",
                    2,
                    5,
                    200,
                    0.8,
                    300,
                    30,
                    false,
                    0.2,
                    0,
                    "LOWEST_PLAYERS",
                    30000,
                    30100,
                    120,
                    30,
                    false,
                    0,
                    false,
                    List.of(),
                    List.of(),
                    null,
                    false,
                    List.of(),
                    0,
                    false,
                    "",
                    List.of(),
                    "ROLLING",
                    List.of(),
                    List.of(),
                    "",
                    0,
                    1024,
                    List.of(),
                    Map.of(),
                    List.of(),
                    "STATIC",
                    30,
                    List.of(),
                    List.of(),
                    List.of(),
                    Map.of());
            manager.update(updated);
            assertEquals("STATIC", manager.get("lobby").orElseThrow().scalingMode());
        }

        @Test
        @DisplayName("update() throws for non-existent group")
        void updateNonExistentThrows() {
            assertThrows(IllegalArgumentException.class, () -> manager.update(minimal("ghost")));
        }

        @Test
        @DisplayName("patch() merges partial updates inside GroupManager")
        void patchMergesPartialUpdate() {
            manager.create(withEnvAndPlayers("lobby", null, 100, Map.of("MODE", "survival")));

            GroupConfig merged = manager.patch(
                    "lobby",
                    withEnvAndPlayers("ignored", "ignored-parent", 250, Map.of("DIFFICULTY", "hard")),
                    Set.of("maxPlayers", "env"));

            assertEquals("lobby", merged.name());
            assertNull(merged.parent());
            assertEquals(250, merged.maxPlayers());
            assertEquals("survival", merged.env().get("MODE"));
            assertEquals("hard", merged.env().get("DIFFICULTY"));
        }

        @Test
        @DisplayName("create() rejects conflicting module policy")
        void rejectsConflictingModulePolicy() {
            var config = new GroupConfig(
                    "policy",
                    null,
                    "PAPER",
                    "1.21",
                    "server.jar",
                    List.of(),
                    "DYNAMIC",
                    1,
                    10,
                    100,
                    0.8,
                    300,
                    30,
                    false,
                    0.2,
                    0,
                    "LOWEST_PLAYERS",
                    30000,
                    30100,
                    120,
                    30,
                    false,
                    0,
                    false,
                    List.of(),
                    List.of(),
                    null,
                    false,
                    List.of(),
                    0,
                    false,
                    "",
                    List.of(),
                    "ROLLING",
                    List.of(),
                    List.of(),
                    "",
                    0,
                    1024,
                    List.of(),
                    Map.of(),
                    List.of(),
                    "STATIC",
                    30,
                    List.of("motd-module"),
                    List.of(),
                    List.of("motd-module"),
                    List.of(),
                    List.of(),
                    List.of(),
                    Map.of());

            assertThrows(IllegalArgumentException.class, () -> manager.create(config));
        }

        @Test
        @DisplayName("delete() removes the group")
        void deleteRemoves() {
            manager.create(minimal("tmp"));
            manager.delete("tmp");
            assertFalse(manager.exists("tmp"));
        }

        @Test
        @DisplayName("exists() returns false before creation")
        void existsFalseBeforeCreate() {
            assertFalse(manager.exists("nope"));
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("create() silently ignores legacy reserved fields dropped under Phase 45")
        void ignoresLegacyDroppedFields() {
            // predictiveScaling, scaleUpMargin, burstCeiling, routing, drainOnShutdown
            // were dropped from the public contract under Phase 45 (M0). Setting them
            // on a fresh group must no longer reject the create.
            assertDoesNotThrow(
                    () -> manager.create(withReservedField("predictive", true, 0.5, 2, "ROUND_ROBIN", true, "", 0)));
        }

        @Test
        @DisplayName("create() accepts implemented placement fields priority and spreadConstraint")
        void acceptsPriorityAndSpreadConstraint() {
            assertDoesNotThrow(
                    () -> manager.create(withReservedField("p1", false, 0.2, 0, "LOWEST_PLAYERS", false, "zone", 10)));
            assertDoesNotThrow(
                    () -> manager.create(withReservedField("p2", false, 0.2, 0, "LOWEST_PLAYERS", false, "rack=a", 0)));
        }

        @Test
        @DisplayName("create() rejects negative priority")
        void rejectsNegativePriority() {
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.create(withReservedField("neg", false, 0.2, 0, "LOWEST_PLAYERS", false, "", -1)));
            assertTrue(ex.getMessage().contains("priority"), ex.getMessage());
        }

        @Test
        @DisplayName("create() rejects malformed spreadConstraint")
        void rejectsMalformedSpreadConstraint() {
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.create(withReservedField("bad", false, 0.2, 0, "LOWEST_PLAYERS", false, "=zone", 0)));
            assertTrue(ex.getMessage().contains("spreadConstraint"), ex.getMessage());
        }

        @Test
        @DisplayName("create() rejects invalid scaling and placement constraints")
        void rejectsInvalidScalingAndPlacementConstraints() {
            var cases = List.of(
                    new DisabledFieldCase(
                            "scalingMode",
                            withScalingPlacement("bad-mode", "ELASTIC", 0.8, 30000, 30100, List.of(), List.of())),
                    new DisabledFieldCase(
                            "scaleUpThreshold",
                            withScalingPlacement("bad-threshold", "DYNAMIC", 80, 30000, 30100, List.of(), List.of())),
                    new DisabledFieldCase(
                            "portRangeStart/portRangeEnd",
                            withScalingPlacement("bad-port", "DYNAMIC", 0.8, 30000, 70000, List.of(), List.of())),
                    new DisabledFieldCase(
                            "nodeAffinity",
                            withScalingPlacement("bad-affinity", "DYNAMIC", 0.8, 30000, 30100, List.of(""), List.of())),
                    new DisabledFieldCase(
                            "nodeAntiAffinity",
                            withScalingPlacement(
                                    "bad-anti-affinity", "DYNAMIC", 0.8, 30000, 30100, List.of(), List.of("zone="))),
                    new DisabledFieldCase(
                            "both nodeAffinity and nodeAntiAffinity",
                            withScalingPlacement(
                                    "conflicting-affinity",
                                    "DYNAMIC",
                                    0.8,
                                    30000,
                                    30100,
                                    List.of("zone=blue"),
                                    List.of("zone=blue"))));

            for (DisabledFieldCase fieldCase : cases) {
                IllegalArgumentException ex =
                        assertThrows(IllegalArgumentException.class, () -> manager.create(fieldCase.config()));
                assertTrue(ex.getMessage().contains(fieldCase.field()), ex.getMessage());
            }
        }
    }

    @Nested
    @DisplayName("Inheritance")
    class Inheritance {

        @Test
        @DisplayName("resolveGroup() with no parent returns the group unchanged")
        void noParentReturnsSelf() {
            manager.create(minimal("hub"));
            var resolved = manager.resolveGroup("hub");
            assertEquals("hub", resolved.name());
            assertEquals(100, resolved.maxPlayers());
        }

        @Test
        @DisplayName("resolveGroup() applies parent defaults")
        void parentDefaultsApplied() {
            manager.create(withEnvAndPlayers("base", null, 50, Map.of("MODE", "survival")));
            manager.create(withParent("child", "base"));

            var resolved = manager.resolveGroup("child");
            // child inherits maxPlayers from parent when child uses 100 (its default)
            // env from parent should be present (child has empty env, parent has
            // MODE=survival)
            assertEquals("MODE", resolved.env().containsKey("MODE") ? "MODE" : null);
        }

        @Test
        @DisplayName("resolveGroup() child env overrides parent env")
        void childEnvOverridesParent() {
            manager.create(withEnvAndPlayers("parent", null, 50, Map.of("MODE", "survival", "DIFFICULTY", "easy")));
            manager.create(withEnvAndPlayers("child", "parent", 50, Map.of("MODE", "creative")));

            var resolved = manager.resolveGroup("child");
            assertEquals("creative", resolved.env().get("MODE"));
            assertEquals("easy", resolved.env().get("DIFFICULTY"));
        }

        @Test
        @DisplayName("resolveGroup() child name takes precedence over parent name")
        void childNamePreserved() {
            manager.create(minimal("parent"));
            manager.create(withParent("child", "parent"));
            var resolved = manager.resolveGroup("child");
            assertEquals("child", resolved.name());
        }

        @Test
        @DisplayName("Circular inheritance is rejected at update time")
        void circularInheritanceRejected() {
            manager.create(minimal("a"));
            manager.create(withParent("b", "a")); // b → a
            // Updating a to have parent b creates: a → b → a (cycle)
            assertThrows(IllegalArgumentException.class, () -> manager.update(withParent("a", "b")));
        }

        @Test
        @DisplayName("resolveGroup() throws for unknown group")
        void unknownGroupThrows() {
            assertThrows(IllegalArgumentException.class, () -> manager.resolveGroup("ghost"));
        }

        @Test
        @DisplayName("resolveGroup() merges config patches and inherits extension policy")
        void inheritsConfigPatchAndExtensionPolicy() {
            manager.create(new GroupConfig(
                    "parent",
                    null,
                    "PAPER",
                    "1.21",
                    "server.jar",
                    List.of(),
                    "DYNAMIC",
                    1,
                    10,
                    100,
                    0.8,
                    300,
                    30,
                    false,
                    0.2,
                    0,
                    "LOWEST_PLAYERS",
                    30000,
                    30100,
                    120,
                    30,
                    false,
                    0,
                    false,
                    List.of(),
                    List.of(),
                    null,
                    false,
                    List.of(),
                    0,
                    false,
                    "",
                    List.of(),
                    "ROLLING",
                    List.of(),
                    List.of(),
                    "",
                    0,
                    1024,
                    List.of(),
                    Map.of(),
                    List.of(),
                    "STATIC",
                    30,
                    List.of("motd-paper"),
                    List.of("tab-paper"),
                    List.of(),
                    Map.of("server.properties", Map.of("motd", "Parent MOTD"))));
            manager.create(new GroupConfig(
                    "child",
                    "parent",
                    "PAPER",
                    "1.21",
                    "server.jar",
                    List.of(),
                    "DYNAMIC",
                    1,
                    10,
                    100,
                    0.8,
                    300,
                    30,
                    false,
                    0.2,
                    0,
                    "LOWEST_PLAYERS",
                    30000,
                    30100,
                    120,
                    30,
                    false,
                    0,
                    false,
                    List.of(),
                    List.of(),
                    null,
                    false,
                    List.of(),
                    0,
                    false,
                    "",
                    List.of(),
                    "ROLLING",
                    List.of(),
                    List.of(),
                    "",
                    0,
                    1024,
                    List.of(),
                    Map.of(),
                    List.of(),
                    "STATIC",
                    30,
                    List.of(),
                    List.of(),
                    List.of("debug-paper"),
                    Map.of("server.properties", Map.of("max-players", "200"))));

            GroupConfig resolved = manager.resolveGroup("child");

            assertEquals(List.of("motd-paper"), resolved.attachedExtensions());
            assertEquals(List.of("tab-paper"), resolved.enabledExtensions());
            assertEquals(List.of("debug-paper"), resolved.disabledExtensions());
            assertEquals(
                    "Parent MOTD",
                    resolved.configPatches().get("server.properties").get("motd"));
            assertEquals(
                    "200", resolved.configPatches().get("server.properties").get("max-players"));
        }

        @Test
        @DisplayName("create() rejects enabled extensions that are also disabled")
        void rejectsEnabledAndDisabledExtensionConflict() {
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.create(new GroupConfig(
                            "policy-conflict",
                            null,
                            "PAPER",
                            "1.21",
                            "server.jar",
                            List.of(),
                            "DYNAMIC",
                            1,
                            10,
                            100,
                            0.8,
                            300,
                            30,
                            false,
                            0.2,
                            0,
                            "LOWEST_PLAYERS",
                            30000,
                            30100,
                            120,
                            30,
                            false,
                            0,
                            false,
                            List.of(),
                            List.of(),
                            null,
                            false,
                            List.of(),
                            0,
                            false,
                            "",
                            List.of(),
                            "ROLLING",
                            List.of(),
                            List.of(),
                            "",
                            0,
                            1024,
                            List.of(),
                            Map.of(),
                            List.of(),
                            "STATIC",
                            30,
                            List.of(),
                            List.of("motd-paper"),
                            List.of("motd-paper"),
                            Map.of())));

            assertTrue(ex.getMessage().contains("both enabled and disabled"));
        }

        @Test
        @DisplayName("proxy groups cannot inherit from server runtime chains")
        void rejectsRuntimeFamilyMismatchAcrossParents() {
            manager.create(minimal("paper-base"));

            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.create(new GroupConfig(
                            "proxy-child",
                            "paper-base",
                            "VELOCITY",
                            "3.3.0",
                            "proxy.jar",
                            List.of(),
                            "DYNAMIC",
                            1,
                            10,
                            100,
                            0.8,
                            300,
                            30,
                            false,
                            0.2,
                            0,
                            "LOWEST_PLAYERS",
                            30000,
                            30100,
                            120,
                            30,
                            false,
                            0,
                            false,
                            List.of(),
                            List.of(),
                            null,
                            false,
                            List.of(),
                            0,
                            false,
                            "",
                            List.of(),
                            "ROLLING",
                            List.of(),
                            List.of(),
                            "",
                            0,
                            1024,
                            List.of(),
                            Map.of(),
                            List.of(),
                            "STATIC",
                            30,
                            List.of(),
                            List.of(),
                            List.of(),
                            Map.of())));

            assertTrue(ex.getMessage().contains("runtime family"));
        }
    }
}
