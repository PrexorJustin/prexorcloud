package me.prexorjustin.prexorcloud.common.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("InputValidator")
class InputValidatorTest {

    @Nested
    @DisplayName("requireSafeName")
    class RequireSafeName {

        @Test
        @DisplayName("Accepts alphanumeric names")
        void acceptsAlphanumeric() {
            assertDoesNotThrow(() -> InputValidator.requireSafeName("lobby1", "group"));
            assertDoesNotThrow(() -> InputValidator.requireSafeName("MyServer", "group"));
        }

        @Test
        @DisplayName("Accepts names with dots, hyphens, underscores")
        void acceptsSpecialChars() {
            assertDoesNotThrow(() -> InputValidator.requireSafeName("my-server", "name"));
            assertDoesNotThrow(() -> InputValidator.requireSafeName("my_server", "name"));
            assertDoesNotThrow(() -> InputValidator.requireSafeName("my.server", "name"));
            assertDoesNotThrow(() -> InputValidator.requireSafeName("lobby-1.2_test", "name"));
        }

        @Test
        @DisplayName("Rejects null and blank names")
        void rejectsNullAndBlank() {
            assertThrows(IllegalArgumentException.class, () -> InputValidator.requireSafeName(null, "name"));
            assertThrows(IllegalArgumentException.class, () -> InputValidator.requireSafeName("", "name"));
            assertThrows(IllegalArgumentException.class, () -> InputValidator.requireSafeName("   ", "name"));
        }

        @Test
        @DisplayName("Rejects path traversal attempts")
        void rejectsPathTraversal() {
            assertThrows(IllegalArgumentException.class, () -> InputValidator.requireSafeName("../etc/passwd", "name"));
            assertThrows(IllegalArgumentException.class, () -> InputValidator.requireSafeName("..%2F", "name"));
        }

        @Test
        @DisplayName("Rejects names starting with dot")
        void rejectsLeadingDot() {
            assertThrows(IllegalArgumentException.class, () -> InputValidator.requireSafeName(".hidden", "name"));
        }

        @Test
        @DisplayName("Rejects names starting with hyphen or underscore")
        void rejectsLeadingSpecialChars() {
            assertThrows(IllegalArgumentException.class, () -> InputValidator.requireSafeName("-server", "name"));
            assertThrows(IllegalArgumentException.class, () -> InputValidator.requireSafeName("_server", "name"));
        }

        @Test
        @DisplayName("Rejects names with path separators")
        void rejectsPathSeparators() {
            assertThrows(IllegalArgumentException.class, () -> InputValidator.requireSafeName("path/name", "name"));
            assertThrows(IllegalArgumentException.class, () -> InputValidator.requireSafeName("path\\name", "name"));
        }

        @Test
        @DisplayName("Rejects names with spaces")
        void rejectsSpaces() {
            assertThrows(IllegalArgumentException.class, () -> InputValidator.requireSafeName("my server", "name"));
        }

        @Test
        @DisplayName("Rejects names longer than 64 characters")
        void rejectsTooLongNames() {
            String longName = "a".repeat(65);
            assertThrows(IllegalArgumentException.class, () -> InputValidator.requireSafeName(longName, "name"));
        }

        @Test
        @DisplayName("Accepts names exactly 64 characters")
        void accepts64CharNames() {
            String name = "a".repeat(64);
            assertDoesNotThrow(() -> InputValidator.requireSafeName(name, "name"));
        }
    }

    @Nested
    @DisplayName("isSafeName")
    class IsSafeName {

        @Test
        @DisplayName("Returns true for valid names")
        void returnsTrueForValid() {
            assertTrue(InputValidator.isSafeName("lobby-1"));
            assertTrue(InputValidator.isSafeName("server.test"));
        }

        @Test
        @DisplayName("Returns false for invalid names")
        void returnsFalseForInvalid() {
            assertFalse(InputValidator.isSafeName(null));
            assertFalse(InputValidator.isSafeName(""));
            assertFalse(InputValidator.isSafeName("../hack"));
            assertFalse(InputValidator.isSafeName(".hidden"));
        }
    }

    @Nested
    @DisplayName("requireSafeCommand")
    class RequireSafeCommand {

        @Test
        @DisplayName("Accepts normal commands")
        void acceptsNormalCommands() {
            assertDoesNotThrow(() -> InputValidator.requireSafeCommand("say Hello World"));
            assertDoesNotThrow(() -> InputValidator.requireSafeCommand("tp player1 100 64 200"));
            assertDoesNotThrow(() -> InputValidator.requireSafeCommand("gamemode creative player1"));
        }

        @Test
        @DisplayName("Accepts commands with tabs")
        void acceptsTabs() {
            assertDoesNotThrow(() -> InputValidator.requireSafeCommand("say\tHello"));
        }

        @Test
        @DisplayName("Rejects null and blank commands")
        void rejectsNullAndBlank() {
            assertThrows(IllegalArgumentException.class, () -> InputValidator.requireSafeCommand(null));
            assertThrows(IllegalArgumentException.class, () -> InputValidator.requireSafeCommand(""));
            assertThrows(IllegalArgumentException.class, () -> InputValidator.requireSafeCommand("   "));
        }

        @Test
        @DisplayName("Rejects commands exceeding max length")
        void rejectsTooLongCommands() {
            String longCmd = "a".repeat(1001);
            assertThrows(IllegalArgumentException.class, () -> InputValidator.requireSafeCommand(longCmd));
        }

