package me.prexorjustin.prexorcloud.controller.cluster.raft;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Minimal in-memory CA for the Ratis TLS spike. Generates a self-signed CA EC keypair and
 * issues leaf certs that can act as both server and client (Ratis mTLS needs both EKUs on
 * the peer cert). Does not touch disk; everything stays in memory for the test's lifetime.
 *
 * <p>Production cluster CA reuses {@code cloud-security}'s {@code CertificateAuthority}, which
 * persists to a PKCS#12 keystore and exposes the same issue-on-CSR flow — this helper is
 * intentionally tiny to keep the spike focused on the Ratis side.
 */
final class InMemoryCa {

    private static final String SIG_ALG = "SHA256withECDSA";

    static {
        if (java.security.Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            java.security.Security.addProvider(new BouncyCastleProvider());
        }
    }

    private final KeyPair caKeyPair;
    private final X509Certificate caCert;

    private InMemoryCa(KeyPair caKeyPair, X509Certificate caCert) {
        this.caKeyPair = caKeyPair;
        this.caCert = caCert;
    }

    static InMemoryCa create() throws Exception {
        KeyPair kp = generateEcKeyPair();
        Instant now = Instant.now();
        X500Name name = new X500Name("CN=prexorcloud-spike-ca");
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                name,
                serial(),
                Date.from(now.minus(Duration.ofMinutes(5))),
                Date.from(now.plus(Duration.ofDays(1))),
                name,
                kp.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        builder.addExtension(
                Extension.keyUsage,
                true,
                new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign | KeyUsage.digitalSignature));
        ContentSigner signer = new JcaContentSignerBuilder(SIG_ALG)
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(kp.getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .getCertificate(builder.build(signer));
        return new InMemoryCa(kp, cert);
    }

    IssuedCert issue(String commonName) throws Exception {
        KeyPair kp = generateEcKeyPair();
        Instant now = Instant.now();
        X500Name issuer = new X500Name(caCert.getSubjectX500Principal().getName());
        X500Name subject = new X500Name("CN=" + commonName);
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                issuer,
                serial(),
                Date.from(now.minus(Duration.ofMinutes(5))),
                Date.from(now.plus(Duration.ofDays(1))),
                subject,
                kp.getPublic());
        builder.addExtension(Extension.basicConstraints, false, new BasicConstraints(false));
        builder.addExtension(
                Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
        builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(new KeyPurposeId[] {
            KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth
        }));
        builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(new GeneralName[] {
            new GeneralName(GeneralName.dNSName, "localhost"),
            new GeneralName(GeneralName.iPAddress, "127.0.0.1"),
            new GeneralName(GeneralName.dNSName, commonName)
        }));
        ContentSigner signer = new JcaContentSignerBuilder(SIG_ALG)
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(caKeyPair.getPrivate());
        X509Certificate leaf = new JcaX509CertificateConverter()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .getCertificate(builder.build(signer));
        return new IssuedCert(kp.getPrivate(), leaf);
    }

    X509Certificate certificate() {
        return caCert;
    }

    private static KeyPair generateEcKeyPair() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
        g.initialize(256, new SecureRandom());
        return g.generateKeyPair();
    }

    private static BigInteger serial() {
        byte[] bytes = new byte[20];
        new SecureRandom().nextBytes(bytes);
        return new BigInteger(1, bytes);
    }

    record IssuedCert(PrivateKey privateKey, X509Certificate certificate) {}
}
