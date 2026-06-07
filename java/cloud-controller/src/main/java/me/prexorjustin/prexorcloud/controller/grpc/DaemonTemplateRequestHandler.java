package me.prexorjustin.prexorcloud.controller.grpc;

import me.prexorjustin.prexorcloud.common.util.InputValidator;
import me.prexorjustin.prexorcloud.controller.template.TemplateConfig;
import me.prexorjustin.prexorcloud.controller.template.TemplateManager;
import me.prexorjustin.prexorcloud.controller.template.TemplateMerger;
import me.prexorjustin.prexorcloud.protocol.ControllerMessage;
import me.prexorjustin.prexorcloud.protocol.TemplateData;
import me.prexorjustin.prexorcloud.protocol.TemplateRequest;
import me.prexorjustin.prexorcloud.protocol.TemplateUpToDate;

import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serves daemon-side template fetch requests off a virtual thread. Extracted
 * from {@code DaemonServiceImpl}'s connect-stream handler.
 *
 * <p>Fast-path: if the daemon supplies a {@code knownHash} matching the
 * controller's stable content hash, replies with {@code TemplateUpToDate}.
 * Otherwise re-packages the template tar.gz.
 */
final class DaemonTemplateRequestHandler {

    private static final Logger logger = LoggerFactory.getLogger(DaemonTemplateRequestHandler.class);

    private final TemplateManager templateManager;
    private final TemplateMerger templateMerger;

    DaemonTemplateRequestHandler(TemplateManager templateManager, TemplateMerger templateMerger) {
        this.templateManager = templateManager;
        this.templateMerger = templateMerger;
    }

    void handleTemplateRequest(String nodeId, TemplateRequest request, StreamObserver<ControllerMessage> response) {
        try {
            InputValidator.requireSafeName(request.getTemplateName(), "templateName");
            InputValidator.requireMaxLength(request.getKnownHash(), 128, "knownHash");
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid template request from node {}: {}", nodeId, e.getMessage());
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                String templateName = request.getTemplateName();
                String knownHash = request.getKnownHash();

                // Fast path: compare stable content hash before expensive tar.gz packaging.
                // TemplateManager's hash is content-based (deterministic), unlike the tar.gz
                // hash which can vary with file metadata. This prevents unnecessary
                // re-downloads.
                if (!knownHash.isEmpty()) {
                    var templateOpt = templateManager.get(templateName);
                    if (templateOpt.isPresent()
                            && knownHash.equals(templateOpt.get().hash())) {
                        response.onNext(ControllerMessage.newBuilder()
                                .setTemplateUpToDate(
                                        TemplateUpToDate.newBuilder().setTemplateName(templateName))
                                .build());
                        return;
                    }
                }

                var result = templateMerger.packageTemplate(templateName);

                var templateOpt = templateManager.get(templateName);
                String stableHash = templateOpt
                        .map(TemplateConfig::hash)
                        .filter(h -> !h.isEmpty())
                        .orElse(result.hash());

                response.onNext(ControllerMessage.newBuilder()
                        .setTemplateData(TemplateData.newBuilder()
                                .setTemplateName(templateName)
                                .setHash(stableHash)
                                .setTarGz(ByteString.copyFrom(result.tarGz())))
                        .build());
            } catch (Exception e) {
                logger.error("Failed to serve template {}: {}", request.getTemplateName(), e.getMessage(), e);
            }
        });
    }
}
