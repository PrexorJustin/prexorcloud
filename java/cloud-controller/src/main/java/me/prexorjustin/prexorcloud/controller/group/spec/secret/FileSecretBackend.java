package me.prexorjustin.prexorcloud.controller.group.spec.secret;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves {@code file://PATH} to the UTF-8 contents of the file at {@code PATH} (the canonical
 * mounted-secret pattern: {@code file:///run/secrets/rcon}). A single trailing newline is stripped so
 * a secret written with {@code echo > file} resolves cleanly. The path is read as the controller
 * process sees it — secret files are expected to be operator-provisioned, not attacker-controlled.
 */
public final class FileSecretBackend implements SecretBackend {

    @Override
    public String scheme() {
        return "file";
    }

    @Override
    public String resolve(String reference) throws SecretResolutionException {
        String raw = reference == null ? "" : reference.trim();
        if (raw.isEmpty()) {
            throw new SecretResolutionException("file secret reference is missing a path");
        }
        Path path = Path.of(raw);
        if (!Files.isRegularFile(path)) {
            throw new SecretResolutionException("secret file '" + raw + "' does not exist or is not a regular file");
        }
        try {
            String value = Files.readString(path, StandardCharsets.UTF_8);
            return value.endsWith("\n") ? value.substring(0, value.length() - 1) : value;
        } catch (IOException e) {
            throw new SecretResolutionException("secret file '" + raw + "' could not be read", e);
        }
    }
}
