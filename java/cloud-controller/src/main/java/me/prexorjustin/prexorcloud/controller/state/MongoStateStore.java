package me.prexorjustin.prexorcloud.controller.state;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import me.prexorjustin.prexorcloud.controller.crash.CrashCauseExtractor;
import me.prexorjustin.prexorcloud.controller.crash.CrashRecord;
import me.prexorjustin.prexorcloud.controller.crash.CrashTrendPoint;
import me.prexorjustin.prexorcloud.controller.deployment.DeploymentRecord;
import me.prexorjustin.prexorcloud.controller.group.spec.VariableDef;
import me.prexorjustin.prexorcloud.controller.scheduler.composition.InstanceCompositionPlan;
import me.prexorjustin.prexorcloud.controller.share.ShareKind;
import me.prexorjustin.prexorcloud.controller.share.ShareRecord;
import me.prexorjustin.prexorcloud.controller.template.TemplateConfig;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MongoDB-backed implementation of {@link StateStore}.
 */
public final class MongoStateStore implements StateStore {

    private static final Logger logger = LoggerFactory.getLogger(MongoStateStore.class);
    private static final ReplaceOptions UPSERT = new ReplaceOptions().upsert(true);
    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final MongoClient client;
    private final MongoDatabase db;

    /**
     * Field stamped on every fenced write with the writing leader's fencing epoch. A write is applied
     * only when the stored value is absent (legacy / first write) or {@code <= myEpoch}; a deposed
     * leader's stale-epoch write is dropped (see {@link #fencedReplace}).
     */
    private static final String OWNER_EPOCH = "ownerEpoch";

    /**
     * Current leadership fencing epoch. {@code <= 0} disables the fence entirely (single-controller
     * installs, and the bootstrap window before {@link #setEpochSource} is wired) so writes behave
     * exactly as before. Wired in production to {@code MongoLeaderElector::currentEpoch}.
     */
    private volatile LongSupplier epochSource = () -> 0L;

    /** Count of fenced writes dropped because a strictly-higher epoch already won (observability). */
    private final AtomicLong fencedWriteRejections = new AtomicLong();

    private MongoCollection<Document> templates;
    private MongoCollection<Document> deployments;
    private MongoCollection<Document> crashes;
    private MongoCollection<Document> auditLog;
    private MongoCollection<Document> nodes;
    private MongoCollection<Document> userPreferences;
    private MongoCollection<Document> workflowTransfers;
    private MongoCollection<Document> workflowDrains;
    private MongoCollection<Document> workflowHealing;
    private MongoCollection<Document> workflowStartRetries;
    private MongoCollection<Document> instanceCompositionPlans;
    private MongoCollection<Document> consoleLines;
    private MongoCollection<Document> shares;
    private MongoCollection<Document> clusterMeta;

    private static final String CLUSTER_META_COLLECTION = "cluster_meta";
    private static final String CLUSTER_META_SINGLETON_ID = "cluster";

    private static final String CONSOLE_LINES_COLLECTION = "console_lines";
    private static final long CONSOLE_LINES_CAP_BYTES = 256L * 1024 * 1024;

    public MongoStateStore(MongoClient client, MongoDatabase db) {
        this.client = client;
        this.db = db;
    }

    @Override
    public void setEpochSource(LongSupplier epochSource) {
        this.epochSource = epochSource == null ? () -> 0L : epochSource;
    }

    /** Count of fenced writes dropped because a strictly-higher epoch already won (observability). */
    @Override
    public long fencedWriteRejections() {
        return fencedWriteRejections.get();
    }

    // --- Lifecycle ---

    @Override
    public void initialize() {
        templates = db.getCollection("templates");
        deployments = db.getCollection("deployments");
        crashes = db.getCollection("crashes");
        auditLog = db.getCollection("audit_log");
        nodes = db.getCollection("nodes");
        userPreferences = db.getCollection("user_preferences");
        workflowTransfers = db.getCollection("workflow_transfers");
        workflowDrains = db.getCollection("workflow_drains");
        workflowHealing = db.getCollection("workflow_healing");
        workflowStartRetries = db.getCollection("workflow_start_retries");
        instanceCompositionPlans = db.getCollection("instance_composition_plans");
        ensureCappedCollection(CONSOLE_LINES_COLLECTION, CONSOLE_LINES_CAP_BYTES);
        consoleLines = db.getCollection(CONSOLE_LINES_COLLECTION);
        shares = db.getCollection("shares");
        clusterMeta = db.getCollection(CLUSTER_META_COLLECTION);

        ensureIndexes();
        logger.info("MongoDB state store initialized (database: {})", db.getName());
    }

