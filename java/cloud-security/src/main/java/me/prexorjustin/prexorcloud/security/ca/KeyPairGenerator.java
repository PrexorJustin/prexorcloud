package me.prexorjustin.prexorcloud.security.ca;

import java.security.InvalidAlgorithmParameterException;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.spec.ECGenParameterSpec;

/**
 * Generates EC P-256 key pairs for use in certificates and TLS.
 */
public final class KeyPairGenerator {

    private KeyPairGenerator() {}

    /**
     * Generate an EC P-256 key pair.
     */
    public static KeyPair generateEC() {
        try {
            var gen = java.security.KeyPairGenerator.getInstance("EC");
            gen.initialize(new ECGenParameterSpec("secp256r1"));
            return gen.generateKeyPair();
        } catch (NoSuchAlgorithmException | InvalidAlgorithmParameterException e) {
            throw new RuntimeException("Failed to generate EC key pair", e);
        }
    }
}
