package me.prexorjustin.prexorcloud.controller.crash;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts a one-line human-readable cause summary and a deterministic
 * signature hash from a crash's log tail.
 *
 * <p>The summary feeds the crashes table so operators can tell crashes apart
 * without opening the detail dialog. The signature groups recurring crashes
 * by the same root cause across instances and time.
 */
public final class CrashCauseExtractor {

    /** Matches a Java exception or error line like {@code java.lang.NullPointerException: Cannot invoke ...}. */
    private static final Pattern EXCEPTION_LINE =
            Pattern.compile("(?:Exception in thread \"[^\"]*\"\\s+|Caused by:\\s+)?"
                    + "([\\w$.]+(?:Exception|Error|Throwable))" + "(?::\\s*(.*))?");

    /** Matches a stack frame like {@code     at com.example.Foo.bar(Foo.java:42)}. */
    private static final Pattern STACK_FRAME = Pattern.compile("\\s*at\\s+([\\w$.]+\\.[\\w$<>]+)\\(.*?\\)\\s*");

    /** Strips numeric line/offset noise so the signature is stable across runs. */
    private static final Pattern STABLE_NORMALIZE = Pattern.compile("\\d+");

    public record Cause(String summary, String signature) {
        public static final Cause UNKNOWN = new Cause("Unknown cause", "00000000");
    }

    private CrashCauseExtractor() {}

    public static Cause extract(List<String> logTail, String classification, int exitCode) {
        if (logTail == null || logTail.isEmpty()) {
            return fallbackForClassification(classification, exitCode);
        }

        String oom = scanForOom(logTail);
        if (oom != null) return signed("OOM", oom, firstFrame(logTail));

        String bind = scanForBindFailure(logTail);
        if (bind != null) return signed("BIND", bind, null);

        String exception = scanForException(logTail);
        if (exception != null) return signed("EXC", exception, firstFrame(logTail));

        return fallbackForClassification(classification, exitCode);
    }

    private static String scanForOom(List<String> logTail) {
        for (int i = logTail.size() - 1; i >= 0; i--) {
            String line = logTail.get(i);
            int idx = line.indexOf("OutOfMemoryError");
            if (idx < 0) continue;
            int colon = line.indexOf(':', idx);
            if (colon < 0 || colon >= line.length() - 1) return "OutOfMemoryError";
            return "OutOfMemoryError: " + line.substring(colon + 1).strip();
        }
        for (String line : logTail) {
            if (line.contains("There is insufficient memory")) {
                return "OutOfMemoryError: native memory exhausted";
            }
        }
        return null;
    }

    private static String scanForBindFailure(List<String> logTail) {
        for (int i = logTail.size() - 1; i >= 0; i--) {
            String line = logTail.get(i);
            if (line.contains("Failed to bind to port")) {
                int idx = line.indexOf("Failed to bind to port");
                return line.substring(idx).strip();
            }
            if (line.contains("Address already in use")) {
                return "Bind failure: address already in use";
            }
        }
        return null;
    }

    private static String scanForException(List<String> logTail) {
        for (int i = logTail.size() - 1; i >= 0; i--) {
            String line = logTail.get(i).strip();
            if (line.isEmpty()) continue;
            Matcher m = EXCEPTION_LINE.matcher(line);
            if (!m.matches()) continue;
            String fqn = m.group(1);
            String message = m.group(2);
            String shortName = fqn.substring(fqn.lastIndexOf('.') + 1);
            if (message == null || message.isBlank()) return shortName;
            String trimmed = message.length() > 200 ? message.substring(0, 197) + "..." : message;
            return shortName + ": " + trimmed;
        }
        return null;
    }

    private static String firstFrame(List<String> logTail) {
        for (String line : logTail) {
            Matcher m = STACK_FRAME.matcher(line);
            if (m.matches()) return m.group(1);
        }
        return null;
    }

    private static Cause fallbackForClassification(String classification, int exitCode) {
        String summary =
                switch (classification == null ? "" : classification) {
                    case "SIGKILL" -> "Killed (SIGKILL)";
                    case "SIGTERM" -> "Terminated (SIGTERM)";
                    case "SIGINT" -> "Interrupted (SIGINT)";
                    case "SIGSEGV" -> "Segmentation fault";
                    case "SIGABRT" -> "Aborted";
                    case "CLEAN" -> "Clean exit";
                    default -> "Exit code " + exitCode;
                };
        return new Cause(summary, signatureOf(classification == null ? "EXIT" : classification, summary, null));
    }

    private static Cause signed(String kind, String summary, String frame) {
        return new Cause(summary, signatureOf(kind, summary, frame));
    }

    private static String signatureOf(String kind, String summary, String frame) {
        String normalized = STABLE_NORMALIZE
                .matcher((kind + "|" + summary + "|" + (frame == null ? "" : frame)))
                .replaceAll("N");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every Java runtime.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
