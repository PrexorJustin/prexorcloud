package me.prexorjustin.prexorcloud.controller.state;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import me.prexorjustin.prexorcloud.controller.crash.CrashRecord;
import me.prexorjustin.prexorcloud.controller.crash.CrashTrendPoint;
import me.prexorjustin.prexorcloud.controller.deployment.DeploymentRecord;
import me.prexorjustin.prexorcloud.controller.scheduler.composition.InstanceCompositionPlan;
import me.prexorjustin.prexorcloud.controller.share.ShareKind;
import me.prexorjustin.prexorcloud.controller.share.ShareRecord;
import me.prexorjustin.prexorcloud.controller.template.TemplateConfig;

/**
 * Persistence interface for all controller state. Implementations:
 * {@link MongoStateStore}.
 */
public interface StateStore {

    // --- Lifecycle ---

    void initialize() throws Exception;

    void close();

    /**
     * Execute a group of operations atomically within a single database
     * transaction. If the action throws, the transaction is rolled back.
     */
    void runInTransaction(Runnable action);

    // --- Templates ---

    void saveTemplate(TemplateConfig config);

    Optional<TemplateConfig> getTemplate(String name);

    List<TemplateConfig> getAllTemplates();

    void deleteTemplate(String name);

    // --- Template Versions ---

    void recordTemplateVersion(String templateName, String hash, long sizeBytes);

    List<TemplateVersion> getTemplateVersions(String templateName);

    Optional<TemplateVersion> getLatestTemplateVersion(String templateName);

    void deleteTemplateVersion(String templateName, String hash);

    // --- Template Variables ---

    List<TemplateVariable> getTemplateVariables(String templateName);

    void saveTemplateVariables(String templateName, List<TemplateVariable> variables);

    // --- Deployments ---

    DeploymentRecord createDeployment(DeploymentRecord record);

    Optional<DeploymentRecord> getDeployment(String groupName, int revision);

    List<DeploymentRecord> getDeployments(String groupName, int limit, int offset);

    default Optional<DeploymentRecord> getInProgressDeployment(String groupName) {
        return getDeployments(groupName, 10, 0).stream()
                .filter(deployment -> "IN_PROGRESS".equals(deployment.state()))
                .findFirst();
    }

    int countDeployments(String groupName);

    void updateDeploymentState(int id, String state);

    void updateDeploymentProgress(int id, int updatedInstances);

    List<DeploymentRecord> getDeploymentsByState(String state, int limit);

    // --- Crashes ---

    void saveCrash(CrashRecord record);

    Optional<CrashRecord> getCrash(String id);

    List<CrashRecord> getCrashes(String group, String nodeId, int limit, int offset);

    int countCrashes(String group, String nodeId);

    /**
     * Lightweight projection of crash records since {@code since}, for trend
     * bucketing. Returned in no particular order.
     */
    List<CrashTrendPoint> getCrashTrend(String group, String nodeId, Instant since);

    // --- Shares ---

    /**
     * Persist a new {@link ShareRecord}. Idempotent on the {@code id}.
     */
    void saveShareRecord(ShareRecord record);

    Optional<ShareRecord> getShareRecord(String id);

    /**
     * Return recent share records, newest first. {@code kind} filters by surface
     * when non-null; {@code activeOnly} hides revoked entries when true.
     */
    List<ShareRecord> getShareRecords(ShareKind kind, boolean activeOnly, int limit, int offset);

    int countShareRecords(ShareKind kind, boolean activeOnly);

    /**
     * Mark a share record revoked at {@code when}. No-op if the id is unknown.
     */
    void markShareRevoked(String id, Instant when);

    // --- Audit Log ---

    void audit(
            String username, String action, String resourceType, String resourceId, String details, String ipAddress);

    /**
     * Audit variant that also captures pre/post-mutation snapshots so the
     * dashboard can render a diff. {@code beforeJson} / {@code afterJson} are
     * raw JSON strings (each {@code null} when not applicable — e.g. a delete
     * action has no after, a create has no before). {@code details} remains
     * the free-form payload used by existing readers.
     */
    void audit(
            String username,
            String action,
            String resourceType,
            String resourceId,
            String details,
            String beforeJson,
            String afterJson,
            String ipAddress);

    List<AuditEntry> getAuditLog(int limit, int offset);

    int countAuditLog();

    /** Deletes audit log entries older than {@code days} days. */
    int pruneAuditLog(int days);

    // --- User Preferences ---

    Optional<String> getUserPreferences(String username);

    void saveUserPreferences(String username, String preferencesJson);

    // --- Registered Nodes ---

    void registerNode(String nodeId);

    Optional<RegisteredNode> getRegisteredNode(String nodeId);

    List<RegisteredNode> getAllRegisteredNodes();

    void updateNodeLastSeen(String nodeId);

    void deleteRegisteredNode(String nodeId);

    // --- Workflow Intent ---

    void saveTransferIntent(TransferIntent intent);

    List<TransferIntent> getTransferIntents();

    void deleteTransferIntent(UUID playerUuid);

    void saveNodeDrainIntent(NodeDrainIntent intent);

    List<NodeDrainIntent> getNodeDrainIntents();

    void deleteNodeDrainIntent(String nodeId);

    void saveHealingActionIntent(HealingActionIntent intent);

    List<HealingActionIntent> getHealingActionIntents();

    void deleteHealingActionIntent(String instanceId);

    void saveStartRetryIntent(StartRetryIntent intent);

    List<StartRetryIntent> getStartRetryIntents();

    void deleteStartRetryIntent(String instanceId);

    // --- Instance Composition Plans ---

    void saveInstanceCompositionPlan(InstanceCompositionPlan plan);

    Optional<InstanceCompositionPlan> getInstanceCompositionPlan(String instanceId);

    void deleteInstanceCompositionPlan(String instanceId);

    // --- Console Scrollback ---

    /**
     * Persist a single console line for an instance. Backed by a capped
     * collection (global FIFO across all instances), so this is best-effort
     * durable scrollback: very chatty instances will evict quiet ones once
     * the cap is exhausted. Implementations must not throw — persistence
     * failure must never break live console fan-out.
     */
    default void appendConsoleLine(String instanceId, Instant timestamp, String line) {}

    /**
     * Return console lines for {@code instanceId}, optionally bounded by
     * {@code since} (inclusive) and {@code until} (inclusive). Results are
     * sorted ascending by timestamp. {@code limit} caps the row count; the
     * caller is responsible for sensible bounds. Returns empty list when
     * persistence is not configured.
     */
    default List<ConsoleLineRecord> getConsoleHistory(String instanceId, Instant since, Instant until, int limit) {
        return List.of();
    }

    // --- Records ---

    record ConsoleLineRecord(Instant timestamp, String line) {}

    record AuditEntry(
            long id,
            String username,
            String action,
            String resourceType,
            String resourceId,
            String details,
            String beforeJson,
            String afterJson,
            String ipAddress,
            String createdAt) {
        public AuditEntry(
                long id,
                String username,
                String action,
                String resourceType,
                String resourceId,
                String details,
                String ipAddress,
                String createdAt) {
            this(id, username, action, resourceType, resourceId, details, null, null, ipAddress, createdAt);
        }
    }

    record RegisteredNode(String nodeId, Instant firstSeen, Instant lastSeen) {}
}
