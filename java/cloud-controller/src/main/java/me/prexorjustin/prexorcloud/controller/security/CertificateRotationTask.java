package me.prexorjustin.prexorcloud.controller.security;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import me.prexorjustin.prexorcloud.security.ca.CertificateAuthority;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checks server certificate expiry daily and auto-rotates when the certificate
 * has fewer than {@code renewalThresholdDays} remaining.
 */
public final class CertificateRotationTask implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(CertificateRotationTask.class);

    private final CertificateAuthority ca;
    private final Path serverKeystorePath;
    private final char[] keystorePassword;
    private final int renewalThresholdDays;
    private final int serverCertValidityDays;
    private final List<String> serverSans;
    private final TlsMaterialWatcher reloadHook;

    public CertificateRotationTask(
            CertificateAuthority ca,
            Path serverKeystorePath,
            char[] keystorePassword,
            int renewalThresholdDays,
            int serverCertValidityDays,
            List<String> serverSans,
            TlsMaterialWatcher reloadHook) {
        this.ca = ca;
        this.serverKeystorePath = serverKeystorePath;
        this.keystorePassword = keystorePassword;
        this.renewalThresholdDays = renewalThresholdDays;
        this.serverCertValidityDays = serverCertValidityDays;
        this.serverSans = serverSans;
        this.reloadHook = reloadHook;
    }

    @Override
    public void run() {
        try {
            checkAndRotate();
        } catch (Exception e) {
            logger.error("Certificate rotation check failed", e);
        }
    }

    private void checkAndRotate() throws Exception {
        if (!Files.exists(serverKeystorePath)) {
            return;
        }

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(serverKeystorePath)) {
            ks.load(in, keystorePassword);
        }

        String alias = ks.aliases().nextElement();
        X509Certificate cert = (X509Certificate) ks.getCertificate(alias);
        Instant expiry = cert.getNotAfter().toInstant();
        long daysUntilExpiry = Duration.between(Instant.now(), expiry).toDays();

        if (daysUntilExpiry > renewalThresholdDays) {
            logger.debug(
                    "Server certificate valid for {} more days (threshold: {})", daysUntilExpiry, renewalThresholdDays);
            return;
        }

        logger.warn(
                "Server certificate expires in {} days (threshold: {}), rotating...",
                daysUntilExpiry,
                renewalThresholdDays);

        var newCert = ca.issueServerCertificate("prexorcloud-controller", serverSans, serverCertValidityDays);
        newCert.savePkcs12(serverKeystorePath, keystorePassword);

        logger.info("Server certificate rotated successfully. New expiry: {} days from now", serverCertValidityDays);

        if (reloadHook != null) {
            reloadHook.forceReload();
        }
    }
}
