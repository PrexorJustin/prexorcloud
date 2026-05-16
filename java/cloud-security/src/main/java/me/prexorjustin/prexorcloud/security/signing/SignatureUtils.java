package me.prexorjustin.prexorcloud.security.signing;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.HexFormat;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class SignatureUtils {

    private static final Logger LOGGER = LoggerFactory.getLogger(SignatureUtils.class);

    private SignatureUtils() {}

    static PublicKey parsePublicKey(byte[] derBytes) {
        try {
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(derBytes));
        } catch (GeneralSecurityException _) {
        }
        try {
            return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(derBytes));
        } catch (GeneralSecurityException _) {
        }
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(derBytes));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("unsupported public key (sha256=" + sha256Hex(derBytes) + ")", e);
        }
    }

    static boolean verify(PublicKey key, byte[] payload, byte[] signatureBytes) {
        String algorithm =
                switch (key.getAlgorithm().toUpperCase(Locale.ROOT)) {
                    case "RSA" -> "SHA256withRSA";
                    case "EC", "ECDSA" -> "SHA256withECDSA";
                    case "ED25519" -> "Ed25519";
                    default -> null;
                };
        if (algorithm == null) {
            return false;
        }
        try {
            Signature signature = Signature.getInstance(algorithm);
            signature.initVerify(key);
            signature.update(payload);
            return signature.verify(signatureBytes);
        } catch (GeneralSecurityException ex) {
            LOGGER.debug("signature verify failed for key {}: {}", key.getAlgorithm(), ex.getMessage());
            return false;
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (GeneralSecurityException _) {
            return "unknown";
        }
    }
}
