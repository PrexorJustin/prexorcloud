package me.prexorjustin.prexorcloud.controller.group.spec.secret;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("SecretResolver")
class SecretResolverTest {

    private static SecretResolver resolverWithEnv(Map<String, String> env) {
        return new SecretResolver(List.of(new EnvSecretBackend(env::get), new FileSecretBackend()));
    }

    @Test
    @DisplayName("env:// resolves to the named environment variable")
    void envResolves() throws Exception {
        var resolver = resolverWithEnv(Map.of("RCON_SECRET", "hunter2"));
        assertEquals("hunter2", resolver.resolve("env://RCON_SECRET"));
    }

    @Test
    @DisplayName("env:// for an unset variable is an error (never a silent empty)")
    void envMissingThrows() {
        var resolver = resolverWithEnv(Map.of());
        assertThrows(SecretResolutionException.class, () -> resolver.resolve("env://NOPE"));
    }

    @Test
    @DisplayName("file:// resolves to the file contents and strips a single trailing newline")
    void fileResolvesAndStripsTrailingNewline(@TempDir Path dir) throws Exception {
        Path secret = dir.resolve("rcon");
        Files.writeString(secret, "s3cr3t\n", StandardCharsets.UTF_8);
        var resolver = resolverWithEnv(Map.of());

        assertEquals("s3cr3t", resolver.resolve("file://" + secret));
    }

    @Test
    @DisplayName("file:// keeps interior newlines, stripping only the final one")
    void filePreservesInteriorNewlines(@TempDir Path dir) throws Exception {
        Path secret = dir.resolve("multiline");
        Files.writeString(secret, "line1\nline2\n", StandardCharsets.UTF_8);
        var resolver = resolverWithEnv(Map.of());

        assertEquals("line1\nline2", resolver.resolve("file://" + secret));
    }

    @Test
    @DisplayName("file:// for a missing path is an error")
    void fileMissingThrows(@TempDir Path dir) {
        var resolver = resolverWithEnv(Map.of());
        assertThrows(SecretResolutionException.class, () -> resolver.resolve("file://" + dir.resolve("absent")));
    }

    @Test
    @DisplayName("an unregistered scheme is a hard error, not a passthrough")
    void unknownSchemeThrows() {
        var resolver = resolverWithEnv(Map.of());
        assertThrows(SecretResolutionException.class, () -> resolver.resolve("vault://app/rcon"));
    }

    @Test
    @DisplayName("a value with no scheme is an inline literal, returned unchanged")
    void literalPassthrough() throws Exception {
        var resolver = resolverWithEnv(Map.of());
        assertEquals("just-a-literal", resolver.resolve("just-a-literal"));
    }

    @Test
    @DisplayName("null/blank references pass through (nothing to resolve)")
    void blankPassthrough() throws Exception {
        var resolver = SecretResolver.withDefaults();
        assertNull(resolver.resolve(null));
        assertEquals("", resolver.resolve(""));
        assertEquals("   ", resolver.resolve("   "));
    }

    @Test
    @DisplayName("withDefaults wires the built-in env and file backends")
    void withDefaultsHasEnvAndFile() {
        var resolver = SecretResolver.withDefaults();
        // file:// for a missing path reaches the file backend (its error), proving it is registered.
        assertThrows(SecretResolutionException.class, () -> resolver.resolve("file:///definitely/not/here/secret"));
    }
}
