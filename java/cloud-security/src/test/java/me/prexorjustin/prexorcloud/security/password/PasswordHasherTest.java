package me.prexorjustin.prexorcloud.security.password;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("PasswordHasher")
class PasswordHasherTest {

    @Nested
    @DisplayName("Hash generation")
    class HashGeneration {

        @Test
        @DisplayName("Hash is not null and not empty")
        void hashIsNotEmpty() {
            String hash = PasswordHasher.hash("password123");
            assertNotNull(hash);
            assertFalse(hash.isEmpty());
        }

        @Test
        @DisplayName("Hash starts with Argon2id marker")
        void hashHasArgon2idMarker() {
            String hash = PasswordHasher.hash("test");
            assertTrue(hash.startsWith("$argon2id$"), "Hash should start with $argon2id$, got: " + hash);
        }

        @Test
        @DisplayName("Same password produces different hashes (salted)")
        void differentHashesForSamePassword() {
            String h1 = PasswordHasher.hash("password");
            String h2 = PasswordHasher.hash("password");
            assertNotEquals(h1, h2, "Hashes should differ due to random salt");
        }

        @Test
        @DisplayName("Different passwords produce different hashes")
        void differentPasswordsDifferentHashes() {
            String h1 = PasswordHasher.hash("password1");
            String h2 = PasswordHasher.hash("password2");
            assertNotEquals(h1, h2);
        }
    }

    @Nested
    @DisplayName("Verification")
    class Verification {

        @Test
        @DisplayName("Correct password verifies successfully")
        void correctPasswordVerifies() {
            String hash = PasswordHasher.hash("mySecret!");
            assertTrue(PasswordHasher.verify("mySecret!", hash));
        }

        @Test
        @DisplayName("Wrong password is rejected")
        void wrongPasswordRejected() {
            String hash = PasswordHasher.hash("correctPassword");
            assertFalse(PasswordHasher.verify("wrongPassword", hash));
        }

        @Test
        @DisplayName("Empty password can be hashed and verified")
        void emptyPassword() {
            String hash = PasswordHasher.hash("");
            assertTrue(PasswordHasher.verify("", hash));
            assertFalse(PasswordHasher.verify("notempty", hash));
        }

        @Test
        @DisplayName("Unicode password hashes and verifies correctly")
        void unicodePassword() {
            String hash = PasswordHasher.hash("p@$$w0rd\u00e9\u00fc\u00f1");
            assertTrue(PasswordHasher.verify("p@$$w0rd\u00e9\u00fc\u00f1", hash));
        }

        @Test
        @DisplayName("Long password hashes and verifies correctly")
        void longPassword() {
            String longPwd = "a".repeat(256);
            String hash = PasswordHasher.hash(longPwd);
            assertTrue(PasswordHasher.verify(longPwd, hash));
        }
    }
}
