package me.prexorjustin.prexorcloud.controller.cluster;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

import me.prexorjustin.prexorcloud.common.util.FilePermissions;

/**
 * On-disk persistence for this controller's cluster-CA-signed TLS material:
 * the cluster CA certificate, this node's signed leaf cert, and this node's
 * private key. PEM files — single-purpose, debuggable with openssl, no
 * keystore-password coupling to anything else.
 *
 * <p>Day-0 controllers mint their own materials and write them here; Day-N
 * controllers receive them through {@link ClusterJoinFlow} and write them here;
 * subsequent restarts on either side load from here and start Raft with TLS
 * without needing to re-issue.
 */
public final class LocalClusterMaterials {

    public static final String CA_CERT_FILE = "ca.crt";
    public static final String LEAF_CERT_FILE = "leaf.crt";
    public static final String LEAF_KEY_FILE = "leaf.key";

    private final Path dir;

    public LocalClusterMaterials(Path dir) {
        this.dir = dir;
    }

    /** Whether all three artefacts are present on disk and look loadable. */
    public boolean exists() {
        return Files.isRegularFile(dir.resolve(CA_CERT_FILE))
                && Files.isRegularFile(dir.resolve(LEAF_CERT_FILE))
                && Files.isRegularFile(dir.resolve(LEAF_KEY_FILE));
    }

    public Path directory() {
        return dir;
    }

    /**
     * Read all three artefacts back into runtime objects. Throws {@link IOException}
     * if any file is missing or malformed — callers higher up surface this as a boot
     * refusal.
     */
    public Loaded load() throws IOException {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate caCert = (X509Certificate) cf.generateCertificate(new java.io.ByteArrayInputStream(
                    decodePem(Files.readString(dir.resolve(CA_CERT_FILE)), "CERTIFICATE")));
            X509Certificate leafCert = (X509Certificate) cf.generateCertificate(new java.io.ByteArrayInputStream(
                    decodePem(Files.readString(dir.resolve(LEAF_CERT_FILE)), "CERTIFICATE")));
            byte[] keyDer = decodePem(Files.readString(dir.resolve(LEAF_KEY_FILE)), "PRIVATE KEY");
            PrivateKey leafKey = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(keyDer));
            return new Loaded(caCert, leafCert, leafKey);
        } catch (CertificateException | NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IOException("failed to load cluster TLS materials from " + dir, e);
        }
    }

    /**
     * Persist all three artefacts atomically-ish: each file is written, then chmod'd
     * to owner-only. We don't fsync — the worst case on crash is "boot finds a
     * half-written file, refuses to start, operator re-runs the wizard" which is
     * acceptable for a control-plane bootstrap.
     */
    public void persist(X509Certificate caCert, X509Certificate leafCert, PrivateKey leafKey) throws IOException {
        try {
            Files.createDirectories(dir);
            FilePermissions.setOwnerOnly(dir);
            writePem(dir.resolve(CA_CERT_FILE), "CERTIFICATE", caCert.getEncoded());
            writePem(dir.resolve(LEAF_CERT_FILE), "CERTIFICATE", leafCert.getEncoded());
            // PrivateKey.getEncoded() returns PKCS#8 DER for EC keys.
            writePem(dir.resolve(LEAF_KEY_FILE), "PRIVATE KEY", leafKey.getEncoded());
        } catch (CertificateException e) {
            throw new IOException("encoding cluster TLS materials", e);
        }
    }

    /**
     * Best-effort delete of all three files. Used when a join attempt is being
     * retried — we'd rather start from a clean slate than blend stale leaf cert
     * with a freshly-issued one.
     */
    public void purge() throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        Files.deleteIfExists(dir.resolve(CA_CERT_FILE));
        Files.deleteIfExists(dir.resolve(LEAF_CERT_FILE));
        Files.deleteIfExists(dir.resolve(LEAF_KEY_FILE));
    }

    public record Loaded(X509Certificate caCert, X509Certificate leafCert, PrivateKey leafKey) {}

    private static void writePem(Path path, String type, byte[] der) throws IOException {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        String pem = "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
        Files.writeString(path, pem);
        FilePermissions.setOwnerReadWrite(path);
    }

    private static byte[] decodePem(String pem, String type) throws IOException {
        String begin = "-----BEGIN " + type + "-----";
        String end = "-----END " + type + "-----";
        int b = pem.indexOf(begin);
        int e = pem.indexOf(end);
        if (b < 0 || e < 0 || e <= b) {
            throw new IOException("PEM missing " + type + " block");
        }
        String body = pem.substring(b + begin.length(), e).replaceAll("\\s", "");
        try {
            return Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException ex) {
            throw new IOException("PEM body not valid base64", ex);
        }
    }
}
