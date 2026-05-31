package me.prexorjustin.prexorcloud.controller.cluster;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Encodes and parses cluster join tokens.
 *
 * <p>Wire format: {@code prexor-jt:v1:<base64url(payload)>.<base64url(hmac)>}
 * where {@code payload} is the JSON-encoded {@link Payload} and {@code hmac} is
 * {@code HMAC-SHA256(seedSecret, payload_bytes)}. Tokens are self-describing —
 * the payload carries the {@code clusterId} and {@code joinAddrs[]} the
 * joining controller dials, so the joining side can validate before sending.
 *
 * <p>The seed secret is held in the Raft state machine's {@link
 * me.prexorjustin.prexorcloud.controller.cluster.state.ClusterMeta} and never
 * leaves the cluster. A rotated seed invalidates every outstanding token.
 */
public final class JoinTokenCodec {

    private static final String PREFIX = "prexor-jt:v1:";
    private static final String HMAC_ALG = "HmacSHA256";
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final Base64.Encoder URL_ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DEC = Base64.getUrlDecoder();

    private JoinTokenCodec() {}

    /**
     * Payload signed inside the token. {@code expiresAt} is enforced on
     * redemption; {@code jti} is the unique identifier the state machine
     * deduplicates against.
     */
    public record Payload(
            @JsonProperty("jti") String jti,
            @JsonProperty("clusterId") String clusterId,
            @JsonProperty("joinAddrs") List<String> joinAddrs,
            @JsonProperty("expiresAt") Instant expiresAt) {}

    /** Produced by {@link #encode}; the {@code hmacBase64} ends up in the wire token. */
    public record Issued(String token, String jti, String hmacBase64) {}

    /** Returned by {@link #parse}; only the payload is exposed — the HMAC is consumed during verify. */
    public record Parsed(Payload payload, String hmacBase64) {}

    public static Issued encode(
            String clusterId, List<String> joinAddrs, Instant expiresAt, byte[] seedSecret) {
        String jti = UUID.randomUUID().toString();
        Payload payload = new Payload(jti, clusterId, List.copyOf(joinAddrs), expiresAt);
        byte[] payloadBytes;
        try {
            payloadBytes = MAPPER.writeValueAsBytes(payload);
        } catch (Exception e) {
            throw new IllegalStateException("encode join-token payload", e);
        }
        byte[] hmac = hmac(seedSecret, payloadBytes);
        String token = PREFIX + URL_ENC.encodeToString(payloadBytes) + "." + URL_ENC.encodeToString(hmac);
        return new Issued(token, jti, URL_ENC.encodeToString(hmac));
    }

    /**
     * Parse the wire token. Throws {@link InvalidJoinToken} on malformed input.
     * Does NOT validate the HMAC; call {@link #verifyHmac} once you have the
     * cluster seed secret to do that.
     */
    public static Parsed parse(String token) {
        if (token == null || !token.startsWith(PREFIX)) {
            throw new InvalidJoinToken("token must start with " + PREFIX);
        }
        String rest = token.substring(PREFIX.length());
        int dot = rest.indexOf('.');
        if (dot < 0) {
            throw new InvalidJoinToken("token missing payload.hmac separator");
        }
        String payloadB64 = rest.substring(0, dot);
        String hmacB64 = rest.substring(dot + 1);
        byte[] payloadBytes;
        try {
            payloadBytes = URL_DEC.decode(payloadB64);
        } catch (IllegalArgumentException e) {
            throw new InvalidJoinToken("payload is not valid base64url");
        }
        Payload payload;
        try {
            payload = MAPPER.readValue(payloadBytes, Payload.class);
        } catch (Exception e) {
            throw new InvalidJoinToken("payload JSON is malformed: " + e.getMessage());
        }
        return new Parsed(payload, hmacB64);
    }

    /**
     * Constant-time verify that the parsed HMAC matches a fresh HMAC over the
     * payload using the given seed secret. Returns false on any mismatch
     * (different secret, tampered payload, base64 corruption).
     */
    public static boolean verifyHmac(Parsed parsed, byte[] seedSecret) {
        byte[] payloadBytes;
        try {
            payloadBytes = MAPPER.writeValueAsBytes(parsed.payload());
        } catch (Exception e) {
            return false;
        }
        byte[] expected = hmac(seedSecret, payloadBytes);
        byte[] provided;
        try {
            provided = URL_DEC.decode(parsed.hmacBase64());
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(expected, provided);
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(key, HMAC_ALG));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC failed", e);
        }
    }

    /** Thrown for malformed tokens — wire-format failures, not HMAC mismatches. */
    public static final class InvalidJoinToken extends RuntimeException {
        public InvalidJoinToken(String message) {
            super(message);
        }
    }

    /** Sentinel: the seed secret is encoded base64 inside {@link me.prexorjustin.prexorcloud.controller.cluster.state.ClusterMeta}. */
    public static byte[] decodeSeed(String base64) {
        return Base64.getDecoder().decode(base64);
    }
}
