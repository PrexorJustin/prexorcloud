package me.prexorjustin.prexorcloud.controller.module.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("ModuleRegistryClient")
class ModuleRegistryClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REG1 = "https://reg1.example/index.json";
    private static final String REG2 = "https://reg2.example/index.json";
    private static final String BAD = "https://bad.example/index.json";

    private final Map<URI, byte[]> served = new HashMap<>();
    private final byte[] fooJar = "FOO-JAR-BYTES".getBytes(StandardCharsets.UTF_8);
    private final byte[] fooBundle = "FOO-COSIGN-BUNDLE".getBytes(StandardCharsets.UTF_8);

    private RegistryFetcher fetcher() {
        return uri -> {
            byte[] b = served.get(uri);
            if (b == null) {
                throw new IOException("404 " + uri);
            }
            return b;
        };
    }

    private ModuleRegistryClient client(String... registries) {
        return new ModuleRegistryClient(List.of(registries), fetcher(), MAPPER);
    }

    @BeforeEach
    void setup() throws Exception {
        var reg1 = new RegistryIndex(
                "reg1",
                1,
                List.of(
                        entry("foo", "1.0.0", "https://reg1.example/foo-1.0.0.jar", sha256(fooJar), List.of()),
                        entry("foo", "1.2.0", "https://reg1.example/foo-1.2.0.jar", sha256(fooJar), List.of()),
                        entry("bar", "2.0.0", "https://reg1.example/bar.jar", sha256(fooJar), List.of("util"))));
        var reg2 = new RegistryIndex(
                "reg2", 1, List.of(entry("baz", "0.1.0", "https://reg2.example/baz.jar", sha256(fooJar), List.of())));

        served.put(URI.create(REG1), MAPPER.writeValueAsBytes(reg1));
        served.put(URI.create(REG2), MAPPER.writeValueAsBytes(reg2));
        served.put(URI.create(BAD), "this is not json".getBytes(StandardCharsets.UTF_8));
        served.put(URI.create("https://reg1.example/foo-1.0.0.jar"), fooJar);
        served.put(URI.create("https://reg1.example/foo.cosign.bundle"), fooBundle);
    }

    private static RegistryModuleEntry entry(String id, String version, String jarUrl, String sha, List<String> tags) {
        return new RegistryModuleEntry(
                id, version, jarUrl, sha, "https://reg1.example/foo.cosign.bundle", null, List.of(), tags, "readme");
    }

    @Test
    @DisplayName("aggregate merges all registries and skips a broken index")
    void aggregateSkipsBroken() {
        var all = client(REG1, REG2, BAD).aggregate();
        assertEquals(4, all.size(), "3 from reg1 + 1 from reg2, bad skipped");
        assertTrue(all.stream().anyMatch(r -> r.registryName().equals("reg2")));
    }

    @Test
    @DisplayName("search matches moduleId and tags, case-insensitively")
    void searchMatches() {
        var byId = client(REG1, REG2).search("FOO");
        assertEquals(2, byId.size());
        var byTag = client(REG1, REG2).search("util");
        assertEquals(1, byTag.size());
        assertEquals("bar", byTag.get(0).entry().moduleId());
    }

    @Test
    @DisplayName("resolve picks the highest semver for latest")
    void resolveLatest() {
        var r = client(REG1).resolve("foo", null, null);
        assertEquals("1.2.0", r.entry().version());
        var explicitLatest = client(REG1).resolve("foo", "latest", null);
        assertEquals("1.2.0", explicitLatest.entry().version());
    }

    @Test
    @DisplayName("resolve returns the exact version when asked")
    void resolveExact() {
        assertEquals("1.0.0", client(REG1).resolve("foo", "1.0.0", null).entry().version());
    }

    @Test
    @DisplayName("resolve throws MODULE_NOT_FOUND for unknown id/version")
    void resolveUnknown() {
        var ex = assertThrows(ModuleRegistryException.class, () -> client(REG1).resolve("nope", null, null));
        assertEquals("MODULE_NOT_FOUND", ex.code());
        var exVer =
                assertThrows(ModuleRegistryException.class, () -> client(REG1).resolve("foo", "9.9.9", null));
        assertEquals("MODULE_NOT_FOUND", exVer.code());
    }

    @Test
    @DisplayName("download writes the jar + cosign sidecar and verifies sha256")
    void downloadVerifies(@TempDir Path dir) throws Exception {
        var entry = client(REG1).resolve("foo", "1.0.0", null).entry();
        Path jar = client(REG1).download(entry, dir);

        assertTrue(Files.exists(jar));
        assertEquals("foo.jar", jar.getFileName().toString());
        assertEquals(new String(fooJar, StandardCharsets.UTF_8), Files.readString(jar));
        assertTrue(Files.exists(jar.resolveSibling("foo.jar.cosign.bundle")));
    }

    @Test
    @DisplayName("download rejects a sha256 mismatch")
    void downloadShaMismatch(@TempDir Path dir) {
        var tampered = new RegistryModuleEntry(
                "foo",
                "1.0.0",
                "https://reg1.example/foo-1.0.0.jar",
                "deadbeef", // wrong
                "https://reg1.example/foo.cosign.bundle",
                null,
                List.of(),
                List.of(),
                null);
        var ex = assertThrows(ModuleRegistryException.class, () -> client(REG1).download(tampered, dir));
        assertEquals("SHA256_MISMATCH", ex.code());
    }

    @Test
    @DisplayName("provides round-trips through the index JSON for dependency-resolution hints")
    void providesRoundTrips() throws Exception {
        var withProvides = new RegistryModuleEntry(
                "journey",
                "1.0.0",
                "https://reg2.example/journey.jar",
                sha256(fooJar),
                "https://reg1.example/foo.cosign.bundle",
                null,
                List.of(),
                List.of(),
                "readme",
                List.of(new RegistryModuleEntry.Capability("prexor.player.journey", "1.0.0")));
        served.put(URI.create(REG2), MAPPER.writeValueAsBytes(new RegistryIndex("reg2", 1, List.of(withProvides))));

        var resolved = client(REG2).resolve("journey", null, null);
        assertEquals(1, resolved.entry().provides().size());
        assertEquals("prexor.player.journey", resolved.entry().provides().get(0).id());
        assertEquals("1.0.0", resolved.entry().provides().get(0).version());
    }

    @Test
    @DisplayName("an index entry without provides parses to an empty list, never null")
    void providesDefaultsEmpty() {
        var entry = client(REG1).resolve("bar", null, null).entry();
        assertTrue(entry.provides().isEmpty());
    }

    @Test
    @DisplayName("compareSemver orders numerically, not lexically")
    void semver() {
        assertTrue(ModuleRegistryClient.compareSemver("1.2.10", "1.2.9") > 0);
        assertTrue(ModuleRegistryClient.compareSemver("1.0.0", "1.0.1") < 0);
        assertEquals(0, ModuleRegistryClient.compareSemver("2.0.0", "2.0.0"));
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
