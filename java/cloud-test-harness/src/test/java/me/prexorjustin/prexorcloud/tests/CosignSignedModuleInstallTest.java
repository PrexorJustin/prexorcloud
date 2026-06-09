package me.prexorjustin.prexorcloud.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.List;

import me.prexorjustin.prexorcloud.controller.config.ModuleSigningConfig;
import me.prexorjustin.prexorcloud.harness.PlatformModuleTestJarFactory;
import me.prexorjustin.prexorcloud.harness.RestClient;
import me.prexorjustin.prexorcloud.harness.TestCluster;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end coverage for the cosign-bundle signed-install path. Boots a controller with
 * {@code modules.signing.required=true} + {@code mode=COSIGN_BUNDLE} pointed at an
 * ephemeral PEM trust bundle, then exercises the real {@code /api/v1/modules/platform/upload}
 * route over multipart with a {@code signature} sidecar part — closing the §8 "Open at v1"
 * cosign integration test gate.
 */
class CosignSignedModuleInstallTest {

    static TestCluster cluster;
    static RestClient admin;
    static KeyPair signingKey;

    @BeforeAll
    static void setup(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(TestCluster.mongoAvailable(), "MongoDB is required for cosign install integration test");

        signingKey = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        Path trustBundle = writeTrustPem(tmp.resolve("trust.pem"), signingKey);

        var signing = new ModuleSigningConfig(
                true, trustBundle.toString(), ModuleSigningConfig.Mode.COSIGN_BUNDLE, false, null);
        cluster = TestCluster.startWithSigning(signing);
        admin = new RestClient(cluster.restBaseUrl(), cluster.adminJwtToken());
    }

    @AfterAll
    static void teardown() {
        if (cluster != null) cluster.close();
    }

    @Test
    void installsSignedModuleAndRejectsUnsignedAndTampered(@TempDir Path tmp) throws Exception {
        Path providerJar = PlatformModuleTestJarFactory.createProviderV1Jar(tmp.resolve("profile.jar"));
        byte[] jarBytes = Files.readAllBytes(providerJar);

        // 1. Happy path: jar + matching cosign bundle → 201 install.
        byte[] bundleJson = cosignBundle(sign(signingKey.getPrivate(), jarBytes));
        var installed = admin.postMultipart(
                "/api/v1/modules/platform/upload",
                List.of(
                        new RestClient.FilePart("file", "profile.jar", "application/java-archive", jarBytes),
                        new RestClient.FilePart(
                                "signature", "profile.jar.cosign.bundle", "application/json", bundleJson)));
        assertEquals(201, installed.status(), installed.body());
        assertEquals("profile", installed.json().get("moduleId").asText());
        assertNotNull(
                cluster.controller()
                        .moduleRegistry()
                        .platformManager()
                        .snapshot("profile")
                        .orElse(null),
                "signed module must be installed");

        // 2. Missing signature → fail-closed at the verifier (cosign bundle absent).
        Path consumerJar = PlatformModuleTestJarFactory.createConsumerJar(tmp.resolve("queue.jar"));
        var unsigned = admin.postMultipartFile("/api/v1/modules/platform/upload", "file", consumerJar);
        assertEquals(422, unsigned.status(), unsigned.body());
        assertEquals(
                "SIGNATURE_VERIFICATION_FAILED",
                unsigned.json().path("error").path("code").asText(),
                unsigned.body());

        // 3. Tampered bundle: signature over *different* bytes than the uploaded jar → 422.
        byte[] consumerBytes = Files.readAllBytes(consumerJar);
        byte[] tampered =
                cosignBundle(sign(signingKey.getPrivate(), "different-bytes".getBytes(StandardCharsets.UTF_8)));
        var bad = admin.postMultipart(
                "/api/v1/modules/platform/upload",
                List.of(
                        new RestClient.FilePart("file", "queue.jar", "application/java-archive", consumerBytes),
                        new RestClient.FilePart("signature", "queue.jar.cosign.bundle", "application/json", tampered)));
        assertEquals(422, bad.status(), bad.body());
        assertEquals(
                "SIGNATURE_VERIFICATION_FAILED",
                bad.json().path("error").path("code").asText(),
                bad.body());
        assertTrue(
                cluster.controller()
                        .moduleRegistry()
                        .platformManager()
                        .snapshot("queue")
                        .isEmpty(),
                "rejected module must not be installed");
    }

    private static byte[] cosignBundle(String base64Signature) {
        return ("{\"base64Signature\":\"" + base64Signature + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    private static String sign(PrivateKey key, byte[] payload) throws Exception {
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(key);
        signature.update(payload);
        return Base64.getEncoder().encodeToString(signature.sign());
    }

    private static Path writeTrustPem(Path path, KeyPair pair) throws Exception {
        String body = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                        .encodeToString(pair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        Files.writeString(path, body);
        return path;
    }
}
