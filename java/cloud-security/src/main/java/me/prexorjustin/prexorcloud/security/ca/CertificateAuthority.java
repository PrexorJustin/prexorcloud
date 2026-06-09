package me.prexorjustin.prexorcloud.security.ca;

import java.io.*;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import me.prexorjustin.prexorcloud.common.util.FilePermissions;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Self-managed Certificate Authority using BouncyCastle. Generates a
 * self-signed CA certificate and issues server/node certificates.
 */
public final class CertificateAuthority {

    private static final Logger logger = LoggerFactory.getLogger(CertificateAuthority.class);
    private static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";
    private static final String KEYSTORE_TYPE = "PKCS12";

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private final KeyPair caKeyPair;
    private final X509Certificate caCertificate;

    private CertificateAuthority(KeyPair caKeyPair, X509Certificate caCertificate) {
        this.caKeyPair = caKeyPair;
        this.caCertificate = caCertificate;
    }

    /**
     * Load an existing CA from a PKCS12 keystore, or generate a new one.
     *
     * @param keystorePath
     *            path to the ca.p12 file
     * @param password
     *            keystore password
     * @param commonName
     *            CA common name (e.g. "PrexorCloud CA")
     * @param validityDays
     *            CA certificate validity in days
     * @return the CertificateAuthority instance
     */
    public static CertificateAuthority loadOrCreate(
            Path keystorePath, char[] password, String commonName, int validityDays) throws Exception {
        if (Files.exists(keystorePath)) {
            return load(keystorePath, password);
        }
        return create(keystorePath, password, commonName, validityDays);
    }

    /**
     * Load an existing CA from PKCS12.
     */
    public static CertificateAuthority load(Path keystorePath, char[] password) throws Exception {
        logger.debug("Loading CA from {}", keystorePath);
        KeyStore ks = KeyStore.getInstance(KEYSTORE_TYPE);
        try (InputStream in = Files.newInputStream(keystorePath)) {
            ks.load(in, password);
        }
        String alias = ks.aliases().nextElement();
        PrivateKey privateKey = (PrivateKey) ks.getKey(alias, password);
        X509Certificate cert = (X509Certificate) ks.getCertificate(alias);
        KeyPair keyPair = new KeyPair(cert.getPublicKey(), privateKey);
        return new CertificateAuthority(keyPair, cert);
    }

    /**
     * Generate a new self-signed CA and save to PKCS12.
     */
    public static CertificateAuthority create(Path keystorePath, char[] password, String commonName, int validityDays)
            throws Exception {
        CertificateAuthority ca = createInMemory(commonName, validityDays);

        Files.createDirectories(keystorePath.getParent());
        KeyStore ks = KeyStore.getInstance(KEYSTORE_TYPE);
        ks.load(null, password);
        ks.setKeyEntry("ca", ca.caKeyPair.getPrivate(), password, new Certificate[] {ca.caCertificate});
        try (OutputStream out = Files.newOutputStream(keystorePath)) {
            ks.store(out, password);
        }
        FilePermissions.setOwnerReadWrite(keystorePath);

        logger.info("CA created. Fingerprint: {}", ca.fingerprint());
        return ca;
    }

