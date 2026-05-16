package me.prexorjustin.prexorcloud.security.tls;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.net.ssl.SSLContext;

import me.prexorjustin.prexorcloud.security.ca.CertificateAuthority;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("TlsContextBuilder")
class TlsContextBuilderTest {

    private static final char[] PASSWORD = "test-pw".toCharArray();

    private static CertificateAuthority newCa(Path tmp) throws Exception {
        return CertificateAuthority.create(tmp.resolve("ca.p12"), PASSWORD, "Test CA", 365);
    }

    @Nested
    @DisplayName("Path-based build")
    class PathBased {

        @Test
        @DisplayName("Builds a TLSv1.3 SSLContext from keystore + CA PEM on disk")
        void buildsTls13ContextFromFiles(@TempDir Path tmp) throws Exception {
            CertificateAuthority ca = newCa(tmp);
            Path serverKeystore = tmp.resolve("server.p12");
            Path caPem = tmp.resolve("ca.pem");

            ca.issueServerCertificate("controller.local", List.of("controller.local"), 30)
                    .savePkcs12(serverKeystore, PASSWORD);
            ca.exportCaPem(caPem);

            SSLContext ctx = TlsContextBuilder.build(serverKeystore, PASSWORD, caPem);

            assertEquals("TLSv1.3", ctx.getProtocol());
            assertNotNull(ctx.getSocketFactory());
            assertNotNull(ctx.getServerSocketFactory());
        }

        @Test
        @DisplayName("Throws when keystore password is wrong")
        void throwsOnWrongPassword(@TempDir Path tmp) throws Exception {
            CertificateAuthority ca = newCa(tmp);
            Path serverKeystore = tmp.resolve("server.p12");
            Path caPem = tmp.resolve("ca.pem");

            ca.issueServerCertificate("controller.local", List.of("controller.local"), 30)
                    .savePkcs12(serverKeystore, PASSWORD);
            ca.exportCaPem(caPem);

            assertThrows(Exception.class, () -> TlsContextBuilder.build(serverKeystore, "wrong".toCharArray(), caPem));
        }

        @Test
        @DisplayName("Throws when CA PEM is missing")
        void throwsOnMissingCaPem(@TempDir Path tmp) throws Exception {
            CertificateAuthority ca = newCa(tmp);
            Path serverKeystore = tmp.resolve("server.p12");

            ca.issueServerCertificate("controller.local", List.of("controller.local"), 30)
                    .savePkcs12(serverKeystore, PASSWORD);

            assertThrows(
                    Exception.class,
                    () -> TlsContextBuilder.build(serverKeystore, PASSWORD, tmp.resolve("missing.pem")));
        }
    }

    @Nested
    @DisplayName("Bytes-based build")
    class BytesBased {

        @Test
        @DisplayName("Builds a TLSv1.3 SSLContext from in-memory PKCS12 + CA PEM bytes")
        void buildsTls13ContextFromBytes(@TempDir Path tmp) throws Exception {
            CertificateAuthority ca = newCa(tmp);
            CertificateAuthority.IssuedCertificate issued = ca.issueNodeCertificate("node-1", 30);
            Path caPem = tmp.resolve("ca.pem");
            ca.exportCaPem(caPem);

            byte[] keystoreBytes = issued.toPkcs12Bytes(PASSWORD);
            byte[] caPemBytes = Files.readAllBytes(caPem);

            SSLContext ctx = TlsContextBuilder.build(keystoreBytes, PASSWORD, caPemBytes);

            assertEquals("TLSv1.3", ctx.getProtocol());
        }
    }
}
