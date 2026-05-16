package me.prexorjustin.prexorcloud.security.tls;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.netty.shaded.io.netty.handler.ssl.util.InsecureTrustManagerFactory;

/**
 * Creates gRPC client TLS credentials with mutual TLS support. Uses the shaded
 * Netty bundled with grpc-netty-shaded.
 */
public final class ClientTlsCredentials {

    private ClientTlsCredentials() {}

    /**
     * Build a Netty SslContext for gRPC client with mutual TLS.
     *
     * @param clientKeystorePath
     *            PKCS12 with client (node) cert + key
     * @param password
     *            keystore password
     * @param caPemPath
     *            CA certificate PEM for verifying server cert
     * @return SslContext for use with NettyChannelBuilder
     */
    public static SslContext build(Path clientKeystorePath, char[] password, Path caPemPath) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(clientKeystorePath)) {
            ks.load(in, password);
        }
        String alias = ks.aliases().nextElement();
        PrivateKey privateKey = (PrivateKey) ks.getKey(alias, password);
        Certificate[] chain = ks.getCertificateChain(alias);
        X509Certificate[] certChain = new X509Certificate[chain.length];
        for (int i = 0; i < chain.length; i++) {
            certChain[i] = (X509Certificate) chain[i];
        }

        try (InputStream caStream = Files.newInputStream(caPemPath)) {
            return GrpcSslContexts.forClient()
                    .keyManager(privateKey, certChain)
                    .trustManager(caStream)
                    .build();
        }
    }

    /**
     * Build a TLS-only SslContext that trusts any server certificate. Used during
     * bootstrap when the daemon doesn't yet have the CA cert. The join token
     * provides authentication in this phase.
     */
    public static SslContext buildInsecure() throws Exception {
        return GrpcSslContexts.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .build();
    }
}