    private void ensureIndexes() {
        deployments.createIndex(Indexes.ascending("seqId"), new IndexOptions().unique(true));
        deployments.createIndex(
                Indexes.compoundIndex(Indexes.ascending("groupName"), Indexes.descending("revision")),
                new IndexOptions().unique(true));

        crashes.createIndex(Indexes.ascending("groupName"));
        crashes.createIndex(
                Indexes.descending("crashedAt"),
                new IndexOptions().expireAfter(30L, TimeUnit.DAYS).name("crashes_ttl"));

        auditLog.createIndex(
                Indexes.descending("createdAt"),
                new IndexOptions().expireAfter(90L, TimeUnit.DAYS).name("audit_ttl"));
        auditLog.createIndex(Indexes.ascending("username"));
        workflowTransfers.createIndex(Indexes.ascending("createdAt"));
        workflowDrains.createIndex(Indexes.ascending("requestedAt"));
        workflowHealing.createIndex(Indexes.ascending("createdAt"));
        workflowStartRetries.createIndex(Indexes.ascending("retryAt"));
        instanceCompositionPlans.createIndex(Indexes.descending("createdAt"));
        consoleLines.createIndex(Indexes.compoundIndex(Indexes.ascending("instanceId"), Indexes.ascending("ts")));

        // Share records expire 30 days after the share — matches the longest pste expiry preset (30d)
        // plus a small safety window; revoked entries still expire so the collection stays bounded.
        shares.createIndex(
                Indexes.descending("sharedAt"),
                new IndexOptions().expireAfter(30L, TimeUnit.DAYS).name("shares_ttl"));
        shares.createIndex(Indexes.ascending("kind"));
        shares.createIndex(Indexes.ascending("sharedByUser"));
    }

    private void ensureCappedCollection(String name, long sizeInBytes) {
        for (String existing : db.listCollectionNames()) {
            if (existing.equals(name)) {
                return;
            }
        }
        try {
            db.createCollection(
                    name,
                    new com.mongodb.client.model.CreateCollectionOptions()
                            .capped(true)
                            .sizeInBytes(sizeInBytes));
        } catch (com.mongodb.MongoCommandException e) {
            // Race with another bootstrap instance — collection now exists.
            logger.debug("createCollection({}) raced: {}", name, e.getMessage());
        }
    }

    @Override
    public void close() {
        // MongoClient lifecycle managed externally (bootstrap shutdown hook)
    }

    @Override
    public void runInTransaction(Runnable action) {
        try (var session = client.startSession()) {
            session.withTransaction(() -> {
                action.run();
                return null;
            });
        }
    }

    // --- Templates ---

    @Override
    public void saveTemplate(TemplateConfig config) {
        var doc = new Document("_id", config.name())
                .append("description", config.description())
                .append("platform", config.platform())
                .append("hash", config.hash())
                .append("sizeBytes", config.sizeBytes())
                .append("updatedAt", new Date());

        var existing = templates.find(Filters.eq("_id", config.name())).first();
        if (existing != null) {
            doc.append("createdAt", existing.getDate("createdAt"));
            doc.append("versions", existing.getList("versions", Document.class, List.of()));
            doc.append("variables", existing.getList("variables", Document.class, List.of()));
        } else {
            doc.append("createdAt", new Date());
            doc.append("versions", List.of());
            doc.append("variables", List.of());
        }
        templates.replaceOne(Filters.eq("_id", config.name()), doc, UPSERT);
    }

    @Override
    public Optional<TemplateConfig> getTemplate(String name) {
        var doc = templates.find(Filters.eq("_id", name)).first();
        return Optional.ofNullable(doc).map(MongoStateStore::toTemplateConfig);
    }

    @Override
    public List<TemplateConfig> getAllTemplates() {
        var list = new ArrayList<TemplateConfig>();
        for (var doc : templates.find().sort(Sorts.ascending("_id"))) {
            list.add(toTemplateConfig(doc));
        }
        return list;
    }

    @Override
    public void deleteTemplate(String name) {
        templates.deleteOne(Filters.eq("_id", name));
    }

    // --- Template Versions ---

    @Override
    public void recordTemplateVersion(String templateName, String hash, long sizeBytes) {
        var versionDoc =
                new Document("hash", hash).append("sizeBytes", sizeBytes).append("createdAt", new Date());

        // Only add if this hash doesn't already exist (INSERT OR IGNORE equivalent)
        templates.updateOne(
                Filters.and(
                        Filters.eq("_id", templateName),
                        Filters.not(Filters.elemMatch("versions", Filters.eq("hash", hash)))),
                Updates.push("versions", versionDoc));
    }

    @Override
    public List<TemplateVersion> getTemplateVersions(String templateName) {
        var doc = templates.find(Filters.eq("_id", templateName)).first();
        if (doc == null) return List.of();
        return toVersionList(templateName, doc.getList("versions", Document.class, List.of()));
    }

    @Override
    public Optional<TemplateVersion> getLatestTemplateVersion(String templateName) {
        var versions = getTemplateVersions(templateName);
        return versions.isEmpty() ? Optional.empty() : Optional.of(versions.getFirst());
    }

    @Override
    public void deleteTemplateVersion(String templateName, String hash) {
        templates.updateOne(Filters.eq("_id", templateName), Updates.pull("versions", new Document("hash", hash)));
    }

    // --- Template Variables ---

    @Override
    public List<TemplateVariable> getTemplateVariables(String templateName) {
        var doc = templates.find(Filters.eq("_id", templateName)).first();
        if (doc == null) return List.of();
        return doc.getList("variables", Document.class, List.of()).stream()
                .map(d -> new TemplateVariable(d.getString("key"), d.getString("value"), d.getString("description")))
                .toList();
    }

    @Override
    public void saveTemplateVariables(String templateName, List<TemplateVariable> variables) {
        var varDocs = variables.stream()
                .map(v ->
                        new Document("key", v.key()).append("value", v.value()).append("description", v.description()))
                .toList();
        templates.updateOne(Filters.eq("_id", templateName), Updates.set("variables", varDocs));
    }

