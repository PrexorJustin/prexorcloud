package me.prexorjustin.prexorcloud.security.jwt;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("JwtManager")
class JwtManagerTest {

    private static final String VALID_SECRET = JwtManager.generateSecret();

    private JwtManager manager() {
        return new JwtManager(VALID_SECRET, 60);
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("Throws on null secret")
        void throwsOnNullSecret() {
            assertThrows(IllegalStateException.class, () -> new JwtManager(null, 60));
        }

        @Test
        @DisplayName("Throws on blank secret")
        void throwsOnBlankSecret() {
            assertThrows(IllegalStateException.class, () -> new JwtManager("  ", 60));
        }

        @Test
        @DisplayName("Throws on invalid base64")
        void throwsOnInvalidBase64() {
            assertThrows(IllegalStateException.class, () -> new JwtManager("not!valid!base64!!!", 60));
        }

        @Test
        @DisplayName("Throws on secret shorter than 32 bytes")
        void throwsOnShortSecret() {
            String shortSecret = Base64.getEncoder().encodeToString(new byte[16]);
            assertThrows(IllegalStateException.class, () -> new JwtManager(shortSecret, 60));
        }

        @Test
        @DisplayName("Accepts valid 32-byte secret")
        void acceptsValidSecret() {
            assertDoesNotThrow(() -> new JwtManager(VALID_SECRET, 60));
        }
    }

    @Nested
    @DisplayName("Token issuance and validation")
    class IssuanceAndValidation {

        @Test
        @DisplayName("Issued token validates successfully")
        void issueAndValidate() {
            var mgr = manager();
            String token = mgr.issue("admin", "ADMIN");
            assertNotNull(token);
            assertFalse(token.isBlank());

            var claims = mgr.validate(token);
            assertTrue(claims.isPresent());
            assertEquals("admin", claims.get().getSubject());
            assertEquals("ADMIN", claims.get().get("role", String.class));
        }

        @Test
        @DisplayName("Token contains correct subject and role")
        void tokenContainsCorrectClaims() {
            var mgr = manager();
            String token = mgr.issue("operator1", "OPERATOR");
            var claims = mgr.validate(token).orElseThrow();

            assertEquals("operator1", claims.getSubject());
            assertEquals("OPERATOR", claims.get("role", String.class));
            assertNotNull(claims.getIssuedAt());
            assertNotNull(claims.getExpiration());
        }

        @Test
        @DisplayName("Token has correct expiration")
        void tokenHasCorrectExpiration() {
            var mgr = manager();
            String token = mgr.issue("user", "VIEWER");
            var claims = mgr.validate(token).orElseThrow();

            long expiryMs =
                    claims.getExpiration().getTime() - claims.getIssuedAt().getTime();
            // 60 minutes = 3_600_000 ms, allow small timing margin
            assertTrue(
                    expiryMs >= 3_599_000 && expiryMs <= 3_601_000, "Expected ~60min expiry, got " + expiryMs + "ms");
        }
    }

    @Nested
    @DisplayName("Validation failures")
    class ValidationFailures {

        @Test
        @DisplayName("Returns empty for garbage token")
        void rejectsGarbage() {
            assertTrue(manager().validate("not.a.jwt").isEmpty());
        }

        @Test
        @DisplayName("Returns empty for empty string")
        void rejectsEmpty() {
            assertTrue(manager().validate("").isEmpty());
        }

        @Test
        @DisplayName("Returns empty for token signed with different secret")
        void rejectsDifferentSecret() {
            var mgr1 = new JwtManager(JwtManager.generateSecret(), 60);
            var mgr2 = new JwtManager(JwtManager.generateSecret(), 60);

            String token = mgr1.issue("user", "ADMIN");
            assertTrue(mgr2.validate(token).isEmpty());
        }

        @Test
        @DisplayName("Returns empty for expired token")
        void rejectsExpiredToken() {
            // Create manager with 0-minute expiration
            var mgr = new JwtManager(VALID_SECRET, 0);
            String token = mgr.issue("user", "ADMIN");
            // Token is already expired at issuance (exp == iat)
            assertTrue(mgr.validate(token).isEmpty());
        }
    }

