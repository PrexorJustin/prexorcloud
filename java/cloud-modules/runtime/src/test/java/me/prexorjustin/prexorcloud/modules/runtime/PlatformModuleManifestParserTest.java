package me.prexorjustin.prexorcloud.modules.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import me.prexorjustin.prexorcloud.api.module.platform.ActivationPolicy;
import me.prexorjustin.prexorcloud.api.module.platform.PlatformModuleManifest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("PlatformModuleManifestParser")
class PlatformModuleManifestParserTest {

    private static final String FULL = """
            id: matchmaking
            version: 2.0.0
            backend:
              entrypoint: me.prexorjustin.prexorcloud.matchmaking.MatchmakingModule
            frontend:
              sdkVersion: 1
              entry: frontend/index.js
            capabilities:
              provides:
                - id: matchmaking-queue
                  version: 1.0.0
              requires:
                - id: player-profile
                  versionRange: "[1.0,2.0)"
            storage:
              mongo: true
              redis: true
              limits:
                mongoDocuments: 1000
                redisKeys: 200
            extensions:
              - id: matchmaking-paper
                target: server/paper
                activation: explicit-group-attach
                conflicts:
                  - legacy-paper
                variants:
                  - id: paper-1-20
                    mcVersionRange: "[1.20,1.21)"
                    runtimeApiVersion: 1
                    artifact: extensions/paper/matchmaking-paper-1.20.jar
                    sha256: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
                    installPath: plugins/
                  - id: paper-1-21
                    mcVersionRange: "[1.21,1.22)"
                    runtimeApiVersion: 1
                    artifact: extensions/paper/matchmaking-paper-1.21.jar
                    sha256: bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
                    installPath: plugins/
              - id: matchmaking-velocity
                target: proxy/velocity
                activation: explicit-group-attach
                variants:
                  - id: velocity-universal
                    mcVersionRange: "*"
                    runtimeApiVersion: 1
                    artifact: extensions/velocity/matchmaking-velocity.jar
                    sha256: cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc
                    installPath: plugins/
            """;

    private static PlatformModuleManifest parse(String yaml) {
        return PlatformModuleManifestParser.parse(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "test-module.jar");
    }

    @Test
    @DisplayName("parses a full manifest")
    void parsesFullManifest() {
        PlatformModuleManifest manifest = parse(FULL);
        assertEquals(PlatformModuleManifest.CURRENT_MANIFEST_VERSION, manifest.manifestVersion());
        assertEquals("matchmaking", manifest.id());
        assertEquals("2.0.0", manifest.version());
        assertEquals(
                "me.prexorjustin.prexorcloud.matchmaking.MatchmakingModule",
                manifest.backend().controller().entrypoint());
        assertEquals(1, manifest.frontend().sdkVersion());
        assertEquals("frontend/index.js", manifest.frontend().entry());
        assertEquals(1, manifest.capabilities().provides().size());
        assertEquals(
                "matchmaking-queue",
                manifest.capabilities().provides().getFirst().id());
        assertEquals("1.0.0", manifest.capabilities().provides().getFirst().version());
        assertEquals(1, manifest.capabilities().requires().size());
        assertEquals(
                "player-profile", manifest.capabilities().requires().getFirst().id());
        assertEquals("[1.0,2.0)", manifest.capabilities().requires().getFirst().versionRange());
        assertTrue(manifest.storage().mongo());
        assertTrue(manifest.storage().redis());
        assertEquals(1000, manifest.storage().limits().mongoDocuments());
        assertEquals(200, manifest.storage().limits().redisKeys());
        assertEquals(2, manifest.extensions().size());
        assertEquals("matchmaking-paper", manifest.extensions().get(0).id());
        assertEquals(
                ActivationPolicy.EXPLICIT_GROUP_ATTACH,
                manifest.extensions().get(0).activationPolicy());
        assertEquals("server/paper", manifest.extensions().get(0).target().wireValue());
        assertEquals(2, manifest.extensions().get(0).variants().size());
    }

    @Test
    @DisplayName("minimal manifest defaults manifestVersion and optional sections")
    void defaultsMinimalManifest() {
        PlatformModuleManifest manifest = parse("""
                id: bare
                version: 1.0.0
                backend:
                  entrypoint: com.example.BareModule
                """);
        assertEquals(PlatformModuleManifest.CURRENT_MANIFEST_VERSION, manifest.manifestVersion());
        assertTrue(manifest.capabilities().isEmpty());
        assertTrue(manifest.storage().isEmpty());
        assertTrue(manifest.extensions().isEmpty());
        assertNull(manifest.frontend());
    }

    @Nested
    @DisplayName("rejects")
    class Rejects {

        @Test
        @DisplayName("unknown root field")
        void unknownRootField() {
            PlatformModuleManifestException ex =
                    assertThrows(PlatformModuleManifestException.class, () -> parse(FULL + "wat: true\n"));
            assertTrue(ex.getMessage().contains("unknown field"));
        }

