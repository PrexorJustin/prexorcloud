package me.prexorjustin.prexorcloud.controller.security;

import static org.junit.jupiter.api.Assertions.*;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.X509ExtendedTrustManager;

import me.prexorjustin.prexorcloud.security.ca.CertificateAuthority;
import me.prexorjustin.prexorcloud.security.tls.NodeRevocationCheck;
import me.prexorjustin.prexorcloud.security.tls.ReloadableServerSslContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("TlsMaterialWatcher")
class TlsMaterialWatcherTest {

    private static final char[] PASSWORD = "test-pass".toCharArray();

    @Test
    @DisplayName("run() with unchanged files leaves trust material in place")
    void noChangeMeansNoReload(@TempDir Path tmp) throws Exception {
        var initial = newMaterial(tmp, "initial");
        var ctx = ReloadableServerSslContext.build(
                initial.serverKeystore, PASSWORD, initial.caPem, NodeRevocationCheck.NONE);
        var watcher = new TlsMaterialWatcher(ctx, initial.serverKeystore, PASSWORD, initial.caPem);

        X509Certificate[] before = trustManager(ctx).getAcceptedIssuers();
        watcher.run();
        X509Certificate[] after = trustManager(ctx).getAcceptedIssuers();

        assertSame(before[0], after[0], "no file change ⇒ no reload ⇒ same accepted-issuers array");
    }

    @Test
    @DisplayName("run() picks up a rotated keystore + CA PEM")
    void rotatedFilesAreReloaded(@TempDir Path tmp) throws Exception {
        var initial = newMaterial(tmp, "initial");
        var ctx = ReloadableServerSslContext.build(
                initial.serverKeystore, PASSWORD, initial.caPem, NodeRevocationCheck.NONE);
        var watcher = new TlsMaterialWatcher(ctx, initial.serverKeystore, PASSWORD, initial.caPem);

        String issuerBefore = trustManager(ctx)
                .getAcceptedIssuers()[0]
                .getSubjectX500Principal()
                .getName();

        // Rotate: build fresh material under different paths, then overwrite the
        // watched paths with the new bytes and bump mtime forward so the watcher
        // observes the change.
        var rotated = newMaterial(tmp, "rotated");
        Files.copy(rotated.serverKeystore, initial.serverKeystore, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(rotated.caPem, initial.caPem, StandardCopyOption.REPLACE_EXISTING);
        FileTime future = FileTime.from(Instant.now().plusSeconds(60));
        Files.setLastModifiedTime(initial.serverKeystore, future);
        Files.setLastModifiedTime(initial.caPem, future);

        watcher.run();

        String issuerAfter = trustManager(ctx)
                .getAcceptedIssuers()[0]
                .getSubjectX500Principal()
                .getName();
        assertNotEquals(issuerBefore, issuerAfter, "trust manager must reflect the rotated CA");
        assertTrue(issuerAfter.contains("rotated"));
    }

    @Test
    @DisplayName("forceReload() runs the same change-detection as the scheduled tick")
    void forceReloadRunsTheSameTick(@TempDir Path tmp) throws Exception {
        var initial = newMaterial(tmp, "initial");
        var ctx = ReloadableServerSslContext.build(
                initial.serverKeystore, PASSWORD, initial.caPem, NodeRevocationCheck.NONE);
        var watcher = new TlsMaterialWatcher(ctx, initial.serverKeystore, PASSWORD, initial.caPem);

        var rotated = newMaterial(tmp, "rotated");
        Files.copy(rotated.serverKeystore, initial.serverKeystore, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(rotated.caPem, initial.caPem, StandardCopyOption.REPLACE_EXISTING);
        FileTime future = FileTime.from(Instant.now().plusSeconds(60));
        Files.setLastModifiedTime(initial.serverKeystore, future);
        Files.setLastModifiedTime(initial.caPem, future);

        watcher.forceReload();

        String issuerAfter = trustManager(ctx)
                .getAcceptedIssuers()[0]
                .getSubjectX500Principal()
                .getName();
        assertTrue(issuerAfter.contains("rotated"));
    }

    @Test
    @DisplayName("Construction snapshots existing files without throwing")
    void constructionSnapshotsExistingFiles(@TempDir Path tmp) throws Exception {
        var initial = newMaterial(tmp, "initial");
        var ctx = ReloadableServerSslContext.build(
                initial.serverKeystore, PASSWORD, initial.caPem, NodeRevocationCheck.NONE);

        assertDoesNotThrow(() -> new TlsMaterialWatcher(ctx, initial.serverKeystore, PASSWORD, initial.caPem));
    }

    @Test
    @DisplayName("run() is silent when keystore path does not exist")
    void runSilentOnMissingKeystore(@TempDir Path tmp) throws Exception {
        var initial = newMaterial(tmp, "initial");
        var ctx = ReloadableServerSslContext.build(
                initial.serverKeystore, PASSWORD, initial.caPem, NodeRevocationCheck.NONE);
        var watcher = new TlsMaterialWatcher(ctx, initial.serverKeystore, PASSWORD, initial.caPem);

        Files.delete(initial.serverKeystore);
        assertDoesNotThrow(watcher::run);
    }

    private static X509ExtendedTrustManager trustManager(ReloadableServerSslContext ctx) throws Exception {
        Field field = ReloadableServerSslContext.class.getDeclaredField("trustManagerRef");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        AtomicReference<X509ExtendedTrustManager> ref = (AtomicReference<X509ExtendedTrustManager>) field.get(ctx);
        return ref.get();
    }

    private static Material newMaterial(Path tmp, String tag) throws Exception {
        Path caKeystore = tmp.resolve("ca-" + tag + ".p12");
        Path caPem = tmp.resolve("ca-" + tag + ".pem");
        Path serverKeystore = tmp.resolve("server-" + tag + ".p12");

        var ca = CertificateAuthority.create(caKeystore, PASSWORD, "TestCA-" + tag, 365);
        ca.exportCaPem(caPem);

        var server = ca.issueServerCertificate("test-server-" + tag, List.of("localhost", "127.0.0.1"), 365);
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, PASSWORD);
        ks.setKeyEntry("server", server.keyPair().getPrivate(), PASSWORD, new Certificate[] {
            server.certificate(), server.caCertificate()
        });
        try (OutputStream out = Files.newOutputStream(serverKeystore)) {
            ks.store(out, PASSWORD);
        }
        return new Material(serverKeystore, caPem);
    }

    private record Material(Path serverKeystore, Path caPem) {}
}
