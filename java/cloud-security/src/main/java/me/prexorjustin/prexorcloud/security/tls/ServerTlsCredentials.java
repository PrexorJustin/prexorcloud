package me.prexorjustin.prexorcloud.security.tls;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;

/**
 * Creates gRPC server TLS credentials with mTLS (mutual TLS) support. Uses the
 * shaded Netty bundled with grpc-netty-shaded.
 */
public final class ServerTlsCredentials {

    private ServerTlsCredentials() {}

    /**
     * Build a Netty SslContext for gRPC server with mutual TLS.
     *
     * @param serverKeystorePath
     *            PKCS12 with server cert + key
     * @param password
     *            keystore password
     * @param caPemPath
     *            CA certificate PEM for verifying client certs
     * @return SslContext for use with NettyServerBuilder
     */
    public static SslContext build(Path serverKeystorePath, char[] password, Path caPemPath) throws Exception {
        // Extract server private key and cert chain from PKCS12
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(serverKeystorePath)) {
            ks.load(in, password);
        }
        String alias = ks.aliases().nextElement();
        PrivateKey privateKey = (PrivateKey) ks.getKey(alias, password);
        Certificate[] chain = ks.getCertificateChain(alias);
        X509Certificate[] certChain = new X509Certificate[chain.length];
        for (int i = 0; i < chain.length; i++) {
            certChain[i] = (X509Certificate) chain[i];
        }

        // CA cert for client verification
        InputStream caStream = Files.newInputStream(caPemPath);

        SslContextBuilder builder = SslContextBuilder.forServer(privateKey, certChain);
        GrpcSslContexts.configure(builder);
        return builder.trustManager(caStream).clientAuth(ClientAuth.OPTIONAL).build();
    }
}
