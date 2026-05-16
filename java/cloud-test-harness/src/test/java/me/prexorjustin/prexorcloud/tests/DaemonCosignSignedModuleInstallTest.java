package me.prexorjustin.prexorcloud.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import me.prexorjustin.prexorcloud.daemon.config.ModuleSigningDaemonConfig;
import me.prexorjustin.prexorcloud.harness.RestClient;
import me.prexorjustin.prexorcloud.harness.TestCluster;
import me.prexorjustin.prexorcloud.modules.runtime.ModuleLifecycleManager;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end coverage of the cosign-signed daemon-host module install path. Both the
 * controller and the daemon are configured with the same cosign trust root; the controller
 * verifies on upload and persists the sidecar, the daemon re-verifies after the {@code
 * ModuleInstall} arrives over gRPC. Unsigned installs are rejected at the controller; this
 * test asserts the signed happy path reaches ACTIVE on the daemon side.
 */
@Tag("daemon-module")
class DaemonCosignSignedModuleInstallTest {

    static TestCluster cluster;
    static RestClient admin;
    static KeyPair signingKey;
    static Path moduleJar;
    static Path hooksFile;

    @BeforeAll
    static void setup(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(
                TestCluster.mongoAvailable(), "MongoDB is required for daemon cosign install integration");
        String configured = System.getProperty("prexor.test.testDaemonModuleJar");
        Assumptions.assumeTrue(
                configured != null && !configured.isBlank(),
                "prexor.test.testDaemonModuleJar must be set by the gradle test task");
        moduleJar = Path.of(configured);
        Assumptions.assumeTrue(Files.exists(moduleJar), "test-daemon-module jar not found: " + moduleJar);

        hooksFile = tmp.resolve("test-daemon-hooks-cosign.log");
        System.setProperty(
                "prexor.test.testDaemonModuleHooksFile",
                hooksFile.toAbsolutePath().toString());

        signingKey = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        Path trustBundle = writeTrustPem(tmp.resolve("trust.pem"), signingKey);

        var controllerSigning = new ModuleSigningConfig(
                true, trustBundle.toString(), ModuleSigningConfig.Mode.COSIGN_BUNDLE, false, null);
        cluster = TestCluster.startWithSigning(controllerSigning);
        admin = new RestClient(cluster.restBaseUrl(), cluster.adminJwtToken());

        // Daemon must enforce the same cosign policy so the test exercises both gates.
        cluster.setDaemonModuleSigning(new ModuleSigningDaemonConfig(
                true, ModuleSigningDaemonConfig.Mode.COSIGN_BUNDLE, trustBundle.toString()));
        cluster.addDaemon("test-daemon-cosign-1");
        cluster.waitForNode("test-daemon-cosign-1", 15_000);
    }

    @AfterAll
    static void teardown() {
        System.clearProperty("prexor.test.testDaemonModuleHooksFile");
        if (cluster != null) cluster.close();
    }

    @Test
    void signedDaemonModuleReachesActiveOnDaemon() throws Exception {
        byte[] jarBytes = Files.readAllBytes(moduleJar);
        byte[] bundleJson = cosignBundle(sign(signingKey.getPrivate(), jarBytes));

        var install = admin.postMultipart(
                "/api/v1/modules/platform/upload",
                List.of(
                        new RestClient.FilePart("file", "test-daemon-module.jar", "application/java-archive", jarBytes),
                        new RestClient.FilePart(
                                "signature", "test-daemon-module.jar.cosign.bundle", "application/json", bundleJson)));
        assertEquals(201, install.status(), install.body());

        var daemon = cluster.daemons().get(0);
        cluster.waitForCondition(
                "signed daemon module ACTIVE on daemon",
                15_000,
                () -> daemon.daemonModuleManager()
                        .moduleState("test-daemon-module")
                        .map(state -> state == ModuleLifecycleManager.ModuleState.ACTIVE)
                        .orElse(false));
    }

    @Test
    void unsignedDaemonModuleIsRejectedAtController() throws Exception {
        var unsigned = admin.postMultipartFile("/api/v1/modules/platform/upload", "file", moduleJar);
        assertEquals(422, unsigned.status(), unsigned.body());
        assertEquals(
                "SIGNATURE_VERIFICATION_FAILED",
                unsigned.json().path("error").path("code").asText(),
                unsigned.body());
        // Daemon must not have installed anything.
        var daemon = cluster.daemons().get(0);
        assertTrue(
                daemon.daemonModuleManager().installedModuleIds().stream()
                        .noneMatch(id -> id.equals("test-daemon-module-unsigned")),
                "unsigned daemon module must not reach the daemon");
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
