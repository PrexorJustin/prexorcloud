package me.prexorjustin.prexorcloud.security.tls;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import javax.net.ssl.*;

/**
 * Builds SSLContext instances from PKCS12 keystores for mTLS.
 */
public final class TlsContextBuilder {

    private TlsContextBuilder() {}

    /**
     * Build an SSLContext with both key material (our cert) and trust material (CA
     * cert).
     *
     * @param keystorePath
     *            PKCS12 keystore containing our cert + private key
     * @param password
     *            keystore password
     * @param caPemPath
     *            CA certificate in PEM format (for trust)
     * @return configured SSLContext
     */
    public static SSLContext build(Path keystorePath, char[] password, Path caPemPath) throws Exception {
        // Load key material
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystorePath)) {
            keyStore.load(in, password);
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, password);

        // Load trust material (CA cert)
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);

        var certFactory = java.security.cert.CertificateFactory.getInstance("X.509");
        try (InputStream in = Files.newInputStream(caPemPath)) {
            var caCert = certFactory.generateCertificate(in);
            trustStore.setCertificateEntry("ca", caCert);
        }

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        SSLContext ctx = SSLContext.getInstance("TLSv1.3");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return ctx;
    }

    /**
     * Build an SSLContext from in-memory PKCS12 bytes and CA PEM bytes. Used during
     * bootstrap when files haven't been saved to disk yet.
     */
    public static SSLContext build(byte[] pkcs12Bytes, char[] password, byte[] caPemBytes) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(new java.io.ByteArrayInputStream(pkcs12Bytes), password);

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, password);

        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        var certFactory = java.security.cert.CertificateFactory.getInstance("X.509");
        var caCert = certFactory.generateCertificate(new java.io.ByteArrayInputStream(caPemBytes));
        trustStore.setCertificateEntry("ca", caCert);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        SSLContext ctx = SSLContext.getInstance("TLSv1.3");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return ctx;
    }
}
