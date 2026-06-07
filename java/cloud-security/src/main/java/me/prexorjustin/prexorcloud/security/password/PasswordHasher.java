package me.prexorjustin.prexorcloud.security.password;

import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;

/**
 * Argon2id password hashing. Parameters: 64 MB memory, 3 iterations, 1
 * parallelism.
 */
public final class PasswordHasher {

    private static final Argon2 ARGON2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id);
    private static final int ITERATIONS = 3;
    private static final int MEMORY_KB = 65536; // 64 MB
    private static final int PARALLELISM = 1;

    private PasswordHasher() {}

    /**
     * Hash a password using Argon2id.
     *
     * @param password
     *            plaintext password
     * @return encoded hash string
     */
    public static String hash(String password) {
        return ARGON2.hash(ITERATIONS, MEMORY_KB, PARALLELISM, password.toCharArray());
    }

    /**
     * Verify a password against an Argon2id hash.
     *
     * @param password
     *            plaintext password
     * @param hash
     *            encoded hash from {@link #hash(String)}
     * @return true if the password matches
     */
    public static boolean verify(String password, String hash) {
        return ARGON2.verify(hash, password.toCharArray());
    }
}
