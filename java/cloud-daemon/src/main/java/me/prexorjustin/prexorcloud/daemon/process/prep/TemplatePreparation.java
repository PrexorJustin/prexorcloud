package me.prexorjustin.prexorcloud.daemon.process.prep;

import static me.prexorjustin.prexorcloud.daemon.process.prep.PreparationOps.stageFailure;
import static me.prexorjustin.prexorcloud.daemon.process.prep.PreparationOps.withPreparationRetries;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import me.prexorjustin.prexorcloud.daemon.grpc.DaemonGrpcClient;
import me.prexorjustin.prexorcloud.daemon.template.ServerConfigPatcher;
import me.prexorjustin.prexorcloud.daemon.template.TemplateCache;
import me.prexorjustin.prexorcloud.daemon.template.TemplateUnpacker;
import me.prexorjustin.prexorcloud.daemon.template.VariableSubstitution;
import me.prexorjustin.prexorcloud.protocol.StartPreparationStage;
import me.prexorjustin.prexorcloud.protocol.TemplateRef;

/**
 * Template-driven instance dir preparation: unpack template archives,
 * substitute variables in the unpacked files, then apply config patches.
 *
 * <p>Sibling to {@link ArtifactProvisioner} — same package, same retry +
 * error-envelope contract. Each public method throws
 * {@link StartPreparationException} tagged with the matching
 * {@link StartPreparationStage} so the start-instance handler can map
 * the failure to its wire-format code.
 *
 * <p>Stateless apart from constructor-injected collaborators.
 */
public final class TemplatePreparation {

    private final TemplateCache templateCache;
    private final DaemonGrpcClient grpcClient;
    private final String nodeId;

    public TemplatePreparation(TemplateCache templateCache, DaemonGrpcClient grpcClient, String nodeId) {
        this.templateCache = templateCache;
        this.grpcClient = grpcClient;
        this.nodeId = nodeId;
    }

    /**
     * Unpack every template archive referenced by {@code spec} into
     * {@code instanceDir}. For static instances with protected-paths, the
     * unpacker preserves operator-owned files instead of overwriting them.
     */
    public void applyTemplates(ResolvedStartSpec spec, Path instanceDir) throws StartPreparationException {
        try {
            Set<String> protectedPaths = new HashSet<>(spec.protectedPaths());
            for (TemplateRef ref : spec.templates()) {
                byte[] templateData = templateCache.getOrRequest(ref.getName(), ref.getHash(), grpcClient);
                if (templateData == null) {
                    throw new IllegalStateException("Template '" + ref.getName() + "' could not be fetched");
                }
                if (spec.staticInstance() && !protectedPaths.isEmpty()) {
                    TemplateUnpacker.unpackWithProtectedPaths(templateData, instanceDir, protectedPaths);
                } else {
                    TemplateUnpacker.unpack(templateData, instanceDir);
                }
            }
        } catch (Exception e) {
            throw stageFailure(
                    StartPreparationStage.TEMPLATE_APPLY,
                    "TEMPLATE_APPLY_FAILED",
                    spec.planHash(),
                    "Failed to apply templates for " + spec.instanceId(),
                    e);
        }
    }

    /**
     * Rewrite {@code $PORT}, {@code $INSTANCE_ID}, {@code $GROUP},
     * {@code $NODE_ID}, {@code $MEMORY}, {@code $MAX_PLAYERS} placeholders
     * in every text file under {@code instanceDir} using the resolved spec
     * values. {@code MAX_PLAYERS} falls back to 100 when the spec leaves
     * it unset.
     */
    public void applyVariableSubstitution(ResolvedStartSpec spec, Path instanceDir) throws StartPreparationException {
        try {
            Map<String, String> vars = Map.of(
                    "PORT",
                    String.valueOf(spec.port()),
                    "INSTANCE_ID",
                    spec.instanceId(),
                    "INSTANCE_NAME",
                    spec.instanceId(),
                    "GROUP",
                    spec.group(),
                    "NODE_ID",
                    nodeId,
                    "MEMORY",
                    String.valueOf(spec.memoryMb()),
                    "MAX_PLAYERS",
                    String.valueOf(spec.maxPlayers() > 0 ? spec.maxPlayers() : 100));
            VariableSubstitution.processDirectory(instanceDir, vars);
        } catch (Exception e) {
            throw stageFailure(
                    StartPreparationStage.VARIABLE_SUBSTITUTION,
                    "VARIABLE_SUBSTITUTION_FAILED",
                    spec.planHash(),
                    "Failed to substitute template variables for " + spec.instanceId(),
                    e);
        }
    }

    /**
     * Apply the spec's structured config patches (server.properties,
     * velocity.toml, etc.) under retries. The retry wrapper is the same
     * shared {@link PreparationOps#withPreparationRetries} used by every
     * other preparation stage.
     */
    public void patchConfigs(ResolvedStartSpec spec, Path instanceDir) throws StartPreparationException {
        try {
            withPreparationRetries("config patching for " + spec.instanceId(), () -> {
                ServerConfigPatcher.patch(instanceDir, spec.configFormat(), spec.configPatches());
                return null;
            });
        } catch (Exception e) {
            throw stageFailure(
                    StartPreparationStage.CONFIG_PATCH,
                    "CONFIG_PATCH_FAILED",
                    spec.planHash(),
                    "Failed to patch server config for " + spec.instanceId(),
                    e);
        }
    }
}