    @Override
    public List<VariableDef> getTemplateVariableDefs(String templateName) {
        var doc = templates.find(Filters.eq("_id", templateName)).first();
        if (doc == null) return List.of();
        return doc.getList("variables", Document.class, List.of()).stream()
                .map(VariableDefCodec::fromDocument)
                .toList();
    }

    @Override
    public void saveTemplateVariableDefs(String templateName, List<VariableDef> defs) {
        var varDocs = defs.stream().map(VariableDefCodec::toDocument).toList();
        templates.updateOne(Filters.eq("_id", templateName), Updates.set("variables", varDocs));
    }

    // --- Deployments ---

    @Override
    public DeploymentRecord createDeployment(DeploymentRecord record) {
        int seqId = nextSequence("deployment_id");
        var doc = new Document("seqId", seqId)
                .append("groupName", record.groupName())
                .append("revision", record.revision())
                .append("trigger", record.trigger())
                .append("strategy", record.strategy())
                .append("state", record.state())
                .append("templateSnapshot", record.templateSnapshot())
                .append("configSnapshot", record.configSnapshot())
                .append("totalInstances", record.totalInstances())
                .append("updatedInstances", record.updatedInstances())
                .append("createdAt", new Date())
                .append("completedAt", null)
                .append("rollbackOf", record.rollbackOf());

        deployments.insertOne(doc);
        return new DeploymentRecord(
                seqId,
                record.groupName(),
                record.revision(),
                record.trigger(),
                record.strategy(),
                record.state(),
                record.templateSnapshot(),
                record.configSnapshot(),
                record.totalInstances(),
                record.updatedInstances(),
                record.createdAt(),
                record.completedAt(),
                record.rollbackOf());
    }

    @Override
    public Optional<DeploymentRecord> getDeployment(String groupName, int revision) {
        var doc = deployments
                .find(Filters.and(Filters.eq("groupName", groupName), Filters.eq("revision", revision)))
                .first();
        return Optional.ofNullable(doc).map(MongoStateStore::toDeploymentRecord);
    }

    @Override
    public List<DeploymentRecord> getDeployments(String groupName, int limit, int offset) {
        var list = new ArrayList<DeploymentRecord>();
        for (var doc : deployments
                .find(Filters.eq("groupName", groupName))
                .sort(Sorts.descending("revision"))
                .skip(Math.max(0, offset))
                .limit(limit)) {
            list.add(toDeploymentRecord(doc));
        }
        return list;
    }

    @Override
    public int countDeployments(String groupName) {
        return Math.toIntExact(deployments.countDocuments(Filters.eq("groupName", groupName)));
    }

    @Override
    public void updateDeploymentState(int id, String state) {
        var updates = new ArrayList<org.bson.conversions.Bson>();
        updates.add(Updates.set("state", state));
        if (List.of("COMPLETED", "FAILED", "ROLLED_BACK").contains(state)) {
            updates.add(Updates.set("completedAt", new Date()));
        }
        fencedUpdate(deployments, Filters.eq("seqId", id), Updates.combine(updates));
    }

    @Override
    public void updateDeploymentProgress(int id, int updatedInstances) {
        fencedUpdate(deployments, Filters.eq("seqId", id), Updates.set("updatedInstances", updatedInstances));
    }

    @Override
    public List<DeploymentRecord> getDeploymentsByState(String state, int limit) {
        var list = new ArrayList<DeploymentRecord>();
        for (var doc : deployments
                .find(Filters.eq("state", state))
                .sort(Sorts.ascending("createdAt"))
                .limit(limit)) {
            list.add(toDeploymentRecord(doc));
        }
        return list;
    }

    // --- Crashes ---

    @Override
    public void saveCrash(CrashRecord record) {
        var doc = new Document("_id", record.id())
                .append("instanceId", record.instanceId())
                .append("groupName", record.group())
                .append("nodeId", record.nodeId())
                .append("exitCode", record.exitCode())
                .append("classification", record.classification())
                .append("causeSummary", record.causeSummary())
                .append("signature", record.signature())
                .append("logTail", record.logTail())
                .append("uptimeMs", record.uptimeMs())
                .append("crashedAt", Date.from(record.crashedAt()));
        crashes.replaceOne(Filters.eq("_id", record.id()), doc, UPSERT);
    }

    @Override
    public Optional<CrashRecord> getCrash(String id) {
        var doc = crashes.find(Filters.eq("_id", id)).first();
        return Optional.ofNullable(doc).map(MongoStateStore::toCrashRecord);
    }

    @Override
    public List<CrashRecord> getCrashes(String group, String nodeId, int limit, int offset) {
        var filters = new ArrayList<org.bson.conversions.Bson>();
        if (group != null && !group.isEmpty()) filters.add(Filters.eq("groupName", group));
        if (nodeId != null && !nodeId.isEmpty()) filters.add(Filters.eq("nodeId", nodeId));

        var filter = filters.isEmpty() ? new Document() : Filters.and(filters);
        var list = new ArrayList<CrashRecord>();
        for (var doc : crashes.find(filter)
                .sort(Sorts.descending("crashedAt"))
                .skip(Math.max(0, offset))
                .limit(limit)) {
            list.add(toCrashRecord(doc));
        }
        return list;
    }