        @Test
        @DisplayName("Accepts commands at max length")
        void acceptsMaxLength() {
            String cmd = "a".repeat(1000);
            assertDoesNotThrow(() -> InputValidator.requireSafeCommand(cmd));
        }

        @Test
        @DisplayName("Rejects commands with control characters")
        void rejectsControlChars() {
            assertThrows(IllegalArgumentException.class, () -> InputValidator.requireSafeCommand("say\u0000hello"));
            assertThrows(IllegalArgumentException.class, () -> InputValidator.requireSafeCommand("say\nhello"));
            assertThrows(IllegalArgumentException.class, () -> InputValidator.requireSafeCommand("say\rhello"));
        }
    }

    @Nested
    @DisplayName("requireValidPort")
    class RequireValidPort {

        @Test
        @DisplayName("Accepts valid ports")
        void acceptsValidPorts() {
            assertDoesNotThrow(() -> InputValidator.requireValidPort(1, "port"));
            assertDoesNotThrow(() -> InputValidator.requireValidPort(80, "port"));
            assertDoesNotThrow(() -> InputValidator.requireValidPort(25565, "port"));
            assertDoesNotThrow(() -> InputValidator.requireValidPort(65535, "port"));
        }

        @Test
        @DisplayName("Rejects zero and negative ports")
        void rejectsInvalidPorts() {
            assertThrows(IllegalArgumentException.class, () -> InputValidator.requireValidPort(0, "port"));
            assertThrows(IllegalArgumentException.class, () -> InputValidator.requireValidPort(-1, "port"));
        }

        @Test
        @DisplayName("Rejects ports above 65535")
        void rejectsHighPorts() {
            assertThrows(IllegalArgumentException.class, () -> InputValidator.requireValidPort(65536, "port"));
        }
    }

    @Nested
    @DisplayName("requirePositive")
    class RequirePositive {

        @Test
        @DisplayName("Accepts positive values")
        void acceptsPositive() {
            assertDoesNotThrow(() -> InputValidator.requirePositive(1, "count"));
            assertDoesNotThrow(() -> InputValidator.requirePositive(100, "count"));
        }

        @Test
        @DisplayName("Rejects zero and negative")
        void rejectsZeroAndNegative() {
            assertThrows(IllegalArgumentException.class, () -> InputValidator.requirePositive(0, "count"));
            assertThrows(IllegalArgumentException.class, () -> InputValidator.requirePositive(-5, "count"));
        }
    }

    @Nested
    @DisplayName("requireMaxLength")
    class RequireMaxLength {

        @Test
        @DisplayName("Accepts strings within limit")
        void acceptsWithinLimit() {
            assertDoesNotThrow(() -> InputValidator.requireMaxLength("hello", 10, "desc"));
            assertDoesNotThrow(() -> InputValidator.requireMaxLength(null, 10, "desc"));
        }

        @Test
        @DisplayName("Rejects strings exceeding limit")
        void rejectsExceedingLimit() {
            assertThrows(
                    IllegalArgumentException.class, () -> InputValidator.requireMaxLength("hello world", 5, "desc"));
        }
    }

    @Nested
    @DisplayName("requireNonNegative")
    class RequireNonNegative {

        @Test
        @DisplayName("Accepts zero")
        void acceptsZero() {
            assertDoesNotThrow(() -> InputValidator.requireNonNegative(0, "count"));
        }

        @Test
        @DisplayName("Accepts positive values")
        void acceptsPositive() {
            assertDoesNotThrow(() -> InputValidator.requireNonNegative(42, "count"));
        }

        @Test
        @DisplayName("Rejects negative values")
        void rejectsNegative() {
            assertThrows(IllegalArgumentException.class, () -> InputValidator.requireNonNegative(-1, "count"));
        }
    }

    @Nested
    @DisplayName("requireNonNegativeLong")
    class RequireNonNegativeLong {

        @Test
        @DisplayName("Accepts zero")
        void acceptsZero() {
            assertDoesNotThrow(() -> InputValidator.requireNonNegativeLong(0L, "bytes"));
        }

        @Test
        @DisplayName("Accepts positive values")
        void acceptsPositive() {
            assertDoesNotThrow(() -> InputValidator.requireNonNegativeLong(100_000L, "bytes"));
        }

        @Test
        @DisplayName("Rejects negative values")
        void rejectsNegative() {
            assertThrows(IllegalArgumentException.class, () -> InputValidator.requireNonNegativeLong(-1L, "bytes"));
        }
    }

    @Nested
    @DisplayName("requireRange")
    class RequireRange {

        @Test
        @DisplayName("Accepts values at boundaries")
        void acceptsBoundaries() {
            assertDoesNotThrow(() -> InputValidator.requireRange(0.0, 0.0, 1.0, "cpu"));
            assertDoesNotThrow(() -> InputValidator.requireRange(1.0, 0.0, 1.0, "cpu"));
        }

        @Test
        @DisplayName("Accepts values within range")
        void acceptsMiddle() {
            assertDoesNotThrow(() -> InputValidator.requireRange(0.5, 0.0, 1.0, "cpu"));
        }

        @Test
        @DisplayName("Rejects values below minimum")
        void rejectsBelowMin() {
            assertThrows(IllegalArgumentException.class, () -> InputValidator.requireRange(-0.1, 0.0, 1.0, "cpu"));
        }

        @Test
        @DisplayName("Rejects values above maximum")
        void rejectsAboveMax() {
            assertThrows(IllegalArgumentException.class, () -> InputValidator.requireRange(1.1, 0.0, 1.0, "cpu"));
        }
    }
}
