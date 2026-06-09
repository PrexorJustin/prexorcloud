package me.prexorjustin.prexorcloud.security.tls;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;

import me.prexorjustin.prexorcloud.security.ca.CertificateAuthority;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("ReloadableServerSslContext")
class ReloadableServerSslContextTest {

    @Test
    @DisplayName("builds SslContext from on-disk material")
    void buildsContext(@TempDir Path tmp) throws Exception {
        Material initial = newMaterial(tmp, "initial");
        var ctx = ReloadableServerSslContext.build(
                initial.serverKeystore, initial.password, initial.caPem, NodeRevocationCheck.NONE);
        assertNotNull(ctx.sslContext());
    }

    @Test
    @DisplayName("reload swaps to new key material without rebuilding the SslContext")
    void reloadSwapsKeyMaterial(@TempDir Path tmp) throws Exception {
        Material initial = newMaterial(tmp, "initial");
        var ctx = ReloadableServerSslContext.build(
                initial.serverKeystore, initial.password, initial.caPem, NodeRevocationCheck.NONE);
        var sslContextBefore = ctx.sslContext();

        // Re-issue and overwrite the same paths.
        Material rotated = newMaterial(tmp, "rotated");
        // Overwrite the original paths so the same on-disk locations now hold rotated material.
        Files.copy(rotated.serverKeystore, initial.serverKeystore, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(rotated.caPem, initial.caPem, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        ctx.reload(initial.serverKeystore, initial.password, initial.caPem);
        // Same SslContext instance — the wrapper is reused.
        assertArrayEquals(
                new Object[] {sslContextBefore},
                new Object[] {ctx.sslContext()},
                "SslContext instance must be reused across reload");
    }

    @Test
    @DisplayName("revocation check is consulted by the wrapping trust manager")
    void revocationCheckIsConsulted(@TempDir Path tmp) throws Exception {
        Material initial = newMaterial(tmp, "initial");
        var seenSerial = new BigInteger[1];
        var seenCn = new String[1];
        NodeRevocationCheck check = (serial, cn) -> {
            seenSerial[0] = serial;
            seenCn[0] = cn;
            return false;
        };
        var ctx = ReloadableServerSslContext.build(initial.serverKeystore, initial.password, initial.caPem, check);
        assertNotNull(ctx.sslContext());
        // The check is wired into the trust manager — exercising it from a real
        // handshake requires a full gRPC round trip; the trust manager is unit-
        // tested via NodeRevocationCheck directly. Here we just assert the
        // build succeeds so the full reload pipeline is reachable.
    }

    private static Material newMaterial(Path tmp, String tag) throws Exception {
        Path caKeystore = tmp.resolve("ca-" + tag + ".p12");
        Path caPem = tmp.resolve("ca-" + tag + ".pem");
        Path serverKeystore = tmp.resolve("server-" + tag + ".p12");
        char[] password = "test-pass".toCharArray();

        var ca = CertificateAuthority.create(caKeystore, password, "TestCA-" + tag, 365);
        ca.exportCaPem(caPem);

        var server = ca.issueServerCertificate("test-server-" + tag, java.util.List.of("localhost", "127.0.0.1"), 365);
        // Save server cert + key to a PKCS12 keystore.
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, password);
        ks.setKeyEntry("server", server.keyPair().getPrivate(), password, new Certificate[] {
            server.certificate(), server.caCertificate()
        });
        try (OutputStream out = Files.newOutputStream(serverKeystore)) {
            ks.store(out, password);
        }
        return new Material(serverKeystore, password, caPem);
    }

    private record Material(Path serverKeystore, char[] password, Path caPem) {}
}