    @Override
    public int countCrashes(String group, String nodeId) {
        var filters = new ArrayList<org.bson.conversions.Bson>();
        if (group != null && !group.isEmpty()) filters.add(Filters.eq("groupName", group));
        if (nodeId != null && !nodeId.isEmpty()) filters.add(Filters.eq("nodeId", nodeId));
        var filter = filters.isEmpty() ? new Document() : Filters.and(filters);
        return Math.toIntExact(crashes.countDocuments(filter));
    }

    @Override
    public List<CrashTrendPoint> getCrashTrend(String group, String nodeId, Instant since) {
        var filters = new ArrayList<org.bson.conversions.Bson>();
        if (group != null && !group.isEmpty()) filters.add(Filters.eq("groupName", group));
        if (nodeId != null && !nodeId.isEmpty()) filters.add(Filters.eq("nodeId", nodeId));
        filters.add(Filters.gte("crashedAt", Date.from(since)));
        var filter = Filters.and(filters);
        var list = new ArrayList<CrashTrendPoint>();
        for (var doc : crashes.find(filter).projection(new Document("crashedAt", 1).append("classification", 1))) {
            Date d = doc.getDate("crashedAt");
            if (d == null) continue;
            list.add(new CrashTrendPoint(d.toInstant(), doc.getString("classification")));
        }
        return list;
    }

    // --- Shares ---

    @Override
    public void saveShareRecord(ShareRecord record) {
        var doc = new Document("_id", record.id())
                .append("kind", record.kind().name())
                .append("resourceId", record.resourceId())
                .append("pasteUrl", record.pasteUrl())
                .append("rawUrl", record.rawUrl())
                .append("deleteToken", record.deleteToken())
                .append("expiresAt", record.expiresAt() == null ? null : Date.from(record.expiresAt()))
                .append("burnAfterRead", record.burnAfterRead())
                .append("isPrivate", record.isPrivate())
                .append("sizeBytes", record.sizeBytes())
                .append("sharedByUser", record.sharedByUser())
                .append("sharedAt", Date.from(record.sharedAt()))
                .append("revokedAt", record.revokedAt() == null ? null : Date.from(record.revokedAt()));
        shares.replaceOne(Filters.eq("_id", record.id()), doc, UPSERT);
    }

    @Override
    public Optional<ShareRecord> getShareRecord(String id) {
        if (id == null || id.isEmpty()) return Optional.empty();
        var doc = shares.find(Filters.eq("_id", id)).first();
        return Optional.ofNullable(doc).map(MongoStateStore::toShareRecord);
    }

    @Override
    public List<ShareRecord> getShareRecords(ShareKind kind, boolean activeOnly, int limit, int offset) {
        var filters = new ArrayList<org.bson.conversions.Bson>();
        if (kind != null) filters.add(Filters.eq("kind", kind.name()));
        if (activeOnly) filters.add(Filters.eq("revokedAt", null));
        var filter = filters.isEmpty() ? new Document() : Filters.and(filters);
        int cappedLimit = Math.min(Math.max(limit, 1), 500);
        var list = new ArrayList<ShareRecord>();
        for (var doc : shares.find(filter)
                .sort(Sorts.descending("sharedAt"))
                .skip(Math.max(0, offset))
                .limit(cappedLimit)) {
            list.add(toShareRecord(doc));
        }
        return list;
    }

    @Override
    public int countShareRecords(ShareKind kind, boolean activeOnly) {
        var filters = new ArrayList<org.bson.conversions.Bson>();
        if (kind != null) filters.add(Filters.eq("kind", kind.name()));
        if (activeOnly) filters.add(Filters.eq("revokedAt", null));
        var filter = filters.isEmpty() ? new Document() : Filters.and(filters);
        return Math.toIntExact(shares.countDocuments(filter));
    }

    @Override
    public void markShareRevoked(String id, Instant when) {
        if (id == null || id.isEmpty()) return;
        shares.updateOne(Filters.eq("_id", id), Updates.set("revokedAt", Date.from(when)));
    }

    private static ShareRecord toShareRecord(Document doc) {
        ShareKind kind;
        try {
            kind = ShareKind.valueOf(doc.getString("kind"));
        } catch (Exception ignored) {
            kind = ShareKind.CRASH;
        }
        return new ShareRecord(
                doc.getString("_id"),
                kind,
                doc.getString("resourceId"),
                doc.getString("pasteUrl"),
                doc.getString("rawUrl"),
                doc.getString("deleteToken"),
                doc.getDate("expiresAt") == null
                        ? null
                        : doc.getDate("expiresAt").toInstant(),
                doc.getBoolean("burnAfterRead", false),
                doc.getBoolean("isPrivate", true),
                doc.getLong("sizeBytes") == null ? 0L : doc.getLong("sizeBytes"),
                doc.getString("sharedByUser"),
                doc.getDate("sharedAt") == null
                        ? Instant.EPOCH
                        : doc.getDate("sharedAt").toInstant(),
                doc.getDate("revokedAt") == null
                        ? null
                        : doc.getDate("revokedAt").toInstant());
    }

    // --- Audit Log ---