        @Test
        @DisplayName("bad runtime target")
        void badRuntimeTarget() {
            PlatformModuleManifestException ex = assertThrows(
                    PlatformModuleManifestException.class, () -> parse(FULL.replace("server/paper", "paper")));
            assertTrue(ex.getMessage().contains("target"));
        }

        @Test
        @DisplayName("duplicate extension id")
        void duplicateExtensionId() {
            String broken = FULL.replace("matchmaking-velocity", "matchmaking-paper");
            PlatformModuleManifestException ex =
                    assertThrows(PlatformModuleManifestException.class, () -> parse(broken));
            assertTrue(ex.getMessage().contains("duplicate extension id"));
        }

        @Test
        @DisplayName("invalid variant range")
        void invalidVariantRange() {
            String broken = FULL.replace("\"[1.20,1.21)\"", "\"not-a-range\"");
            PlatformModuleManifestException ex =
                    assertThrows(PlatformModuleManifestException.class, () -> parse(broken));
            assertTrue(ex.getMessage().contains("valid range"));
        }

        @Test
        @DisplayName("path traversal in artifact")
        void pathTraversalArtifact() {
            String broken =
                    FULL.replace("extensions/paper/matchmaking-paper-1.20.jar", "../matchmaking-paper-1.20.jar");
            PlatformModuleManifestException ex =
                    assertThrows(PlatformModuleManifestException.class, () -> parse(broken));
            assertTrue(ex.getMessage().contains("relative path"));
        }

        @Test
        @DisplayName("bad sha256")
        void badSha256() {
            String broken =
                    FULL.replace("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "not-a-sha");
            PlatformModuleManifestException ex =
                    assertThrows(PlatformModuleManifestException.class, () -> parse(broken));
            assertTrue(ex.getMessage().contains("sha256"));
        }

        @Test
        @DisplayName("duplicate provided capability id")
        void duplicateProvidedCapabilityId() {
            String broken = FULL.replace("""
              provides:
                - id: matchmaking-queue
                  version: 1.0.0
              requires:
                - id: player-profile
                  versionRange: "[1.0,2.0)"
            """, """
              provides:
                - id: matchmaking-queue
                  version: 1.0.0
                - id: matchmaking-queue
                  version: 1.1.0
              requires:
                - id: player-profile
                  versionRange: "[1.0,2.0)"
            """);
            PlatformModuleManifestException ex =
                    assertThrows(PlatformModuleManifestException.class, () -> parse(broken));
            assertTrue(ex.getMessage().contains("more than once"));
        }

        @Test
        @DisplayName("unsupported frontend SDK version")
        void unsupportedFrontendSdkVersion() {
            String broken = FULL.replace("sdkVersion: 1", "sdkVersion: 2");
            PlatformModuleManifestException ex =
                    assertThrows(PlatformModuleManifestException.class, () -> parse(broken));
            assertTrue(ex.getMessage().contains("frontend.sdkVersion"));
            assertTrue(ex.getMessage().contains("supported"));
        }

        @Test
        @DisplayName("non-positive frontend SDK version")
        void nonPositiveFrontendSdkVersion() {
            String broken = FULL.replace("sdkVersion: 1", "sdkVersion: 0");
            PlatformModuleManifestException ex =
                    assertThrows(PlatformModuleManifestException.class, () -> parse(broken));
            assertTrue(ex.getMessage().contains("frontend.sdkVersion"));
        }

        @Test
        @DisplayName("invalid storage flag type")
        void invalidStorageFlagType() {
            String broken = FULL.replace("mongo: true", "mongo: \"yes\"");
            PlatformModuleManifestException ex =
                    assertThrows(PlatformModuleManifestException.class, () -> parse(broken));
            assertTrue(ex.getMessage().contains("storage.mongo"));
        }

        @Test
        @DisplayName("unknown storage limit field")
        void unknownStorageLimitField() {
            String broken = FULL.replace("redisKeys: 200", "redisBytes: 200");
            PlatformModuleManifestException ex =
                    assertThrows(PlatformModuleManifestException.class, () -> parse(broken));
            assertTrue(ex.getMessage().contains("storage.limits"));
        }

        @Test
        @DisplayName("non-positive storage limit")
        void nonPositiveStorageLimit() {
            String broken = FULL.replace("mongoDocuments: 1000", "mongoDocuments: 0");
            PlatformModuleManifestException ex =
                    assertThrows(PlatformModuleManifestException.class, () -> parse(broken));
            assertTrue(ex.getMessage().contains("storage.limits.mongoDocuments"));
        }

        @Test
        @DisplayName("storage limit for disabled backend")
        void storageLimitForDisabledBackend() {
            String broken = FULL.replace("redis: true", "redis: false");
            PlatformModuleManifestException ex =
                    assertThrows(PlatformModuleManifestException.class, () -> parse(broken));
            assertTrue(ex.getMessage().contains("storage.limits.redisKeys"));
        }

        @Test
        @DisplayName("deprecatedSince on a v1 manifest (field is v2-only)")
        void deprecatedSinceOnV1Manifest() {
            String broken = """
                    manifestVersion: 1
                    id: legacy
                    version: 1.0.0
                    backend:
                      entrypoint: com.example.Legacy
                    capabilities:
                      provides:
                        - id: cap
                          version: 1.0.0
                          deprecatedSince: 1.0.0
                    """;
            PlatformModuleManifestException ex =
                    assertThrows(PlatformModuleManifestException.class, () -> parse(broken));
            assertTrue(ex.getMessage().contains("deprecatedSince"));
            assertTrue(ex.getMessage().contains("unknown field"));
        }

        @Test
        @DisplayName("unsupported manifestVersion 3")
        void unsupportedManifestVersion() {
            String broken = """
                    manifestVersion: 3
                    id: future
                    version: 1.0.0
                    backend:
                      entrypoint: com.example.Future
                    """;
            PlatformModuleManifestException ex =
                    assertThrows(PlatformModuleManifestException.class, () -> parse(broken));
            assertTrue(ex.getMessage().contains("manifestVersion"));
            assertTrue(ex.getMessage().contains("supported"));
        }

        @Test
        @DisplayName("removedIn without deprecatedSince")
        void removedInWithoutDeprecatedSince() {
            String broken = """
                    manifestVersion: 2
                    id: mod
                    version: 1.0.0
                    backend:
                      entrypoint: com.example.M
                    capabilities:
                      provides:
                        - id: cap
                          version: 1.0.0
                          removedIn: 2.0.0
                    """;
            PlatformModuleManifestException ex =
                    assertThrows(PlatformModuleManifestException.class, () -> parse(broken));
            assertTrue(ex.getMessage().contains("removedIn"));
            assertTrue(ex.getMessage().contains("deprecatedSince"));
        }
    }

