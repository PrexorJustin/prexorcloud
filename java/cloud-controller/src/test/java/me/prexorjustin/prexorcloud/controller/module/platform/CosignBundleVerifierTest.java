package me.prexorjustin.prexorcloud.controller.module.platform;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import me.prexorjustin.prexorcloud.security.signing.PlatformModuleSignatureVerifier;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CosignBundleVerifierTest {

    @Test
    void verifiesCosignKeyedBundleWithoutCert(@TempDir Path tmp) throws Exception {
        KeyPair keys = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        Path trust = writePemBundle(tmp.resolve("trust.pem"), keys);

        Path jar = Files.write(tmp.resolve("module.jar"), "fake-jar".getBytes());
        String b64Sig = sign(keys.getPrivate(), "fake-jar".getBytes(), "SHA256withRSA");
        Files.writeString(jar.resolveSibling("module.jar.cosign.bundle"), "{\"base64Signature\":\"" + b64Sig + "\"}");

        var verifier = PlatformModuleSignatureVerifier.CosignBundleVerifier.fromPemBundle(trust);
        assertDoesNotThrow(() -> verifier.verify(prepared(jar)));
    }

    @Test
    void rejectsTamperedJar(@TempDir Path tmp) throws Exception {
        KeyPair keys = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        Path trust = writePemBundle(tmp.resolve("trust.pem"), keys);

        Path jar = Files.write(tmp.resolve("module.jar"), "tampered".getBytes());
        String b64Sig = sign(keys.getPrivate(), "original".getBytes(), "SHA256withRSA");
        Files.writeString(jar.resolveSibling("module.jar.cosign.bundle"), "{\"base64Signature\":\"" + b64Sig + "\"}");

        var verifier = PlatformModuleSignatureVerifier.CosignBundleVerifier.fromPemBundle(trust);
        assertThrows(
                PlatformModuleSignatureVerifier.SignatureVerificationException.class,
                () -> verifier.verify(prepared(jar)));
    }

    @Test
    void rejectsMissingBundle(@TempDir Path tmp) throws Exception {
        KeyPair keys = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        Path trust = writePemBundle(tmp.resolve("trust.pem"), keys);
        Path jar = Files.write(tmp.resolve("module.jar"), "x".getBytes());

        var verifier = PlatformModuleSignatureVerifier.CosignBundleVerifier.fromPemBundle(trust);
        var ex = assertThrows(
                PlatformModuleSignatureVerifier.SignatureVerificationException.class,
                () -> verifier.verify(prepared(jar)));
        assert ex.getMessage().contains("missing cosign bundle");
    }

    @Test
    void rejectsBundleWithoutBase64Signature(@TempDir Path tmp) throws Exception {
        KeyPair keys = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        Path trust = writePemBundle(tmp.resolve("trust.pem"), keys);

        Path jar = Files.write(tmp.resolve("module.jar"), "fake".getBytes());
        Files.writeString(jar.resolveSibling("module.jar.cosign.bundle"), "{}");

        var verifier = PlatformModuleSignatureVerifier.CosignBundleVerifier.fromPemBundle(trust);
        assertThrows(
                PlatformModuleSignatureVerifier.SignatureVerificationException.class,
                () -> verifier.verify(prepared(jar)));
    }

    @Test
    void verifiesBundleWithEmbeddedCertAgainstCaTrustRoot(@TempDir Path tmp) throws Exception {
        // Build a tiny CA + leaf cert; trust root holds the CA.
        KeyPair ca = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        X509Certificate caCert = selfSign(ca, "CN=Test CA");

        KeyPair leaf = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        X509Certificate leafCert = signCert(ca, caCert, leaf, "CN=Module Signer");

        Path trust = writePemCerts(tmp.resolve("trust.pem"), List.of(caCert));

        Path jar = Files.write(tmp.resolve("module.jar"), "blob".getBytes());
        String b64Sig = sign(leaf.getPrivate(), "blob".getBytes(), "SHA256withRSA");
        String certPem = certPem(leafCert);
        Files.writeString(
                jar.resolveSibling("module.jar.cosign.bundle"),
                "{\"base64Signature\":\"" + b64Sig + "\",\"cert\":" + jsonString(certPem) + "}");

        var verifier = PlatformModuleSignatureVerifier.CosignBundleVerifier.fromPemBundle(trust);
        assertDoesNotThrow(() -> verifier.verify(prepared(jar)));
    }

    @Test
    void rejectsBundleWithUntrustedCert(@TempDir Path tmp) throws Exception {
        KeyPair trustedCa = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        X509Certificate trustedCaCert = selfSign(trustedCa, "CN=Trusted CA");
        Path trust = writePemCerts(tmp.resolve("trust.pem"), List.of(trustedCaCert));

        // Leaf signed by a *different* CA — chain validation must fail.
        KeyPair otherCa = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        X509Certificate otherCaCert = selfSign(otherCa, "CN=Other CA");
        KeyPair leaf = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        X509Certificate leafCert = signCert(otherCa, otherCaCert, leaf, "CN=Rogue");

        Path jar = Files.write(tmp.resolve("module.jar"), "blob".getBytes());
        String b64Sig = sign(leaf.getPrivate(), "blob".getBytes(), "SHA256withRSA");
        Files.writeString(
                jar.resolveSibling("module.jar.cosign.bundle"),
                "{\"base64Signature\":\"" + b64Sig + "\",\"cert\":" + jsonString(certPem(leafCert)) + "}");

        var verifier = PlatformModuleSignatureVerifier.CosignBundleVerifier.fromPemBundle(trust);
        assertThrows(
                PlatformModuleSignatureVerifier.SignatureVerificationException.class,
                () -> verifier.verify(prepared(jar)));
    }

    @Test
    void rekorVerifiesSetSignedByTrustedKey(@TempDir Path tmp) throws Exception {
        KeyPair signing = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        Path trust = writePemBundle(tmp.resolve("trust.pem"), signing);

        var ecGen = KeyPairGenerator.getInstance("EC");
        ecGen.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair rekorKp = ecGen.generateKeyPair();

        Path jar = Files.write(tmp.resolve("module.jar"), "blob".getBytes());
        String b64Sig = sign(signing.getPrivate(), "blob".getBytes(), "SHA256withRSA");

        long integratedTime = 1714867200L;
        long logIndex = 12345L;
        String logId = "c0d23d6ad406973f9559f3ba2d1ca01f84147d8ffc5b8445c224f98b9591801d";
        String body =
                Base64.getEncoder().encodeToString("{\"kind\":\"hashedrekord\"}".getBytes(StandardCharsets.UTF_8));
        String setB64 = signRekorSet(rekorKp.getPrivate(), body, integratedTime, logId, logIndex);

        String bundleJson = "{\"base64Signature\":\"" + b64Sig + "\",\"rekorBundle\":{"
                + "\"SignedEntryTimestamp\":\"" + setB64 + "\","
                + "\"Payload\":{"
                + "\"body\":\"" + body + "\","
                + "\"integratedTime\":" + integratedTime + ","
                + "\"logIndex\":" + logIndex + ","
                + "\"logID\":\"" + logId + "\"}}}";
        Files.writeString(jar.resolveSibling("module.jar.cosign.bundle"), bundleJson);

        var verifier = PlatformModuleSignatureVerifier.CosignBundleVerifier.fromPemBundle(trust)
                .withRekor(
                        PlatformModuleSignatureVerifier.CosignBundleVerifier.RekorPolicy.REQUIRE_SET,
                        List.<PublicKey>of(rekorKp.getPublic()));
        assertDoesNotThrow(() -> verifier.verify(prepared(jar)));
    }

    @Test
    void rekorRejectsTamperedSet(@TempDir Path tmp) throws Exception {
        KeyPair signing = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        Path trust = writePemBundle(tmp.resolve("trust.pem"), signing);

        var ecGen = KeyPairGenerator.getInstance("EC");
        ecGen.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair rekorKp = ecGen.generateKeyPair();

        Path jar = Files.write(tmp.resolve("module.jar"), "blob".getBytes());
        String b64Sig = sign(signing.getPrivate(), "blob".getBytes(), "SHA256withRSA");

        // Sign over a different logIndex than what we put in the bundle — SET must reject.
        String body = Base64.getEncoder().encodeToString("body".getBytes(StandardCharsets.UTF_8));
        String setB64 = signRekorSet(rekorKp.getPrivate(), body, 1L, "deadbeef", 1L);

        String bundleJson = "{\"base64Signature\":\"" + b64Sig + "\",\"rekorBundle\":{"
                + "\"SignedEntryTimestamp\":\"" + setB64 + "\","
                + "\"Payload\":{"
                + "\"body\":\"" + body + "\","
                + "\"integratedTime\":1,"
                + "\"logIndex\":999," // mismatch — was signed with 1
                + "\"logID\":\"deadbeef\"}}}";
        Files.writeString(jar.resolveSibling("module.jar.cosign.bundle"), bundleJson);

        var verifier = PlatformModuleSignatureVerifier.CosignBundleVerifier.fromPemBundle(trust)
                .withRekor(
                        PlatformModuleSignatureVerifier.CosignBundleVerifier.RekorPolicy.REQUIRE_SET,
                        List.<PublicKey>of(rekorKp.getPublic()));
        assertThrows(
                PlatformModuleSignatureVerifier.SignatureVerificationException.class,
                () -> verifier.verify(prepared(jar)));
    }

    @Test
    void rekorRejectsBundleWithoutRekorBundle(@TempDir Path tmp) throws Exception {
        KeyPair signing = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        Path trust = writePemBundle(tmp.resolve("trust.pem"), signing);

        var ecGen = KeyPairGenerator.getInstance("EC");
        ecGen.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair rekorKp = ecGen.generateKeyPair();

        Path jar = Files.write(tmp.resolve("module.jar"), "blob".getBytes());
        String b64Sig = sign(signing.getPrivate(), "blob".getBytes(), "SHA256withRSA");
        Files.writeString(jar.resolveSibling("module.jar.cosign.bundle"), "{\"base64Signature\":\"" + b64Sig + "\"}");

        var verifier = PlatformModuleSignatureVerifier.CosignBundleVerifier.fromPemBundle(trust)
                .withRekor(
                        PlatformModuleSignatureVerifier.CosignBundleVerifier.RekorPolicy.REQUIRE_SET,
                        List.<PublicKey>of(rekorKp.getPublic()));
        var ex = assertThrows(
                PlatformModuleSignatureVerifier.SignatureVerificationException.class,
                () -> verifier.verify(prepared(jar)));
        assert ex.getMessage().contains("rekorBundle");
    }

    @Test
    void rekorDisabledIgnoresMissingRekorBundle(@TempDir Path tmp) throws Exception {
        // Sanity: with policy=DISABLED, a bundle without rekorBundle still succeeds.
        KeyPair keys = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        Path trust = writePemBundle(tmp.resolve("trust.pem"), keys);

        Path jar = Files.write(tmp.resolve("module.jar"), "blob".getBytes());
        String b64Sig = sign(keys.getPrivate(), "blob".getBytes(), "SHA256withRSA");
        Files.writeString(jar.resolveSibling("module.jar.cosign.bundle"), "{\"base64Signature\":\"" + b64Sig + "\"}");

        var verifier = PlatformModuleSignatureVerifier.CosignBundleVerifier.fromPemBundle(trust);
        assertDoesNotThrow(() -> verifier.verify(prepared(jar)));
    }

    @Test
    void rejectsEmptyTrustRoot(@TempDir Path tmp) throws Exception {
        Path trust = Files.writeString(tmp.resolve("empty.pem"), "no blocks here");
        assertThrows(
                IllegalStateException.class,
                () -> PlatformModuleSignatureVerifier.CosignBundleVerifier.fromPemBundle(trust));
    }

    private static Path writePemBundle(Path path, KeyPair... keys) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (KeyPair pair : keys) {
            sb.append("-----BEGIN PUBLIC KEY-----\n");
            sb.append(Base64.getMimeEncoder(64, "\n".getBytes())
                    .encodeToString(pair.getPublic().getEncoded()));
            sb.append("\n-----END PUBLIC KEY-----\n");
        }
        return Files.writeString(path, sb.toString());
    }

    private static Path writePemCerts(Path path, List<X509Certificate> certs) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (X509Certificate c : certs) {
            sb.append(certPem(c)).append('\n');
        }
        return Files.writeString(path, sb.toString());
    }

    private static String certPem(X509Certificate cert) throws Exception {
        return "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(cert.getEncoded())
                + "\n-----END CERTIFICATE-----";
    }

    private static String signRekorSet(PrivateKey key, String body, long integratedTime, String logId, long logIndex)
            throws Exception {
        // Mirror the canonical JSON the verifier reconstructs.
        String canonical = "{\"body\":\"" + body + "\","
                + "\"integratedTime\":" + integratedTime + ","
                + "\"logID\":\"" + logId + "\","
                + "\"logIndex\":" + logIndex + "}";
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(key);
        signature.update(canonical.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signature.sign());
    }

    private static String sign(PrivateKey key, byte[] payload, String algo) throws Exception {
        Signature signature = Signature.getInstance(algo);
        signature.initSign(key);
        signature.update(payload);
        return Base64.getEncoder().encodeToString(signature.sign());
    }

    private static X509Certificate selfSign(KeyPair pair, String dn) throws Exception {
        Instant now = Instant.now();
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                new X500Name(dn),
                BigInteger.valueOf(System.nanoTime()),
                Date.from(now.minus(1, ChronoUnit.HOURS)),
                Date.from(now.plus(1, ChronoUnit.DAYS)),
                new X500Name(dn),
                pair.getPublic());
        var signer = new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    private static X509Certificate signCert(KeyPair issuer, X509Certificate issuerCert, KeyPair subject, String dn)
            throws Exception {
        Instant now = Instant.now();
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                new X500Name(issuerCert.getSubjectX500Principal().getName()),
                BigInteger.valueOf(System.nanoTime()),
                Date.from(now.minus(1, ChronoUnit.HOURS)),
                Date.from(now.plus(1, ChronoUnit.DAYS)),
                new X500Name(dn),
                subject.getPublic());
        var signer = new JcaContentSignerBuilder("SHA256withRSA").build(issuer.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    private static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static PlatformModuleSignatureVerifier.VerificationInput prepared(Path jar) {
        return new PlatformModuleSignatureVerifier.VerificationInput(jar, "alpha", "1.0.0", "deadbeef");
    }
}