    @Override
    public void audit(
            String username, String action, String resourceType, String resourceId, String details, String ipAddress) {
        audit(username, action, resourceType, resourceId, details, null, null, ipAddress);
    }

    @Override
    public void audit(
            String username,
            String action,
            String resourceType,
            String resourceId,
            String details,
            String beforeJson,
            String afterJson,
            String ipAddress) {
        var doc = new Document()
                .append("username", username != null ? username : "system")
                .append("action", action)
                .append("resourceType", resourceType)
                .append("resourceId", resourceId)
                .append("details", details)
                .append("ipAddress", ipAddress)
                .append("createdAt", new Date());
        if (beforeJson != null) doc.append("before", beforeJson);
        if (afterJson != null) doc.append("after", afterJson);
        auditLog.insertOne(doc);
    }

    @Override
    public List<AuditEntry> getAuditLog(int limit, int offset) {
        int cappedLimit = Math.min(limit, 1000);
        var list = new ArrayList<AuditEntry>();
        for (var doc :
                auditLog.find().sort(Sorts.descending("_id")).skip(offset).limit(cappedLimit)) {
            list.add(toAuditEntry(doc));
        }
        return list;
    }

    @Override
    public AuditLogPage getAuditLogSeek(String cursor, int limit) {
        int cappedLimit = Math.clamp(limit, 1, 1000);
        // Sort by _id descending (newest first). ObjectId is monotonic by insertion
        // time, so "older than cursor" is _id < cursor — a range scan over the
        // primary _id index, no skip() and no offset-proportional work.
        var query = auditLog.find();
        if (cursor != null && !cursor.isBlank()) {
            query = auditLog.find(Filters.lt("_id", new ObjectId(cursor)));
        }
        // Fetch one extra to decide whether a further page exists without a count.
        var docs = new ArrayList<Document>(cappedLimit + 1);
        query.sort(Sorts.descending("_id")).limit(cappedLimit + 1).into(docs);

        String nextCursor = null;
        if (docs.size() > cappedLimit) {
            // The last in-page doc is the boundary; its _id is the next cursor.
            Document lastInPage = docs.get(cappedLimit - 1);
            nextCursor = lastInPage.getObjectId("_id").toHexString();
            docs.subList(cappedLimit, docs.size()).clear();
        }
        var entries = new ArrayList<AuditEntry>(docs.size());
        for (var doc : docs) {
            entries.add(toAuditEntry(doc));
        }
        return new AuditLogPage(entries, nextCursor);
    }

    private static AuditEntry toAuditEntry(Document doc) {
        return new AuditEntry(
                doc.getObjectId("_id").hashCode() & 0x7FFFFFFFL,
                doc.getString("username"),
                doc.getString("action"),
                doc.getString("resourceType"),
                doc.getString("resourceId"),
                doc.getString("details"),
                doc.getString("before"),
                doc.getString("after"),
                doc.getString("ipAddress"),
                formatDate(doc.getDate("createdAt")));
    }

    @Override
    public int countAuditLog() {
        return Math.toIntExact(auditLog.countDocuments());
    }

    @Override
    public int pruneAuditLog(int days) {
        // TTL index (90-day expiry) handles automatic rotation; manual pruning is a
        // no-op.
        return 0;
    }

    // --- User Preferences ---

    @Override
    public Optional<String> getUserPreferences(String username) {
        var doc = userPreferences.find(Filters.eq("_id", username)).first();
        return Optional.ofNullable(doc).map(d -> d.getString("preferences"));
    }

    @Override
    public void saveUserPreferences(String username, String preferencesJson) {
        var doc = new Document("_id", username)
                .append("preferences", preferencesJson)
                .append("updatedAt", new Date());
        userPreferences.replaceOne(Filters.eq("_id", username), doc, UPSERT);
    }

    // --- Registered Nodes ---

    @Override
    public void registerNode(String nodeId) {
        var now = new Date();
        var doc = new Document("_id", nodeId).append("firstSeen", now).append("lastSeen", now);
        fencedReplace(nodes, nodeId, doc);
    }

    @Override
    public Optional<RegisteredNode> getRegisteredNode(String nodeId) {
        var doc = nodes.find(Filters.eq("_id", nodeId)).first();
        return Optional.ofNullable(doc).map(MongoStateStore::toRegisteredNode);
    }

    @Override
    public List<RegisteredNode> getAllRegisteredNodes() {
        var list = new ArrayList<RegisteredNode>();
        for (var doc : nodes.find().sort(Sorts.descending("firstSeen"))) {
            list.add(toRegisteredNode(doc));
        }
        return list;
    }

    @Override
    public void updateNodeLastSeen(String nodeId) {
        nodes.updateOne(Filters.eq("_id", nodeId), Updates.set("lastSeen", new Date()));
    }

    @Override
    public void deleteRegisteredNode(String nodeId) {
        fencedDelete(nodes, nodeId);
    }

    // --- Workflow Intent ---

    @Override
    public void saveTransferIntent(TransferIntent intent) {
        var doc = new Document("_id", intent.playerUuid().toString())
                .append("playerUuid", intent.playerUuid().toString())
                .append("targetInstanceId", intent.targetInstanceId())
                .append("createdAt", Date.from(intent.createdAt()));
        fencedReplace(workflowTransfers, intent.playerUuid().toString(), doc);
    }

