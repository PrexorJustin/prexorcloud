package me.prexorjustin.prexorcloud.controller.module.platform;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

import me.prexorjustin.prexorcloud.security.signing.PlatformModuleSignatureVerifier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformModuleSignatureVerifierTest {

    @Test
    void noopAcceptsAnyModule() {
        PlatformModuleSignatureVerifier verifier = PlatformModuleSignatureVerifier.NOOP;
        assertDoesNotThrow(() -> verifier.verify(prepared(Path.of("doesnt-matter.jar"))));
    }

    @Test
    void failClosedAlwaysThrows() {
        PlatformModuleSignatureVerifier verifier = PlatformModuleSignatureVerifier.failClosed();
        var ex = assertThrows(
                PlatformModuleSignatureVerifier.SignatureVerificationException.class,
                () -> verifier.verify(prepared(Path.of("doesnt-matter.jar"))));
        // sanity: error mentions trust root
        assert ex.getMessage().contains("trust root");
    }

    @Test
    void trustRootVerifierAcceptsValidSignature(@TempDir Path tmp) throws Exception {
        KeyPair keys = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        Path bundle = writePemBundle(tmp.resolve("trust.pem"), keys);

        Path jar = Files.write(tmp.resolve("module.jar"), "fake-jar-bytes".getBytes());
        Files.writeString(jar.resolveSibling("module.jar.sig"), sign(keys, "fake-jar-bytes".getBytes()));

        PlatformModuleSignatureVerifier verifier =
                PlatformModuleSignatureVerifier.TrustRootVerifier.fromPemBundle(bundle);
        assertDoesNotThrow(() -> verifier.verify(prepared(jar)));
    }

    @Test
    void trustRootVerifierRejectsTamperedJar(@TempDir Path tmp) throws Exception {
        KeyPair keys = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        Path bundle = writePemBundle(tmp.resolve("trust.pem"), keys);

        Path jar = Files.write(tmp.resolve("module.jar"), "tampered".getBytes());
        Files.writeString(jar.resolveSibling("module.jar.sig"), sign(keys, "original".getBytes()));

        PlatformModuleSignatureVerifier verifier =
                PlatformModuleSignatureVerifier.TrustRootVerifier.fromPemBundle(bundle);
        assertThrows(
                PlatformModuleSignatureVerifier.SignatureVerificationException.class,
                () -> verifier.verify(prepared(jar)));
    }

    @Test
    void trustRootVerifierRejectsMissingSidecar(@TempDir Path tmp) throws Exception {
        KeyPair keys = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        Path bundle = writePemBundle(tmp.resolve("trust.pem"), keys);
        Path jar = Files.write(tmp.resolve("module.jar"), "fake".getBytes());

        PlatformModuleSignatureVerifier verifier =
                PlatformModuleSignatureVerifier.TrustRootVerifier.fromPemBundle(bundle);
        var ex = assertThrows(
                PlatformModuleSignatureVerifier.SignatureVerificationException.class,
                () -> verifier.verify(prepared(jar)));
        assert ex.getMessage().contains("missing signature");
    }

    @Test
    void trustRootVerifierRequiresAtLeastOneKey(@TempDir Path tmp) throws Exception {
        Path bundle = Files.writeString(tmp.resolve("empty.pem"), "no keys here");
        assertThrows(
                IllegalStateException.class,
                () -> PlatformModuleSignatureVerifier.TrustRootVerifier.fromPemBundle(bundle));
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

    private static String sign(KeyPair keys, byte[] payload) throws Exception {
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(keys.getPrivate());
        signature.update(payload);
        return Base64.getEncoder().encodeToString(signature.sign());
    }

    private static PlatformModuleSignatureVerifier.VerificationInput prepared(Path jar) {
        return new PlatformModuleSignatureVerifier.VerificationInput(jar, "alpha", "1.0.0", "deadbeef");
    }
}