    /**
     * Generate a new self-signed CA without touching disk. The cluster control
     * plane uses this to mint the cluster-wide CA and ship its bytes through
     * the Raft state machine instead of a local keystore.
     */
    public static CertificateAuthority createInMemory(String commonName, int validityDays) throws Exception {
        logger.info("Generating new in-memory CA: CN={}", commonName);

        KeyPair keyPair = KeyPairGenerator.generateEC();
        X500Name issuer = new X500Name("CN=" + commonName);
        Instant now = Instant.now();
        Instant expiry = now.plus(Duration.ofDays(validityDays));

        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                issuer, generateSerialNumber(), Date.from(now), Date.from(expiry), issuer, keyPair.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));

        ContentSigner signer = new JcaContentSignerBuilder(SIGNATURE_ALGORITHM)
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(keyPair.getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .getCertificate(builder.build(signer));

        return new CertificateAuthority(keyPair, cert);
    }

    /**
     * Reconstruct a CA from the X.509 DER cert bytes + PKCS#8 DER private key
     * bytes. Used by controllers that load their CA material from the cluster
     * Raft state instead of a local keystore.
     */
    public static CertificateAuthority loadFromDer(byte[] certDer, byte[] privateKeyPkcs8Der) throws Exception {
        var certFactory = java.security.cert.CertificateFactory.getInstance("X.509");
        X509Certificate cert =
                (X509Certificate) certFactory.generateCertificate(new java.io.ByteArrayInputStream(certDer));
        var keySpec = new java.security.spec.PKCS8EncodedKeySpec(privateKeyPkcs8Der);
        PrivateKey priv = java.security.KeyFactory.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME)
                .generatePrivate(keySpec);
        return new CertificateAuthority(new KeyPair(cert.getPublicKey(), priv), cert);
    }

    /**
     * Sign an externally-supplied PKCS#10 CSR with this CA. The subject and
     * public key come from the CSR; SANs are merged from the explicit
     * {@code extraSans} list (the cluster control plane uses this to pin the
     * joiner's actual {@code raft/rest/grpc} hosts rather than trusting
     * arbitrary CSR extensions). The signed leaf carries both
     * {@code id_kp_serverAuth} and {@code id_kp_clientAuth} EKUs — controllers
     * act as both in cluster traffic.
     */
    public X509Certificate signCsr(byte[] csrDer, java.util.List<String> extraSans, int validityDays) throws Exception {
        org.bouncycastle.pkcs.PKCS10CertificationRequest csr =
                new org.bouncycastle.pkcs.PKCS10CertificationRequest(csrDer);
        java.security.PublicKey subjectPublicKey = new org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest(csr)
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .getPublicKey();

        X500Name issuer = new X500Name(caCertificate.getSubjectX500Principal().getName());
        X500Name subject = csr.getSubject();
        Instant now = Instant.now();
        Instant expiry = now.plus(Duration.ofDays(validityDays));

        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                issuer, generateSerialNumber(), Date.from(now), Date.from(expiry), subject, subjectPublicKey);
        builder.addExtension(Extension.basicConstraints, false, new BasicConstraints(false));
        builder.addExtension(
                Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
        builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(new KeyPurposeId[] {
            KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth
        }));
        if (extraSans != null && !extraSans.isEmpty()) {
            GeneralName[] names = extraSans.stream()
                    .filter(s -> s != null && !s.isBlank())
                    .map(san -> isIpAddress(san)
                            ? new GeneralName(GeneralName.iPAddress, san)
                            : new GeneralName(GeneralName.dNSName, san))
                    .toArray(GeneralName[]::new);
            if (names.length > 0) {
                builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(names));
            }
        }

        ContentSigner signer = new JcaContentSignerBuilder(SIGNATURE_ALGORITHM)
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(caKeyPair.getPrivate());
        return new JcaX509CertificateConverter()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .getCertificate(builder.build(signer));
    }

    /**
     * Issue a server certificate signed by this CA.
     *
     * @param commonName
     *            server CN (e.g. "prexorcloud-controller")
     * @param sans
     *            Subject Alternative Names (DNS names and IPs)
     * @param validityDays
     *            certificate validity
     * @return issued certificate + its key pair
     */
    public IssuedCertificate issueServerCertificate(String commonName, List<String> sans, int validityDays)
            throws Exception {
        return issueCertificate(
                commonName,
                sans,
                validityDays,
                KeyUsage.digitalSignature | KeyUsage.keyEncipherment,
                KeyPurposeId.id_kp_serverAuth);
    }

    /**
     * Issue a client (node) certificate signed by this CA.
     *
     * @param nodeId
     *            node identifier used as CN and SAN
     * @param validityDays
     *            certificate validity
     * @return issued certificate + its key pair
     */
    public IssuedCertificate issueNodeCertificate(String nodeId, int validityDays) throws Exception {
        return issueCertificate(
                nodeId,
                List.of(nodeId),
                validityDays,
                KeyUsage.digitalSignature | KeyUsage.keyEncipherment,
                KeyPurposeId.id_kp_clientAuth);
    }

    /**
     * Issue a cluster peer certificate signed by this CA. Carries both
     * {@code id_kp_serverAuth} and {@code id_kp_clientAuth} EKUs because a
     * controller acts as both server and client to its peers in cluster
     * (Ratis + controller gRPC) traffic.
     */
    public IssuedCertificate issueClusterPeerCertificate(String commonName, List<String> sans, int validityDays)
            throws Exception {
        return issueCertificate(
                commonName,
                sans,
                validityDays,
                KeyUsage.digitalSignature | KeyUsage.keyEncipherment,
                new KeyPurposeId[] {KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth});
    }

    private IssuedCertificate issueCertificate(
            String commonName, List<String> sans, int validityDays, int keyUsageBits, KeyPurposeId extendedKeyUsage)
            throws Exception {
        return issueCertificate(commonName, sans, validityDays, keyUsageBits, new KeyPurposeId[] {extendedKeyUsage});
    }

    private IssuedCertificate issueCertificate(
            String commonName, List<String> sans, int validityDays, int keyUsageBits, KeyPurposeId[] extendedKeyUsages)
            throws Exception {
        KeyPair subjectKeyPair = KeyPairGenerator.generateEC();
        X500Name issuerName =
                new X500Name(caCertificate.getSubjectX500Principal().getName());
        X500Name subjectName = new X500Name("CN=" + commonName);
        Instant now = Instant.now();
        Instant expiry = now.plus(Duration.ofDays(validityDays));

        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                issuerName,
                generateSerialNumber(),
                Date.from(now),
                Date.from(expiry),
                subjectName,
                subjectKeyPair.getPublic());

        builder.addExtension(Extension.basicConstraints, false, new BasicConstraints(false));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(keyUsageBits));
        builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(extendedKeyUsages));

        // Build SANs
        if (sans != null && !sans.isEmpty()) {
            GeneralName[] names = sans.stream()
                    .map(san -> {
                        if (isIpAddress(san)) {
                            return new GeneralName(GeneralName.iPAddress, san);
                        }
                        return new GeneralName(GeneralName.dNSName, san);
                    })
                    .toArray(GeneralName[]::new);
            builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(names));
        }

        ContentSigner signer = new JcaContentSignerBuilder(SIGNATURE_ALGORITHM)
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(caKeyPair.getPrivate());

        X509Certificate cert = new JcaX509CertificateConverter()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .getCertificate(builder.build(signer));

        logger.debug("Issued certificate: CN={}, validity={} days", commonName, validityDays);
        return new IssuedCertificate(subjectKeyPair, cert, caCertificate);
    }

    /**
     * Export the CA certificate in PEM format.
     */
    public void exportCaPem(Path pemPath) throws Exception {
        Files.createDirectories(pemPath.getParent());
        try (var writer = Files.newBufferedWriter(pemPath)) {
            writer.write("-----BEGIN CERTIFICATE-----\n");
            String base64 =
                    java.util.Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(caCertificate.getEncoded());
            writer.write(base64);
            writer.write("\n-----END CERTIFICATE-----\n");
        }
        logger.debug("CA PEM exported to {}", pemPath);
    }

    /**
     * SHA-256 fingerprint of the CA certificate.
     */
    public String fingerprint() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(caCertificate.getEncoded());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < digest.length; i++) {
                if (i > 0) sb.append(':');
                sb.append(String.format("%02X", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            logger.warn("Failed to compute CA certificate fingerprint: {}", e.getMessage());
            return "unknown";
        }
    }

    public X509Certificate certificate() {
        return caCertificate;
    }

    public KeyPair keyPair() {
        return caKeyPair;
    }

    private static BigInteger generateSerialNumber() {
        byte[] bytes = new byte[20];
        new SecureRandom().nextBytes(bytes);
        return new BigInteger(1, bytes);
    }

    private static boolean isIpAddress(String value) {
        return value.matches("\\d{1,3}(\\.\\d{1,3}){3}") || value.contains(":");
    }

    /**
     * A certificate issued by this CA, bundled with its key pair and the CA cert.
     */
    public record IssuedCertificate(KeyPair keyPair, X509Certificate certificate, X509Certificate caCertificate) {

        /**
         * Save as PKCS12 keystore.
         */
        public void savePkcs12(Path path, char[] password) throws Exception {
            Files.createDirectories(path.getParent());
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, password);
            ks.setKeyEntry("cert", keyPair.getPrivate(), password, new Certificate[] {certificate, caCertificate});
            try (OutputStream out = Files.newOutputStream(path)) {
                ks.store(out, password);
            }
            FilePermissions.setOwnerReadWrite(path);
        }

        /**
         * Export as PKCS12 bytes (for gRPC bootstrap response).
         */
        public byte[] toPkcs12Bytes(char[] password) throws Exception {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, password);
            ks.setKeyEntry("cert", keyPair.getPrivate(), password, new Certificate[] {certificate, caCertificate});
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ks.store(baos, password);
            return baos.toByteArray();
        }
    }
}