    @Override
    public List<TransferIntent> getTransferIntents() {
        var list = new ArrayList<TransferIntent>();
        for (var doc : workflowTransfers.find().sort(Sorts.ascending("createdAt"))) {
            list.add(new TransferIntent(
                    java.util.UUID.fromString(doc.getString("playerUuid")),
                    doc.getString("targetInstanceId"),
                    doc.getDate("createdAt") != null ? doc.getDate("createdAt").toInstant() : Instant.now()));
        }
        return list;
    }

    @Override
    public void deleteTransferIntent(java.util.UUID playerUuid) {
        fencedDelete(workflowTransfers, playerUuid.toString());
    }

    @Override
    public void saveNodeDrainIntent(NodeDrainIntent intent) {
        var doc = new Document("_id", intent.nodeId())
                .append("nodeId", intent.nodeId())
                .append("shutdownAfterDrain", intent.shutdownAfterDrain())
                .append("kickMessage", intent.kickMessage())
                .append("requestedAt", Date.from(intent.requestedAt()))
                .append("timeoutAt", Date.from(intent.timeoutAt()))
                .append(
                        "drainingInstanceIds",
                        intent.drainingInstanceIds().stream().sorted().toList());
        fencedReplace(workflowDrains, intent.nodeId(), doc);
    }

    @Override
    public List<NodeDrainIntent> getNodeDrainIntents() {
        var list = new ArrayList<NodeDrainIntent>();
        for (var doc : workflowDrains.find().sort(Sorts.ascending("requestedAt"))) {
            list.add(new NodeDrainIntent(
                    doc.getString("nodeId"),
                    doc.getBoolean("shutdownAfterDrain", false),
                    doc.getString("kickMessage"),
                    doc.getDate("requestedAt") != null
                            ? doc.getDate("requestedAt").toInstant()
                            : Instant.now(),
                    doc.getDate("timeoutAt") != null ? doc.getDate("timeoutAt").toInstant() : Instant.now(),
                    Set.copyOf(doc.getList("drainingInstanceIds", String.class, List.of()))));
        }
        return list;
    }

    @Override
    public void deleteNodeDrainIntent(String nodeId) {
        fencedDelete(workflowDrains, nodeId);
    }

    @Override
    public void saveHealingActionIntent(HealingActionIntent intent) {
        var doc = new Document("_id", intent.instanceId())
                .append("instanceId", intent.instanceId())
                .append("groupName", intent.groupName())
                .append("reason", intent.reason())
                .append("createdAt", Date.from(intent.createdAt()));
        fencedReplace(workflowHealing, intent.instanceId(), doc);
    }

    @Override
    public List<HealingActionIntent> getHealingActionIntents() {
        var list = new ArrayList<HealingActionIntent>();
        for (var doc : workflowHealing.find().sort(Sorts.ascending("createdAt"))) {
            list.add(new HealingActionIntent(
                    doc.getString("instanceId"),
                    doc.getString("groupName"),
                    doc.getString("reason"),
                    doc.getDate("createdAt") != null ? doc.getDate("createdAt").toInstant() : Instant.now()));
        }
        return list;
    }

    @Override
    public void deleteHealingActionIntent(String instanceId) {
        fencedDelete(workflowHealing, instanceId);
    }

    @Override
    public void saveStartRetryIntent(StartRetryIntent intent) {
        var doc = new Document("_id", intent.instanceId())
                .append("instanceId", intent.instanceId())
                .append("groupName", intent.groupName())
                .append("nodeId", intent.nodeId())
                .append("reason", intent.reason())
                .append("planHash", intent.planHash())
                .append("attempt", intent.attempt())
                .append("retryAt", Date.from(intent.retryAt()))
                .append("createdAt", Date.from(intent.createdAt()));
        fencedReplace(workflowStartRetries, intent.instanceId(), doc);
    }

    @Override
    public List<StartRetryIntent> getStartRetryIntents() {
        var list = new ArrayList<StartRetryIntent>();
        for (var doc : workflowStartRetries.find().sort(Sorts.ascending("retryAt"))) {
            list.add(new StartRetryIntent(
                    doc.getString("instanceId"),
                    doc.getString("groupName"),
                    doc.getString("nodeId"),
                    doc.getString("reason"),
                    doc.getString("planHash"),
                    doc.getInteger("attempt", 0),
                    doc.getDate("retryAt") != null ? doc.getDate("retryAt").toInstant() : Instant.now(),
                    doc.getDate("createdAt") != null ? doc.getDate("createdAt").toInstant() : Instant.now()));
        }
        return list;
    }

    @Override
    public void deleteStartRetryIntent(String instanceId) {
        fencedDelete(workflowStartRetries, instanceId);
    }

    // --- Instance Composition Plans ---

    @Override
    public void saveInstanceCompositionPlan(InstanceCompositionPlan plan) {
        var doc = new Document("_id", plan.instanceId())
                .append("groupName", plan.groupName())
                .append("nodeId", plan.nodeId())
                .append("planHash", plan.planHash())
                .append("createdAt", Date.from(plan.createdAt()))
                .append("payload", toJson(plan));
        fencedReplace(instanceCompositionPlans, plan.instanceId(), doc);
    }

