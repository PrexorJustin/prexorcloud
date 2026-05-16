package me.prexorjustin.prexorcloud.controller.crash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CrashCauseExtractor")
class CrashCauseExtractorTest {

    @Test
    @DisplayName("extracts OutOfMemoryError message")
    void extractsOom() {
        var cause = CrashCauseExtractor.extract(
                List.of(
                        "[INFO] Starting server",
                        "java.lang.OutOfMemoryError: Java heap space",
                        "  at net.minecraft.world.level.chunk.ChunkAccess.<init>(ChunkAccess.java:84)"),
                "OOM",
                137);
        assertEquals("OutOfMemoryError: Java heap space", cause.summary());
        assertEquals(16, cause.signature().length());
    }

    @Test
    @DisplayName("extracts native-memory OOM phrasing")
    void extractsNativeOom() {
        var cause = CrashCauseExtractor.extract(
                List.of("There is insufficient memory for the Java Runtime Environment to continue."), "OOM", 1);
        assertTrue(cause.summary().contains("native memory"));
    }

    @Test
    @DisplayName("extracts a bind failure")
    void extractsBindFailure() {
        var cause = CrashCauseExtractor.extract(
                List.of("[ERROR] Failed to bind to port 25565: Address already in use"), "PORT_BIND_FAILURE", 1);
        assertTrue(cause.summary().startsWith("Failed to bind to port"));
    }

    @Test
    @DisplayName("extracts a generic exception with message")
    void extractsExceptionWithMessage() {
        var cause = CrashCauseExtractor.extract(
                List.of(
                        "[ERROR] Plugin threw",
                        "java.lang.NullPointerException: Cannot invoke \"World.getName()\" because \"world\" is null",
                        "  at com.example.lobby.LobbyPlugin.onJoin(LobbyPlugin.java:42)"),
                "GENERAL_ERROR",
                1);
        assertTrue(cause.summary().startsWith("NullPointerException:"));
        assertTrue(cause.summary().contains("World.getName()"));
    }

    @Test
    @DisplayName("extracts a Caused-by line")
    void extractsCausedBy() {
        var cause = CrashCauseExtractor.extract(
                List.of(
                        "Some context line",
                        "Caused by: java.lang.ArrayIndexOutOfBoundsException: Index 64 out of bounds for length 64",
                        "  at com.example.pvp.ArenaGen.generate(ArenaGen.java:128)"),
                "GENERAL_ERROR",
                1);
        assertTrue(cause.summary().startsWith("ArrayIndexOutOfBoundsException:"));
    }

    @Test
    @DisplayName("falls back to signal name when no exception is present")
    void fallsBackToSignal() {
        var cause = CrashCauseExtractor.extract(List.of("[INFO] oom-killer chose pid 14122 (java)"), "SIGKILL", 137);
        assertEquals("Killed (SIGKILL)", cause.summary());
    }

    @Test
    @DisplayName("returns identical signature for same root cause with different line numbers")
    void signatureIsStableAcrossLineNumbers() {
        var first = CrashCauseExtractor.extract(
                List.of("java.lang.NullPointerException: oops", "  at com.example.Foo.bar(Foo.java:42)"),
                "GENERAL_ERROR",
                1);
        var second = CrashCauseExtractor.extract(
                List.of("java.lang.NullPointerException: oops", "  at com.example.Foo.bar(Foo.java:123)"),
                "GENERAL_ERROR",
                1);
        assertEquals(first.signature(), second.signature());
    }

    @Test
    @DisplayName("returns different signatures for distinct exception types")
    void signatureDiffersAcrossCauses() {
        var npe = CrashCauseExtractor.extract(List.of("java.lang.NullPointerException: x"), "GENERAL_ERROR", 1);
        var oom = CrashCauseExtractor.extract(List.of("java.lang.OutOfMemoryError: Java heap space"), "OOM", 137);
        assertNotEquals(npe.signature(), oom.signature());
    }

    @Test
    @DisplayName("handles empty log tail by using classification")
    void handlesEmptyLog() {
        var cause = CrashCauseExtractor.extract(List.of(), "SIGTERM", 143);
        assertEquals("Terminated (SIGTERM)", cause.summary());
    }
}
