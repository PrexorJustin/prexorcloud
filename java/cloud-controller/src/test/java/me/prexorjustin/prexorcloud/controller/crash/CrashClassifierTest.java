package me.prexorjustin.prexorcloud.controller.crash;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("CrashClassifier")
class CrashClassifierTest {

    @Nested
    @DisplayName("Exit code classification")
    class ExitCodeClassification {

        @Test
        @DisplayName("Exit code 0 returns CLEAN")
        void cleanExit() {
            assertEquals("CLEAN", CrashClassifier.classify(0, List.of()));
        }

        @Test
        @DisplayName("Exit code 1 returns GENERAL_ERROR")
        void generalError() {
            assertEquals("GENERAL_ERROR", CrashClassifier.classify(1, List.of()));
        }

        @Test
        @DisplayName("Exit code 130 returns SIGINT")
        void sigint() {
            assertEquals("SIGINT", CrashClassifier.classify(130, List.of()));
        }

        @Test
        @DisplayName("Exit code 137 returns SIGKILL")
        void sigkill() {
            assertEquals("SIGKILL", CrashClassifier.classify(137, List.of()));
        }

        @Test
        @DisplayName("Exit code 143 returns SIGTERM")
        void sigterm() {
            assertEquals("SIGTERM", CrashClassifier.classify(143, List.of()));
        }

        @Test
        @DisplayName("Unknown exit code returns UNKNOWN")
        void unknownExitCode() {
            assertEquals("UNKNOWN", CrashClassifier.classify(42, List.of()));
        }

        @Test
        @DisplayName("Negative exit code returns UNKNOWN")
        void negativeExitCode() {
            assertEquals("UNKNOWN", CrashClassifier.classify(-1, List.of()));
        }
    }

    @Nested
    @DisplayName("Log pattern matching")
    class LogPatternMatching {

        @Test
        @DisplayName("OutOfMemoryError in logs overrides exit code to OOM")
        void oomFromJavaError() {
            var logs = List.of(
                    "INFO: Server starting...",
                    "java.lang.OutOfMemoryError: GC overhead limit exceeded",
                    "FATAL: Shutting down");
            assertEquals("OOM", CrashClassifier.classify(1, logs));
        }

        @Test
        @DisplayName("Insufficient memory message returns OOM")
        void oomFromInsufficientMemory() {
            var logs = List.of("There is insufficient memory for the Java Runtime Environment");
            assertEquals("OOM", CrashClassifier.classify(137, logs));
        }

        @Test
        @DisplayName("OOM detection overrides even clean exit code")
        void oomOverridesCleanExit() {
            var logs = List.of("java.lang.OutOfMemoryError: Java heap space");
            assertEquals("OOM", CrashClassifier.classify(0, logs));
        }

        @Test
        @DisplayName("Null log tail falls through to exit code classification")
        void nullLogTail() {
            assertEquals("GENERAL_ERROR", CrashClassifier.classify(1, null));
        }

        @Test
        @DisplayName("Empty log tail uses exit code classification")
        void emptyLogTail() {
            assertEquals("SIGKILL", CrashClassifier.classify(137, List.of()));
        }

        @Test
        @DisplayName("Unrelated log lines do not trigger OOM")
        void unrelatedLogs() {
            var logs = List.of("INFO: Player joined", "WARN: Chunk load slow");
            assertEquals("GENERAL_ERROR", CrashClassifier.classify(1, logs));
        }
    }
}
