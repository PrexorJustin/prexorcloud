package me.prexorjustin.prexorcloud.controller.rest.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import me.prexorjustin.prexorcloud.controller.catalog.CatalogConfig;
import me.prexorjustin.prexorcloud.controller.catalog.CatalogConfigLoader;
import me.prexorjustin.prexorcloud.controller.catalog.CatalogStore;
import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.group.GroupConfig;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.controller.state.InstanceInfo;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("GroupDtoMapper")
class GroupDtoMapperTest {

    @Test
    @DisplayName("maps runtime target, extension policy, and computed counters")
    void mapsGroupDtoFields() {
        ClusterState clusterState = new ClusterState(new EventBus());
        clusterState.addInstance(
                new InstanceInfo("lobby-1", "lobby", "node-a", InstanceState.RUNNING, 25565, 12, 0, Instant.now()));
        clusterState.addInstance(
                new InstanceInfo("lobby-2", "lobby", "node-b", InstanceState.RUNNING, 25566, 8, 0, Instant.now()));

        GroupConfig group = new GroupConfig(
                "lobby",
                null,
                "PAPER",
                "1.21.4",
                "server.jar",
                List.of("motd"),
                "DYNAMIC",
                1,
                3,
                100,
                0.8,
                300,
                60,
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
                List.of("-XX:+UseG1GC"),
                Map.of("EXAMPLE_FLAG", "1"),
                List.of("Lobby"),
                "STATIC",
                30,
                List.of("motd-module"),
                List.of("chat-module"),
                List.of("debug-module"),
                List.of("motd-paper"),
                List.of("chat-paper"),
                List.of("debug-paper"),
                Map.of("server.properties", Map.of("motd", "Lobby")));

        Map<String, Object> dto = GroupDtoMapper.toDto(group, clusterState);

        assertEquals(
                Map.of("platform", "PAPER", "platformVersion", "1.21.4", "family", "SERVER"), dto.get("runtimeTarget"));
        assertEquals(List.of("motd-module"), dto.get("attachedModules"));
        assertEquals(List.of("chat-module"), dto.get("enabledModules"));
        assertEquals(List.of("debug-module"), dto.get("disabledModules"));
        assertEquals(List.of("motd-paper"), dto.get("attachedExtensions"));
        assertEquals(List.of("chat-paper"), dto.get("enabledExtensions"));
        assertEquals(List.of("debug-paper"), dto.get("disabledExtensions"));
        assertEquals(0.0, dto.get("cpuReservation"));
        assertEquals(0L, dto.get("diskReservationMb"));
        assertEquals(2, dto.get("runningInstances"));
        assertEquals(20, dto.get("totalPlayers"));
    }

    @Test
    @DisplayName("normalizes runtime target version through the catalog when available")
    void normalizesRuntimeTargetFromCatalog() {
        GroupConfig group = new GroupConfig(
                "proxy",
                null,
                "VELOCITY",
                "",
                "proxy.jar",
                List.of(),
                "DYNAMIC",
                1,
                2,
                200,
                0.8,
                300,
                60,
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

        Map<String, Object> dto = GroupDtoMapper.toDto(
                group,
                new ClusterState(new EventBus()),
                new StaticCatalogStore(List.of(new CatalogConfigLoader.CatalogEntry(
                        "VELOCITY",
                        "PROXY",
                        "VELOCITY",
                        "3.4.0",
                        "https://example.invalid/velocity.jar",
                        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                        true))));

        assertEquals(
                Map.of("platform", "VELOCITY", "platformVersion", "3.4.0", "family", "PROXY"),
                dto.get("runtimeTarget"));
    }

    private record StaticCatalogStore(List<CatalogConfigLoader.CatalogEntry> entries) implements CatalogStore {
        @Override
        public List<CatalogConfigLoader.CatalogEntry> getAll() {
            return entries;
        }

        @Override
        public List<CatalogConfigLoader.CatalogEntry> getByPlatform(String platform) {
            return entries.stream()
                    .filter(entry -> entry.platform().equals(platform))
                    .toList();
        }

        @Override
        public java.util.Optional<CatalogConfig.PlatformCatalog> getPlatform(String platform) {
            return java.util.Optional.empty();
        }

        @Override
        public boolean addEntry(
                String platform,
                String category,
                String configFormat,
                String version,
                String downloadUrl,
                String sha256) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateEntry(
                String platform, String oldVersion, String newVersion, String downloadUrl, String sha256) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeEntry(String platform, String version) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setRecommended(String platform, String version) {
            throw new UnsupportedOperationException();
        }
    }
}
