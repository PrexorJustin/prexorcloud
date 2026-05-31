package me.prexorjustin.prexorcloud.controller.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JoinTokenCodecTest {

    private static final byte[] SEED = "this-is-a-fake-cluster-seed-secret".getBytes();
    private static final String CLUSTER_ID = "cluster-test-123";
    private static final List<String> JOIN_ADDRS = List.of("ctrl-1.cluster.test:9091", "ctrl-2.cluster.test:9091");
    private static final Instant EXPIRES = Instant.parse("2026-06-30T12:00:00Z");

    @Test
    @DisplayName("encode→parse→verifyHmac round-trips a fresh token")
    void roundTrip() {
        var issued = JoinTokenCodec.encode(CLUSTER_ID, JOIN_ADDRS, EXPIRES, SEED);
        assertNotNull(issued.jti(), "jti must be populated");
        assertTrue(issued.token().startsWith("prexor-jt:v1:"));

        var parsed = JoinTokenCodec.parse(issued.token());
        assertEquals(CLUSTER_ID, parsed.payload().clusterId());
        assertEquals(JOIN_ADDRS, parsed.payload().joinAddrs());
        assertEquals(EXPIRES, parsed.payload().expiresAt());
        assertEquals(issued.jti(), parsed.payload().jti());

        assertTrue(JoinTokenCodec.verifyHmac(parsed, SEED), "HMAC matches against the same seed");
    }

    @Test
    @DisplayName("verifyHmac fails when the seed differs")
    void wrongSeedFailsVerify() {
        var issued = JoinTokenCodec.encode(CLUSTER_ID, JOIN_ADDRS, EXPIRES, SEED);
        var parsed = JoinTokenCodec.parse(issued.token());
        assertFalse(JoinTokenCodec.verifyHmac(parsed, "different-seed".getBytes()));
    }

    @Test
    @DisplayName("parse rejects tokens without the prexor-jt:v1: prefix")
    void parseRejectsMissingPrefix() {
        assertThrows(JoinTokenCodec.InvalidJoinToken.class, () -> JoinTokenCodec.parse("not-a-real-token"));
        assertThrows(JoinTokenCodec.InvalidJoinToken.class, () -> JoinTokenCodec.parse("prexor-jt:v2:foo.bar"));
    }

    @Test
    @DisplayName("parse rejects tokens missing the payload.hmac separator")
    void parseRejectsMissingSeparator() {
        assertThrows(JoinTokenCodec.InvalidJoinToken.class, () -> JoinTokenCodec.parse("prexor-jt:v1:onlypayload"));
    }

    @Test
    @DisplayName("parse rejects tokens with non-base64 payload")
    void parseRejectsCorruptPayload() {
        assertThrows(
                JoinTokenCodec.InvalidJoinToken.class,
                () -> JoinTokenCodec.parse("prexor-jt:v1:###not-base64###.aaa"));
    }

    @Test
    @DisplayName("tampered payload fails HMAC verify")
    void tamperedPayloadFailsHmac() {
        var issued = JoinTokenCodec.encode(CLUSTER_ID, JOIN_ADDRS, EXPIRES, SEED);
        // Splice a different payload into the same HMAC slot — the parsed payload's recomputed
        // HMAC will not match the original HMAC, so verifyHmac returns false.
        String token = issued.token();
        int dot = token.indexOf('.', "prexor-jt:v1:".length());
        String hmacPart = token.substring(dot);

        var malicious = JoinTokenCodec.encode("evil-cluster", JOIN_ADDRS, EXPIRES, SEED);
        int evilDot = malicious.token().indexOf('.', "prexor-jt:v1:".length());
        String tampered = malicious.token().substring(0, evilDot) + hmacPart;

        var parsed = JoinTokenCodec.parse(tampered);
        assertFalse(JoinTokenCodec.verifyHmac(parsed, SEED));
    }
}
