package me.prexorjustin.prexorcloud.security.ca;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("CertificateAuthority")
class CertificateAuthorityTest {

    private static final char[] PASSWORD = "test-pw".toCharArray();
    private static final String COMMON_NAME = "PrexorCloud Test CA";

    @Nested
    @DisplayName("Lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("create() persists keystore and load() reproduces fingerprint")
        void createAndLoadRoundTrip(@TempDir Path tmp) throws Exception {
            Path keystore = tmp.resolve("ca.p12");

            CertificateAuthority created = CertificateAuthority.create(keystore, PASSWORD, COMMON_NAME, 365);
            assertTrue(Files.exists(keystore));

            CertificateAuthority loaded = CertificateAuthority.load(keystore, PASSWORD);
            assertEquals(created.fingerprint(), loaded.fingerprint());
            assertEquals(
                    created.certificate().getSerialNumber(),
                    loaded.certificate().getSerialNumber());
        }

        @Test
        @DisplayName("loadOrCreate() creates on first call, loads on second")
        void loadOrCreateIsIdempotent(@TempDir Path tmp) throws Exception {
            Path keystore = tmp.resolve("ca.p12");

            CertificateAuthority first = CertificateAuthority.loadOrCreate(keystore, PASSWORD, COMMON_NAME, 365);
            CertificateAuthority second = CertificateAuthority.loadOrCreate(keystore, PASSWORD, COMMON_NAME, 365);

            assertEquals(first.fingerprint(), second.fingerprint());
        }

        @Test
        @DisplayName("CA certificate is self-signed and basicConstraints CA flag is set")
        void caCertificateIsSelfSigned(@TempDir Path tmp) throws Exception {
            CertificateAuthority ca = CertificateAuthority.create(tmp.resolve("ca.p12"), PASSWORD, COMMON_NAME, 365);
            X509Certificate cert = ca.certificate();

            assertEquals(cert.getSubjectX500Principal(), cert.getIssuerX500Principal());
            assertTrue(cert.getBasicConstraints() >= 0, "basicConstraints CA flag must be set");
            cert.verify(ca.keyPair().getPublic());
        }

        @Test
        @DisplayName("exportCaPem() writes a parsable PEM block")
        void exportCaPemWritesParsablePem(@TempDir Path tmp) throws Exception {
            CertificateAuthority ca = CertificateAuthority.create(tmp.resolve("ca.p12"), PASSWORD, COMMON_NAME, 365);
            Path pem = tmp.resolve("ca.pem");
            ca.exportCaPem(pem);

            String contents = Files.readString(pem);
            assertTrue(contents.startsWith("-----BEGIN CERTIFICATE-----"));
            assertTrue(contents.contains("-----END CERTIFICATE-----"));

            var factory = java.security.cert.CertificateFactory.getInstance("X.509");
            try (var in = Files.newInputStream(pem)) {
                X509Certificate parsed = (X509Certificate) factory.generateCertificate(in);
                assertEquals(ca.certificate().getSerialNumber(), parsed.getSerialNumber());
            }
        }
    }

    @Nested
    @DisplayName("Issuing")
    class Issuing {

        @Test
        @DisplayName("issueServerCertificate() is signed by the CA and carries DNS+IP SANs")
        void serverCertificateSignedAndSanned(@TempDir Path tmp) throws Exception {
            CertificateAuthority ca = CertificateAuthority.create(tmp.resolve("ca.p12"), PASSWORD, COMMON_NAME, 365);

            CertificateAuthority.IssuedCertificate issued =
                    ca.issueServerCertificate("controller.local", List.of("controller.local", "127.0.0.1"), 30);

            X509Certificate cert = issued.certificate();
            cert.verify(ca.keyPair().getPublic());
            assertEquals("CN=controller.local", cert.getSubjectX500Principal().getName());

            Collection<List<?>> sans = cert.getSubjectAlternativeNames();
            assertNotNull(sans);
            assertTrue(sans.stream().anyMatch(entry -> "controller.local".equals(entry.get(1))));
            assertTrue(sans.stream().anyMatch(entry -> "127.0.0.1".equals(entry.get(1))));
        }

        @Test
        @DisplayName("issueNodeCertificate() carries the node id as CN and SAN")
        void nodeCertificateHasNodeIdSan(@TempDir Path tmp) throws Exception {
            CertificateAuthority ca = CertificateAuthority.create(tmp.resolve("ca.p12"), PASSWORD, COMMON_NAME, 365);

            CertificateAuthority.IssuedCertificate issued = ca.issueNodeCertificate("node-7", 30);

            X509Certificate cert = issued.certificate();
            assertEquals("CN=node-7", cert.getSubjectX500Principal().getName());
            assertTrue(cert.getSubjectAlternativeNames().stream().anyMatch(entry -> "node-7".equals(entry.get(1))));
        }

        @Test
        @DisplayName("Issued PKCS12 bytes round-trip into a loadable keystore")
        void issuedPkcs12BytesAreLoadable(@TempDir Path tmp) throws Exception {
            CertificateAuthority ca = CertificateAuthority.create(tmp.resolve("ca.p12"), PASSWORD, COMMON_NAME, 365);
            CertificateAuthority.IssuedCertificate issued = ca.issueNodeCertificate("node-1", 30);

            byte[] bytes = issued.toPkcs12Bytes(PASSWORD);

            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(new java.io.ByteArrayInputStream(bytes), PASSWORD);
            String alias = ks.aliases().nextElement();
            assertNotNull(ks.getKey(alias, PASSWORD));
            assertNotNull(ks.getCertificate(alias));
        }

        @Test
        @DisplayName("Two issued certificates have distinct serial numbers")
        void distinctSerialNumbers(@TempDir Path tmp) throws Exception {
            CertificateAuthority ca = CertificateAuthority.create(tmp.resolve("ca.p12"), PASSWORD, COMMON_NAME, 365);

            var first = ca.issueNodeCertificate("a", 30);
            var second = ca.issueNodeCertificate("b", 30);

            assertNotEquals(
                    first.certificate().getSerialNumber(), second.certificate().getSerialNumber());
        }
    }
}