    @Nested
    @DisplayName("Key rotation")
    class KeyRotation {

        @Test
        @DisplayName("Active signing key validates own tokens")
        void activeKeyValidates() {
            var mgr = manager();
            assertEquals(1, mgr.acceptableKeyCount());
            String token = mgr.issue("user", "ADMIN");
            assertTrue(mgr.validate(token).isPresent());
        }

        @Test
        @DisplayName("Tokens issued before rotation still validate after rotation")
        void rotationKeepsOldTokensValid() {
            String oldSecret = JwtManager.generateSecret();
            String newSecret = JwtManager.generateSecret();
            var mgr = new JwtManager(oldSecret, 60);

            String oldToken = mgr.issue("user", "ADMIN");
            mgr.rotate(newSecret);

            assertTrue(mgr.validate(oldToken).isPresent(), "old token should still validate during overlap");
            String newToken = mgr.issue("user", "ADMIN");
            assertTrue(mgr.validate(newToken).isPresent(), "new token should validate");
        }

        @Test
        @DisplayName("Revoking the previous key rejects its tokens")
        void revokingPreviousRejectsOldTokens() {
            String oldSecret = JwtManager.generateSecret();
            String newSecret = JwtManager.generateSecret();
            var mgr = new JwtManager(oldSecret, 60);
            String oldToken = mgr.issue("user", "ADMIN");

            mgr.rotate(newSecret);
            assertTrue(mgr.validate(oldToken).isPresent());

            assertTrue(mgr.revokeKey(oldSecret));
            assertTrue(mgr.validate(oldToken).isEmpty());
        }

        @Test
        @DisplayName("Revoking the active key throws")
        void cannotRevokeActiveKey() {
            String secret = JwtManager.generateSecret();
            var mgr = new JwtManager(secret, 60);
            assertThrows(IllegalStateException.class, () -> mgr.revokeKey(secret));
        }

        @Test
        @DisplayName("addPreviousKey accepts foreign-signed tokens")
        void addPreviousKeyAcceptsForeignTokens() {
            String oldSecret = JwtManager.generateSecret();
            String newSecret = JwtManager.generateSecret();

            // simulate previous controller signed a token, then we boot with the new secret
            var oldMgr = new JwtManager(oldSecret, 60);
            String oldToken = oldMgr.issue("user", "ADMIN");

            var newMgr = new JwtManager(newSecret, 60);
            assertTrue(newMgr.validate(oldToken).isEmpty(), "should not validate before adding previous");

            newMgr.addPreviousKey(oldSecret);
            assertTrue(newMgr.validate(oldToken).isPresent());
        }

        @Test
        @DisplayName("acceptableKeyCount is bounded by maxAcceptableKeys")
        void acceptableKeysBounded() {
            String s1 = JwtManager.generateSecret();
            var mgr = new JwtManager(s1, 60, 2);
            mgr.addPreviousKey(JwtManager.generateSecret());
            mgr.addPreviousKey(JwtManager.generateSecret());
            mgr.addPreviousKey(JwtManager.generateSecret());

            assertTrue(mgr.acceptableKeyCount() <= 2, "expected <=2, got " + mgr.acceptableKeyCount());
        }
    }

    @Nested
    @DisplayName("Secret generation")
    class SecretGeneration {

        @Test
        @DisplayName("Generated secret is valid base64")
        void generatedSecretIsValidBase64() {
            String secret = JwtManager.generateSecret();
            assertDoesNotThrow(() -> Base64.getDecoder().decode(secret));
        }

        @Test
        @DisplayName("Generated secret is at least 32 bytes")
        void generatedSecretIsLongEnough() {
            String secret = JwtManager.generateSecret();
            byte[] decoded = Base64.getDecoder().decode(secret);
            assertTrue(decoded.length >= 32);
        }

        @Test
        @DisplayName("Generated secrets are unique")
        void generatedSecretsAreUnique() {
            String s1 = JwtManager.generateSecret();
            String s2 = JwtManager.generateSecret();
            assertNotEquals(s1, s2);
        }
    }
}
