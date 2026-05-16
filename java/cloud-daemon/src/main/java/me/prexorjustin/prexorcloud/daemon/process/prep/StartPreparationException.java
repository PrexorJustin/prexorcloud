package me.prexorjustin.prexorcloud.daemon.process.prep;

import me.prexorjustin.prexorcloud.protocol.StartPreparationStage;

/**
 * Thrown by any preparation stage to signal a structured failure that
 * the start-instance handler can map to a {@code StartResult} envelope.
 *
 * <p>Carries three things beyond the standard exception message:
 *
 * <ul>
 *   <li>{@link #stage()} — which preparation stage was running
 *       (validation, runtime provision, extension provision, template
 *       apply, variable substitution, config patch, process start).
 *       Surfaces back to the controller for retry-disposition logic.
 *   <li>{@link #errorCode()} — wire-stable string code for the response
 *       envelope ({@code RUNTIME_PROVISION_FAILED},
 *       {@code EXTENSION_PROVISION_FAILED}, etc.).
 *   <li>{@link #planHash()} — the controller's composition-plan hash for
 *       the failed start; lets the controller correlate retries against
 *       the original plan.
 * </ul>
 *
 * <p>Public so collaborators outside {@code ProcessManager} (artifact
 * provisioner, template preparer, future extractions) can throw it.
 */
public final class StartPreparationException extends Exception {

    private final String errorCode;
    private final String planHash;
    private final StartPreparationStage stage;

    public StartPreparationException(
            StartPreparationStage stage, String errorCode, String planHash, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.planHash = planHash;
        this.stage = stage;
    }

    public StartPreparationException(StartPreparationStage stage, String errorCode, String planHash, String message) {
        this(stage, errorCode, planHash, message, null);
    }

    public String errorCode() {
        return errorCode;
    }

    public String planHash() {
        return planHash;
    }

    public StartPreparationStage stage() {
        return stage;
    }
}