    @Nested
    @DisplayName("manifestVersion 2")
    class ManifestVersion2 {

        @Test
        @DisplayName("parses deprecation fields on capability provides")
        void parsesDeprecationFields() {
            PlatformModuleManifest manifest = parse("""
                    manifestVersion: 2
                    id: profiles
                    version: 2.5.0
                    backend:
                      entrypoint: com.example.Profiles
                    capabilities:
                      provides:
                        - id: player-profile
                          version: 1.4.0
                          deprecatedSince: 1.3.0
                          removedIn: 2.0.0
                        - id: player-stats
                          version: 1.0.0
                    """);
            assertEquals(2, manifest.manifestVersion());
            var deprecated = manifest.capabilities().provides().get(0);
            assertEquals("player-profile", deprecated.id());
            assertEquals("1.3.0", deprecated.deprecatedSince());
            assertEquals("2.0.0", deprecated.removedIn());
            assertTrue(deprecated.isDeprecated());

            var live = manifest.capabilities().provides().get(1);
            assertNull(live.deprecatedSince());
            assertNull(live.removedIn());
            assertFalse(live.isDeprecated());
        }

        @Test
        @DisplayName("v1 manifest still parses cleanly")
        void v1StillParses() {
            PlatformModuleManifest manifest = parse("""
                    manifestVersion: 1
                    id: legacy
                    version: 1.0.0
                    backend:
                      entrypoint: com.example.Legacy
                    capabilities:
                      provides:
                        - id: cap
                          version: 1.0.0
                    """);
            assertEquals(1, manifest.manifestVersion());
            assertFalse(manifest.capabilities().provides().getFirst().isDeprecated());
        }

        @Test
        @DisplayName("parses the reloadable flag on a controller entrypoint")
        void parsesReloadableFlag() {
            PlatformModuleManifest manifest = parse("""
                    manifestVersion: 2
                    id: chat
                    version: 1.0.0
                    backend:
                      controller:
                        entrypoint: com.example.Chat
                        reloadable: true
                    """);
            assertTrue(manifest.backend().controller().reloadable());
        }

        @Test
        @DisplayName("reloadable defaults to false when omitted")
        void reloadableDefaultsFalse() {
            PlatformModuleManifest objectForm = parse("""
                    manifestVersion: 2
                    id: chat
                    version: 1.0.0
                    backend:
                      controller:
                        entrypoint: com.example.Chat
                    """);
            assertFalse(objectForm.backend().controller().reloadable());

            PlatformModuleManifest legacyForm = parse("""
                    manifestVersion: 2
                    id: chat
                    version: 1.0.0
                    backend:
                      entrypoint: com.example.Chat
                    """);
            assertFalse(legacyForm.backend().controller().reloadable());
        }

        @Test
        @DisplayName("rejects the reloadable flag on a v1 manifest")
        void rejectsReloadableOnV1() {
            PlatformModuleManifestException ex = assertThrows(PlatformModuleManifestException.class, () -> parse("""
                            manifestVersion: 1
                            id: chat
                            version: 1.0.0
                            backend:
                              controller:
                                entrypoint: com.example.Chat
                                reloadable: true
                            """));
            assertTrue(ex.getMessage().contains("reloadable"));
        }
    }
}
