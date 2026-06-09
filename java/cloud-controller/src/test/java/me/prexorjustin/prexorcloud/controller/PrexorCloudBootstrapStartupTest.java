package me.prexorjustin.prexorcloud.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrexorCloudBootstrapStartupTest {

    @Test
    void productionBootstrapFailsFastWithoutRedis(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("config"));
        Files.writeString(tempDir.resolve(Path.of("config", "controller.yml")), """
                runtime:
                  profile: "production"
                database:
                  uri: "mongodb://127.0.0.1:27017"
                  database: "prexorcloud"
                """, StandardCharsets.UTF_8);

        Process process = new ProcessBuilder(
                        javaExecutable().toString(),
                        "--enable-preview",
                        "-cp",
                        System.getProperty("java.class.path"),
                        PrexorCloudBootstrap.class.getName())
                .directory(tempDir.toFile())
                .redirectErrorStream(true)
                .start();

        assertTrue(
                process.waitFor(30, TimeUnit.SECONDS), "bootstrap process should exit after config validation fails");

        String output = readOutput(process);
        assertEquals(1, process.exitValue(), () -> "unexpected bootstrap output:\n" + output);
        assertTrue(
                output.contains("redis.uri must be configured when runtime.profile=production"),
                () -> "bootstrap output should explain the Redis requirement:\n" + output);
    }

    private static Path javaExecutable() {
        String binary = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", binary);
    }

    private static String readOutput(Process process) throws IOException {
        try (var stream = process.getInputStream();
                var output = new ByteArrayOutputStream()) {
            stream.transferTo(output);
            return output.toString(StandardCharsets.UTF_8);
        }
    }
}
