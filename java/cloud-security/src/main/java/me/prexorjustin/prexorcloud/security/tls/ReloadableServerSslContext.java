package me.prexorjustin.prexorcloud.security.tls;

import java.io.InputStream;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.KeyManagerFactorySpi;
import javax.net.ssl.ManagerFactoryParameters;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.TrustManagerFactorySpi;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509ExtendedTrustManager;

import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gRPC server {@link SslContext} that reloads its key material and CA trust
 * material at runtime without rebuilding the surrounding gRPC server.
 *
 * <p>The {@link SslContext} is built once with delegating
 * {@link X509ExtendedKeyManager} and {@link X509ExtendedTrustManager} instances
 * that hold an {@link AtomicReference} to the active backing managers. The
 * {@link SslProvider#JDK} provider is used so per-handshake delegate lookups
 * pick up the new material — the OpenSSL provider would bake the leaf
 * material into native config at build time and defeat live reload.
 *
 * <p>The trust manager wrapper additionally consults a
 * {@link NodeRevocationCheck} so revoked client certificates are rejected at
 * the TLS layer for newly opened connections.
 */
public final class ReloadableServerSslContext {

    private static final Logger logger = LoggerFactory.getLogger(ReloadableServerSslContext.class);

    private final AtomicReference<X509ExtendedKeyManager> keyManagerRef = new AtomicReference<>();
    private final AtomicReference<X509ExtendedTrustManager> trustManagerRef = new AtomicReference<>();
    private final NodeRevocationCheck revocationCheck;
    private final SslContext sslContext;

    private ReloadableServerSslContext(NodeRevocationCheck revocationCheck) throws Exception {
        this.revocationCheck = revocationCheck;

        X509ExtendedKeyManager keyDelegate = new DelegatingKeyManager(keyManagerRef);
        X509ExtendedTrustManager trustDelegate = new RevocationAwareTrustManager(trustManagerRef, revocationCheck);

        SslContextBuilder builder = SslContextBuilder.forServer(new SingleKeyManagerFactory(keyDelegate))
                .trustManager(new SingleTrustManagerFactory(trustDelegate))
                .clientAuth(ClientAuth.OPTIONAL)
                .sslProvider(SslProvider.JDK);
        GrpcSslContexts.configure(builder, SslProvider.JDK);
        this.sslContext = builder.build();
    }

    /**
     * Build a reloadable context. The {@code keystorePath} and {@code caPemPath}
     * are loaded immediately to populate the initial managers.
     */
    public static ReloadableServerSslContext build(
            Path keystorePath, char[] password, Path caPemPath, NodeRevocationCheck revocationCheck) throws Exception {
        ReloadableServerSslContext ctx =
                new ReloadableServerSslContext(revocationCheck == null ? NodeRevocationCheck.NONE : revocationCheck);
        ctx.swapMaterial(keystorePath, password, caPemPath);
        return ctx;
    }

    /** The Netty {@link SslContext} for use with {@code NettyServerBuilder.sslContext()}. */
    public SslContext sslContext() {
        return sslContext;
    }

    /**
     * Reload the inner key + trust material from disk. New TLS handshakes
     * after this call use the new material; in-flight handshakes complete
     * with whichever delegate they captured.
     */
    public synchronized void reload(Path keystorePath, char[] password, Path caPemPath) throws Exception {
        swapMaterial(keystorePath, password, caPemPath);
        logger.info("Reloaded gRPC TLS material from {} (CA: {})", keystorePath, caPemPath);
    }

    private void swapMaterial(Path keystorePath, char[] password, Path caPemPath) throws Exception {
        keyManagerRef.set(loadKeyManager(keystorePath, password));
        trustManagerRef.set(loadTrustManager(caPemPath));
    }

    private static X509ExtendedKeyManager loadKeyManager(Path keystorePath, char[] password) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystorePath)) {
            ks.load(in, password);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, password);
        for (var km : kmf.getKeyManagers()) {
            if (km instanceof X509ExtendedKeyManager x) {
                return x;
            }
        }
        throw new IllegalStateException("No X509ExtendedKeyManager produced from " + keystorePath);
    }

    private static X509ExtendedTrustManager loadTrustManager(Path caPemPath) throws Exception {
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        var certFactory = java.security.cert.CertificateFactory.getInstance("X.509");
        try (InputStream in = Files.newInputStream(caPemPath)) {
            var caCert = certFactory.generateCertificate(in);
            trustStore.setCertificateEntry("ca", caCert);
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        for (var tm : tmf.getTrustManagers()) {
            if (tm instanceof X509ExtendedTrustManager x) {
                return x;
            }
        }
        throw new IllegalStateException("No X509ExtendedTrustManager produced from " + caPemPath);
    }

    private static final class DelegatingKeyManager extends X509ExtendedKeyManager {

        private final AtomicReference<X509ExtendedKeyManager> ref;

        DelegatingKeyManager(AtomicReference<X509ExtendedKeyManager> ref) {
            this.ref = ref;
        }

        private X509ExtendedKeyManager d() {
            X509ExtendedKeyManager km = ref.get();
            if (km == null) {
                throw new IllegalStateException("KeyManager not initialized");
            }
            return km;
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return d().getClientAliases(keyType, issuers);
        }

        @Override
        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
            return d().chooseClientAlias(keyType, issuers, socket);
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return d().getServerAliases(keyType, issuers);
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return d().chooseServerAlias(keyType, issuers, socket);
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return d().getCertificateChain(alias);
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return d().getPrivateKey(alias);
        }

        @Override
        public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
            return d().chooseEngineClientAlias(keyType, issuers, engine);
        }

        @Override
        public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
            return d().chooseEngineServerAlias(keyType, issuers, engine);
        }
    }

    private static final class RevocationAwareTrustManager extends X509ExtendedTrustManager {

        private final AtomicReference<X509ExtendedTrustManager> ref;
        private final NodeRevocationCheck revocationCheck;

        RevocationAwareTrustManager(
                AtomicReference<X509ExtendedTrustManager> ref, NodeRevocationCheck revocationCheck) {
            this.ref = ref;
            this.revocationCheck = revocationCheck;
        }

        private X509ExtendedTrustManager d() {
            X509ExtendedTrustManager tm = ref.get();
            if (tm == null) {
                throw new IllegalStateException("TrustManager not initialized");
            }
            return tm;
        }

        private void checkRevoked(X509Certificate[] chain) throws CertificateException {
            if (chain == null || chain.length == 0) {
                return;
            }
            X509Certificate leaf = chain[0];
            BigInteger serial = leaf.getSerialNumber();
            String cn = extractCn(leaf.getSubjectX500Principal().getName());
            if (revocationCheck.isRevoked(serial, cn)) {
                throw new CertificateException(
                        "Client certificate revoked (CN=" + cn + ", serial=" + serial.toString(16) + ")");
            }
        }

        private static String extractCn(String distinguishedName) {
            if (distinguishedName == null) {
                return "";
            }
            for (String part : distinguishedName.split(",")) {
                String trimmed = part.trim();
                if (trimmed.regionMatches(true, 0, "CN=", 0, 3)) {
                    return trimmed.substring(3);
                }
            }
            return "";
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            d().checkClientTrusted(chain, authType);
            checkRevoked(chain);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            d().checkServerTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return d().getAcceptedIssuers();
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            d().checkClientTrusted(chain, authType, socket);
            checkRevoked(chain);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            d().checkServerTrusted(chain, authType, socket);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            d().checkClientTrusted(chain, authType, engine);
            checkRevoked(chain);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            d().checkServerTrusted(chain, authType, engine);
        }
    }

    private static final class SingleKeyManagerFactory extends KeyManagerFactory {

        SingleKeyManagerFactory(X509ExtendedKeyManager km) {
            super(
                    new SingleKeyManagerFactorySpi(km),
                    Security.getProviders()[0],
                    KeyManagerFactory.getDefaultAlgorithm());
        }
    }

    private static final class SingleKeyManagerFactorySpi extends KeyManagerFactorySpi {

        private final KeyManager[] managers;

        SingleKeyManagerFactorySpi(X509ExtendedKeyManager km) {
            this.managers = new KeyManager[] {km};
        }

        @Override
        protected void engineInit(KeyStore ks, char[] password) {
            // no-op: managers are pre-built
        }

        @Override
        protected void engineInit(ManagerFactoryParameters spec) {
            // no-op: managers are pre-built
        }

        @Override
        protected KeyManager[] engineGetKeyManagers() {
            return Arrays.copyOf(managers, managers.length);
        }
    }

    private static final class SingleTrustManagerFactory extends TrustManagerFactory {

        SingleTrustManagerFactory(X509ExtendedTrustManager tm) {
            super(
                    new SingleTrustManagerFactorySpi(tm),
                    Security.getProviders()[0],
                    TrustManagerFactory.getDefaultAlgorithm());
        }
    }

    private static final class SingleTrustManagerFactorySpi extends TrustManagerFactorySpi {

        private final TrustManager[] managers;

        SingleTrustManagerFactorySpi(X509ExtendedTrustManager tm) {
            this.managers = new TrustManager[] {tm};
        }

        @Override
        protected void engineInit(KeyStore ks) {
            // no-op
        }

        @Override
        protected void engineInit(ManagerFactoryParameters spec) {
            // no-op
        }

        @Override
        protected TrustManager[] engineGetTrustManagers() {
            return Arrays.copyOf(managers, managers.length);
        }
    }
}