    @Override
    public Optional<InstanceCompositionPlan> getInstanceCompositionPlan(String instanceId) {
        var doc = instanceCompositionPlans.find(Filters.eq("_id", instanceId)).first();
        if (doc == null) {
            return Optional.empty();
        }
        return Optional.of(fromJson(doc.getString("payload"), InstanceCompositionPlan.class));
    }

    @Override
    public void deleteInstanceCompositionPlan(String instanceId) {
        fencedDelete(instanceCompositionPlans, instanceId);
    }

    // --- Console Scrollback ---

    @Override
    public void appendConsoleLine(String instanceId, Instant timestamp, String line) {
        if (instanceId == null || instanceId.isEmpty() || line == null) return;
        try {
            consoleLines.insertOne(new Document()
                    .append("instanceId", instanceId)
                    .append("ts", Date.from(timestamp != null ? timestamp : Instant.now()))
                    .append("line", line));
        } catch (RuntimeException e) {
            logger.warn("Failed to persist console line for instance {}: {}", instanceId, e.toString());
        }
    }

    @Override
    public List<ConsoleLineRecord> getConsoleHistory(String instanceId, Instant since, Instant until, int limit) {
        if (instanceId == null || instanceId.isEmpty() || limit <= 0) return List.of();
        var filters = new ArrayList<org.bson.conversions.Bson>();
        filters.add(Filters.eq("instanceId", instanceId));
        if (since != null) filters.add(Filters.gte("ts", Date.from(since)));
        if (until != null) filters.add(Filters.lte("ts", Date.from(until)));
        var list = new ArrayList<ConsoleLineRecord>();
        for (var doc : consoleLines
                .find(Filters.and(filters))
                .sort(Sorts.ascending("ts"))
                .limit(limit)) {
            Date ts = doc.getDate("ts");
            list.add(new ConsoleLineRecord(ts != null ? ts.toInstant() : Instant.EPOCH, doc.getString("line")));
        }
        return list;
    }

    // --- Cluster identity ---

    @Override
    public Optional<String> getClusterId() {
        Document doc =
                clusterMeta.find(Filters.eq("_id", CLUSTER_META_SINGLETON_ID)).first();
        return doc == null ? Optional.empty() : Optional.ofNullable(doc.getString("clusterId"));
    }

    @Override
    public void dropClusterMeta() {
        clusterMeta.drop();
    }

    // --- Epoch fencing (single-writer correctness) ---

    /**
     * Epoch-fenced upsert keyed on {@code _id}. Stamps {@link #OWNER_EPOCH} with the current leadership
     * epoch and applies the replacement iff no doc with a strictly-higher epoch already exists — so a
     * deposed leader's stale-epoch write can never clobber the live leader's state. Atomic on the
     * unique {@code _id}: the conditional replace and the absent-doc insert resolve concurrency through
     * the index, not a read-modify-write.
     *
     * <p>Fail-soft by design: a rejected write is logged + counted, never thrown, and {@code epoch <= 0}
     * disables the fence (plain upsert) so single-controller installs and the bootstrap window are
     * unchanged. The {@code <=} comparison lets the same leader (and the fixed-epoch single-controller
     * {@code alwaysLeader}) re-write its own docs. {@code doc} must not already carry {@code _id}-foreign
     * state; the helper appends {@link #OWNER_EPOCH} in place.
     *
     * @return {@code true} if the write was applied (or the fence is disabled); {@code false} if dropped.
     */
    private boolean fencedReplace(MongoCollection<Document> coll, String id, Document doc) {
        long me = epochSource.getAsLong();
        if (me <= 0L) {
            coll.replaceOne(Filters.eq("_id", id), doc, UPSERT);
            return true;
        }
        doc.append(OWNER_EPOCH, me);
        var result = coll.replaceOne(
                Filters.and(
                        Filters.eq("_id", id),
                        Filters.or(Filters.exists(OWNER_EPOCH, false), Filters.lte(OWNER_EPOCH, me))),
                doc);
        if (result.getMatchedCount() > 0) {
            return true; // replaced an absent-epoch / same-or-lower-epoch doc
        }
        // No match: the doc is absent (insert it) or a strictly-higher-epoch doc exists (fence it).
        try {
            coll.insertOne(doc);
            return true;
        } catch (MongoWriteException e) {
            if (e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
                return rejectFencedWrite(coll, id, me);
            }
            throw e;
        }
    }

    /**
     * Epoch-fenced in-place update. Narrows {@code idFilter} so it matches only when the stored epoch
     * is absent or {@code <= myEpoch}, and stamps {@link #OWNER_EPOCH}. A 0-match (target absent — an
     * in-place update is already a no-op there — or fenced) returns {@code false} without throwing.
     */
    private boolean fencedUpdate(MongoCollection<Document> coll, org.bson.conversions.Bson idFilter, org.bson.conversions.Bson update) {
        long me = epochSource.getAsLong();
        if (me <= 0L) {
            coll.updateOne(idFilter, update);
            return true;
        }
        var fenced = Filters.and(
                idFilter, Filters.or(Filters.exists(OWNER_EPOCH, false), Filters.lte(OWNER_EPOCH, me)));
        var result = coll.updateOne(fenced, Updates.combine(update, Updates.set(OWNER_EPOCH, me)));
        if (result.getMatchedCount() > 0) {
            return true;
        }
        // Absent (no-op) or fenced; can't cheaply distinguish, so this is a debug-level signal only.
        logger.debug(
                "Fenced update matched 0 docs in {} (myEpoch={}) — target absent or out-epoched",
                coll.getNamespace().getCollectionName(),
                me);
        return false;
    }

