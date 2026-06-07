package me.prexorjustin.prexorcloud.common.util;

import java.util.regex.Pattern;

/**
 * Centralized input validation for user-supplied identifiers and values.
 * Prevents path traversal, injection, and resource exhaustion.
 */
public final class InputValidator {

    private InputValidator() {}

    /** Allowed pattern for group names, instance IDs, template names, node IDs. */
    private static final Pattern SAFE_NAME = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$");

    /** Maximum length for commands sent to server instances. */
    private static final int MAX_COMMAND_LENGTH = 1000;

    /** Maximum length for arbitrary string fields (descriptions, messages). */
    private static final int MAX_STRING_LENGTH = 4096;

    /**
     * Validate a resource name (group, instance, template, node, module). Must be
     * 1-64 chars, alphanumeric with dots, hyphens, underscores. No path separators,
     * no leading dots, no whitespace.
     *
     * @throws IllegalArgumentException
     *             if invalid
     */
    public static void requireSafeName(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        if (!SAFE_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    fieldName + " must be 1-64 characters, alphanumeric with dots/hyphens/underscores: " + value);
        }
    }

    /**
     * Check if a name is safe without throwing.
     */
    public static boolean isSafeName(String value) {
        return value != null && SAFE_NAME.matcher(value).matches();
    }

    /**
     * Validate a command string (sent to server instance stdin).
     *
     * @throws IllegalArgumentException
     *             if the command is blank, too long, or contains control characters
     */
    public static void requireSafeCommand(String command) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("Command cannot be blank");
        }
        if (command.length() > MAX_COMMAND_LENGTH) {
            throw new IllegalArgumentException("Command too long (max " + MAX_COMMAND_LENGTH + " characters)");
        }
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            // Allow printable ASCII + common whitespace (space, tab), reject control chars
            if (c < 0x20 && c != '\t') {
                throw new IllegalArgumentException("Command contains invalid control character at position " + i);
            }
        }
    }

    /**
     * Validate a port number.
     *
     * @throws IllegalArgumentException
     *             if outside valid range
     */
    public static void requireValidPort(int port, String fieldName) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(fieldName + " must be between 1 and 65535, got: " + port);
        }
    }

    /**
     * Validate a positive integer.
     *
     * @throws IllegalArgumentException
     *             if not positive
     */
    public static void requirePositive(int value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive, got: " + value);
        }
    }

    /**
     * Validate that a string is within max length.
     *
     * @throws IllegalArgumentException
     *             if too long
     */
    public static void requireMaxLength(String value, int maxLength, String fieldName) {
        if (value != null && value.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " too long (max " + maxLength + " characters)");
        }
    }

    public static void requireNonNegative(int value, String fieldName) {
        if (value < 0) {
            throw new IllegalArgumentException(fieldName + " must be non-negative, got: " + value);
        }
    }

    public static void requireNonNegativeLong(long value, String fieldName) {
        if (value < 0) {
            throw new IllegalArgumentException(fieldName + " must be non-negative, got: " + value);
        }
    }

    public static void requireRange(double value, double min, double max, String fieldName) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                    fieldName + " must be between " + min + " and " + max + ", got: " + value);
        }
    }
}
