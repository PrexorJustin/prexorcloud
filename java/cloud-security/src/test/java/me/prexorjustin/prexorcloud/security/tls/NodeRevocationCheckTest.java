package me.prexorjustin.prexorcloud.security.tls;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("NodeRevocationCheck")
class NodeRevocationCheckTest {

    @Test
    @DisplayName("NONE accepts every certificate")
    void noneAcceptsAll() {
        NodeRevocationCheck check = NodeRevocationCheck.NONE;

        assertFalse(check.isRevoked(BigInteger.ONE, "node-1"));
        assertFalse(check.isRevoked(BigInteger.ZERO, ""));
        assertFalse(check.isRevoked(new BigInteger("12345678901234567890"), "node-99"));
    }

    @Test
    @DisplayName("Lambda implementation can revoke by serial")
    void lambdaCanRevokeBySerial() {
        Set<BigInteger> revoked = Set.of(BigInteger.valueOf(42));
        NodeRevocationCheck check = (serial, cn) -> revoked.contains(serial);

        assertTrue(check.isRevoked(BigInteger.valueOf(42), "node-x"));
        assertFalse(check.isRevoked(BigInteger.valueOf(43), "node-x"));
    }

    @Test
    @DisplayName("Lambda implementation can revoke by subject CN")
    void lambdaCanRevokeBySubjectCn() {
        Set<String> revoked = Set.of("node-bad");
        NodeRevocationCheck check = (serial, cn) -> revoked.contains(cn);

        assertTrue(check.isRevoked(BigInteger.ONE, "node-bad"));
        assertFalse(check.isRevoked(BigInteger.ONE, "node-good"));
    }
}
