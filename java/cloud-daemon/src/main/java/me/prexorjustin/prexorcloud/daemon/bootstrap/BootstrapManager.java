package me.prexorjustin.prexorcloud.daemon.bootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import me.prexorjustin.prexorcloud.common.util.FilePermissions;
import me.prexorjustin.prexorcloud.protocol.BootstrapServiceGrpc;
import me.prexorjustin.prexorcloud.protocol.ExchangeJoinTokenRequest;
import me.prexorjustin.prexorcloud.security.tls.ClientTlsCredentials;

import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles first-time node bootstrap: exchanges a join token for a certificate.
 */
public final class BootstrapManager {

    private static final Logger logger = LoggerFactory.getLogger(BootstrapManager.class);

    private final String controllerHost;
    private final int controllerPort;
    private final Path certDir;

    public BootstrapManager(String controllerHost, int controllerPort, Path certDir) {
        this.controllerHost = controllerHost;
        this.controllerPort = controllerPort;
        this.certDir = certDir;
    }

    public Path nodePkcs12Path() {
        return certDir.resolve("node.p12");
    }

    public Path caPemPath() {
        return certDir.resolve("ca.pem");
    }

    public Path passwordPath() {
        return certDir.resolve(".node-password");
    }

    public boolean isBootstrapped() {
        return Files.exists(nodePkcs12Path());
    }

    /**
     * Exchange a join token with the controller to obtain a node certificate.
     * Connects WITHOUT TLS for bootstrap (controller allows unauthenticated
     * bootstrap).
     */
    public void bootstrap(String joinToken, String nodeId) throws Exception {
        logger.info("Bootstrapping node {} with controller {}:{}", nodeId, controllerHost, controllerPort);

        // Connect with TLS but trust any server cert (no CA cert yet during bootstrap).
        // The join token itself authenticates this request.
        var sslContext = ClientTlsCredentials.buildInsecure();
        ManagedChannel channel = NettyChannelBuilder.forAddress(controllerHost, controllerPort)
                .sslContext(sslContext)
                .build();

        try {
            var stub = BootstrapServiceGrpc.newBlockingStub(channel).withDeadlineAfter(30, TimeUnit.SECONDS);
            var response = stub.exchangeJoinToken(ExchangeJoinTokenRequest.newBuilder()
                    .setJoinToken(joinToken)
                    .setNodeId(nodeId)
                    .build());

            // Save PKCS12
            Files.createDirectories(certDir);
            Files.write(nodePkcs12Path(), response.getPkcs12().toByteArray());
            FilePermissions.setOwnerReadWrite(nodePkcs12Path());

            // Save password
            Files.writeString(passwordPath(), response.getPkcs12Password());
            FilePermissions.setOwnerReadWrite(passwordPath());

            // Save CA PEM
            Files.write(caPemPath(), response.getCaCertificatePem().toByteArray());

            logger.info("Bootstrap successful. Certificate saved to {}", nodePkcs12Path());
        } finally {
            channel.shutdownNow();
        }
    }
}
