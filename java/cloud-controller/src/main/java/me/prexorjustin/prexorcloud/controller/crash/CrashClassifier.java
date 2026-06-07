package me.prexorjustin.prexorcloud.controller.crash;

import java.util.List;

/**
 * Classifies crash exit codes and log patterns into a human-readable
 * classification.
 */
public final class CrashClassifier {

    private CrashClassifier() {}

    /**
     * Classify a crash based on exit code and log tail.
     *
     * @return one of: CLEAN, GENERAL_ERROR, OOM, STACK_OVERFLOW, CLASS_NOT_FOUND,
     *         PORT_BIND_FAILURE, SIGINT, SIGABRT, SIGKILL, SIGSEGV, SIGTERM,
     *         UNKNOWN
     */
    public static String classify(int exitCode, List<String> logTail) {
        if (logTail != null) {
            // Check logs for OOM regardless of exit code
            if (logTail.stream()
                    .anyMatch(line -> line.contains("java.lang.OutOfMemoryError")
                            || line.contains("There is insufficient memory"))) {
                return "OOM";
            }

            // Check for StackOverflowError
            if (logTail.stream().anyMatch(line -> line.contains("java.lang.StackOverflowError"))) {
                return "STACK_OVERFLOW";
            }

            // Check for ClassNotFoundException / NoClassDefFoundError
            if (logTail.stream()
                    .anyMatch(line -> line.contains("java.lang.ClassNotFoundException")
                            || line.contains("java.lang.NoClassDefFoundError"))) {
                return "CLASS_NOT_FOUND";
            }

            // Check for port bind failure
            if (logTail.stream()
                    .anyMatch(line ->
                            line.contains("Failed to bind to port") || line.contains("Address already in use"))) {
                return "PORT_BIND_FAILURE";
            }
        }

        return switch (exitCode) {
            case 0 -> "CLEAN";
            case 1 -> "GENERAL_ERROR";
            case 130 -> "SIGINT";
            case 134 -> "SIGABRT";
            case 137 -> "SIGKILL";
            case 139 -> "SIGSEGV";
            case 143 -> "SIGTERM";
            default -> "UNKNOWN";
        };
    }
}