    /**
     * Epoch-fenced delete keyed on {@code _id}. A deposed leader can't delete a doc the live leader has
     * already recreated at a higher epoch — the delete simply matches nothing. Fail-soft (no throw).
     */
    private boolean fencedDelete(MongoCollection<Document> coll, String id) {
        long me = epochSource.getAsLong();
        if (me <= 0L) {
            coll.deleteOne(Filters.eq("_id", id));
            return true;
        }
        var result = coll.deleteOne(Filters.and(
                Filters.eq("_id", id),
                Filters.or(Filters.exists(OWNER_EPOCH, false), Filters.lte(OWNER_EPOCH, me))));
        return result.getDeletedCount() > 0;
    }

    private boolean rejectFencedWrite(MongoCollection<Document> coll, String id, long myEpoch) {
        fencedWriteRejections.incrementAndGet();
        logger.warn(
                "Fenced write rejected in {} (_id={}, myEpoch={}): a higher-epoch write already won — "
                        + "this controller is likely deposed; dropping the stale write",
                coll.getNamespace().getCollectionName(),
                id,
                myEpoch);
        return false;
    }

    // --- Helpers ---

    /**
     * Atomically increments and returns a named sequence counter. Uses a dedicated
     * "counters" collection.
     */
    private int nextSequence(String name) {
        var counters = db.getCollection("counters");
        var result = counters.findOneAndUpdate(
                Filters.eq("_id", name),
                Updates.inc("seq", 1),
                new com.mongodb.client.model.FindOneAndUpdateOptions()
                        .upsert(true)
                        .returnDocument(com.mongodb.client.model.ReturnDocument.AFTER));
        return result.getInteger("seq");
    }

    private static TemplateConfig toTemplateConfig(Document doc) {
        return new TemplateConfig(
                doc.getString("_id"),
                doc.getString("description"),
                doc.getString("platform"),
                doc.getString("hash"),
                doc.getLong("sizeBytes") != null ? doc.getLong("sizeBytes") : 0L);
    }

    private static List<TemplateVersion> toVersionList(String templateName, List<Document> docs) {
        return docs.stream()
                .sorted((a, b) -> {
                    Date da = a.getDate("createdAt");
                    Date db = b.getDate("createdAt");
                    if (da == null || db == null) return 0;
                    return db.compareTo(da); // descending
                })
                .map(d -> new TemplateVersion(
                        templateName,
                        d.getString("hash"),
                        d.getLong("sizeBytes") != null ? d.getLong("sizeBytes") : 0L,
                        formatDate(d.getDate("createdAt"))))
                .toList();
    }

    private static DeploymentRecord toDeploymentRecord(Document doc) {
        int id = doc.getInteger("seqId", 0);
        return new DeploymentRecord(
                id,
                doc.getString("groupName"),
                doc.getInteger("revision", 0),
                doc.getString("trigger"),
                doc.getString("strategy"),
                doc.getString("state"),
                doc.getString("templateSnapshot"),
                doc.getString("configSnapshot"),
                doc.getInteger("totalInstances", 0),
                doc.getInteger("updatedInstances", 0),
                formatDate(doc.getDate("createdAt")),
                formatDate(doc.getDate("completedAt")),
                doc.getInteger("rollbackOf"));
    }

    private static CrashRecord toCrashRecord(Document doc) {
        @SuppressWarnings("unchecked")
        List<String> logTail = doc.getList("logTail", String.class, List.of());
        String classification = doc.getString("classification");
        int exitCode = doc.getInteger("exitCode", 0);
        String causeSummary = doc.getString("causeSummary");
        String signature = doc.getString("signature");
        if (causeSummary == null || signature == null) {
            // Backfill from logTail for crashes persisted before the field landed.
            var cause = CrashCauseExtractor.extract(logTail, classification, exitCode);
            if (causeSummary == null) causeSummary = cause.summary();
            if (signature == null) signature = cause.signature();
        }
        return new CrashRecord(
                doc.getString("_id"),
                doc.getString("instanceId"),
                doc.getString("groupName"),
                doc.getString("nodeId"),
                exitCode,
                classification,
                causeSummary,
                signature,
                logTail,
                doc.getLong("uptimeMs") != null ? doc.getLong("uptimeMs") : 0L,
                doc.getDate("crashedAt") != null ? doc.getDate("crashedAt").toInstant() : Instant.now());
    }

    private static RegisteredNode toRegisteredNode(Document doc) {
        return new RegisteredNode(
                doc.getString("_id"),
                doc.getDate("firstSeen") != null ? doc.getDate("firstSeen").toInstant() : Instant.now(),
                doc.getDate("lastSeen") != null ? doc.getDate("lastSeen").toInstant() : Instant.now());
    }

    private static String formatDate(Date date) {
        return date != null ? date.toInstant().toString() : null;
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize state payload", e);
        }
    }

    private static <T> T fromJson(String value, Class<T> type) {
        try {
            return JSON.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize state payload", e);
        }
    }

    public MongoDatabase database() {
        return db;
    }
}
