package me.prexorjustin.prexorcloud.controller.redis;

import java.time.Duration;
import java.util.List;

/**
 * Central Redis key and prefix registry for controller-owned runtime state.
 * Keep literal key formats here so backup scope, diagnostics, and runtime
 * producers cannot drift independently.
 */
public final class RedisKeys {

    public record KeyFamilyPolicy(
            String family, String prefix, String ttlDescription, String retentionDescription, String purpose) {}

    public static final String VERSION = "v1";
    public static final String NAMESPACE_PREFIX = "prexor:" + VERSION + ":";

    public static final String LEASE_PREFIX = NAMESPACE_PREFIX + "lease:";
    public static final String LEASE_TOKEN_PREFIX = NAMESPACE_PREFIX + "lease-token:";
    public static final String NODE_PREFIX = NAMESPACE_PREFIX + "node:";
    public static final String INSTANCE_PREFIX = NAMESPACE_PREFIX + "instance:";
    public static final String PLAYER_PREFIX = NAMESPACE_PREFIX + "player:";
    public static final String PLUGIN_TOKEN_PREFIX = NAMESPACE_PREFIX + "plugintoken:";
    public static final String JWT_PREFIX = NAMESPACE_PREFIX + "jwt:";
    public static final String JWT_REVOKED_PREFIX = JWT_PREFIX + "revoked:";
    public static final String NODE_CERT_REVOKED_PREFIX = NAMESPACE_PREFIX + "nodecert:revoked:";
    public static final String PLATFORM_MODULE_PREFIX = NAMESPACE_PREFIX + "platform:";
    public static final String RATE_LIMIT_PREFIX = NAMESPACE_PREFIX + "ratelimit:";
    public static final String COOLDOWN_PREFIX = NAMESPACE_PREFIX + "cooldown:";
    public static final String WORKLOAD_SEQUENCE_PREFIX = NAMESPACE_PREFIX + "workloadseq:";
    public static final String START_RETRY_PREFIX = NAMESPACE_PREFIX + "startretry:";
    public static final String START_RETRY_WAKEUP = START_RETRY_PREFIX + "wakeup";
    public static final String START_RETRY_CLAIM_PREFIX = START_RETRY_PREFIX + "claim:";
    public static final String CONSOLE_PREFIX = NAMESPACE_PREFIX + "console:";
    public static final String CONSOLE_WINDOW_PREFIX = CONSOLE_PREFIX + "window:";
    public static final String SSE_PREFIX = NAMESPACE_PREFIX + "sse:";
    public static final String SSE_SEQUENCE = SSE_PREFIX + "sequence";
    public static final String SSE_REPLAY = SSE_PREFIX + "replay-stream";
    public static final String SSE_TICKET_PREFIX = SSE_PREFIX + "ticket:";
    public static final String LOGIN_PREFIX = NAMESPACE_PREFIX + "login:";
    public static final String LOGIN_FAILURES_PREFIX = LOGIN_PREFIX + "fail:";
    public static final String LOGIN_LOCK_PREFIX = LOGIN_PREFIX + "lock:";
    public static final String PASSWORD_RESET_PREFIX = NAMESPACE_PREFIX + "pwreset:";

    private static final List<KeyFamilyPolicy> KEY_POLICIES = List.of(
            new KeyFamilyPolicy(
                    "lease",
                    LEASE_PREFIX,
                    "configured lease TTL (usually scheduler interval * 2)",
                    "expires automatically if not renewed",
                    "active controller lease ownership"),
            new KeyFamilyPolicy(
                    "lease-token",
                    LEASE_TOKEN_PREFIX,
                    "no TTL",
                    "persistent monotonic fencing counter",
                    "fencing token allocation per lease resource"),
            new KeyFamilyPolicy(
                    "node-runtime",
                    NODE_PREFIX,
                    "no TTL",
                    "deleted when node runtime state is removed",
                    "shared node runtime snapshot"),
            new KeyFamilyPolicy(
                    "instance-runtime",
                    INSTANCE_PREFIX,
                    "no TTL",
                    "deleted when instance runtime state is removed",
                    "shared instance runtime snapshot"),
            new KeyFamilyPolicy(
                    "player-runtime",
                    PLAYER_PREFIX,
                    "no TTL",
                    "deleted when player runtime state is removed",
                    "shared player runtime snapshot"),
            new KeyFamilyPolicy(
                    "plugin-token",
                    PLUGIN_TOKEN_PREFIX,
                    "token expiry (default 15 minutes)",
                    "expires automatically",
                    "workload/plugin bearer token persistence"),
            new KeyFamilyPolicy(
                    "jwt-revoked",
                    JWT_PREFIX,
                    "remaining JWT lifetime",
                    "expires automatically",
                    "revoked JWT marker storage"),
            new KeyFamilyPolicy(
                    "node-cert-revoked",
                    NODE_CERT_REVOKED_PREFIX,
                    "remaining certificate validity",
                    "expires automatically",
                    "revoked node certificate marker (serial / CN)"),
            new KeyFamilyPolicy(
                    "platform-module",
                    PLATFORM_MODULE_PREFIX,
                    "module-defined / application-managed",
                    "module-managed",
                    "per-module Redis storage namespace"),
            new KeyFamilyPolicy(
                    "rate-limit",
                    RATE_LIMIT_PREFIX,
                    "60 seconds",
                    "expires automatically",
                    "REST/API rate-limit counters"),
            new KeyFamilyPolicy(
                    "cooldown",
                    COOLDOWN_PREFIX,
                    "group scaling cooldown duration",
                    "expires automatically when configured",
                    "scheduler cooldown windows"),
            new KeyFamilyPolicy(
                    "workload-sequence",
                    WORKLOAD_SEQUENCE_PREFIX,
                    "workload token TTL (default 15 minutes)",
                    "expires automatically",
                    "replay-protection sequence window"),
            new KeyFamilyPolicy(
                    "start-retry",
                    START_RETRY_PREFIX,
                    "wakeups persist until retryAt; claims expire after 2 x scheduler interval",
                    "wakeups removed on claim/cancel; claims expire automatically",
                    "transient start retry wakeups and cross-controller claims"),
            new KeyFamilyPolicy(
                    "console-window",
                    CONSOLE_PREFIX,
                    "2 x active flood window",
                    "expires automatically",
                    "console flood-suppression state"),
            new KeyFamilyPolicy(
                    "sse",
                    SSE_PREFIX,
                    "tickets: 30 seconds; sequence/replay: no TTL",
                    "tickets expire; replay stream is bounded by stream trim",
                    "SSE sequence, replay buffer, and auth tickets"),
            new KeyFamilyPolicy(
                    "login",
                    LOGIN_PREFIX,
                    "failure window (default 15 min); lockout duration (default 15 min)",
                    "expires automatically",
                    "per-username failed-login counters and active lockouts"),
            new KeyFamilyPolicy(
                    "password-reset",
                    PASSWORD_RESET_PREFIX,
                    "configured token TTL (default 30 minutes)",
                    "expires automatically; deleted on consume",
                    "single-use password-reset token bound to a username"));

    private static final List<String> BACKUP_PREFIXES =
            KEY_POLICIES.stream().map(KeyFamilyPolicy::prefix).toList();

    private RedisKeys() {}

    public static List<KeyFamilyPolicy> keyPolicies() {
        return KEY_POLICIES;
    }

    public static List<String> backupPrefixes() {
        return BACKUP_PREFIXES;
    }

    public static Duration leaseTtl(long leaseTtlSeconds) {
        return Duration.ofSeconds(Math.max(1L, leaseTtlSeconds));
    }

    public static Duration defaultPluginTokenTtl() {
        return Duration.ofMinutes(15);
    }

    public static Duration sseTicketTtl() {
        return Duration.ofSeconds(30);
    }

    public static Duration rateLimitWindow() {
        return Duration.ofSeconds(60);
    }

    public static Duration consoleWindowRetention(long windowMs) {
        return Duration.ofMillis(Math.max(1L, windowMs) * 2L);
    }

    public static Duration startRetryClaimTtl(long evaluationIntervalSeconds) {
        return Duration.ofSeconds(Math.max(1L, evaluationIntervalSeconds) * 2L);
    }

    public static Duration sanitizedTtl(Duration ttl) {
        long seconds = ttl == null ? 0L : ttl.getSeconds();
        return Duration.ofSeconds(Math.max(1L, seconds));
    }

    public static String lease(String resource) {
        return LEASE_PREFIX + resource;
    }

    public static String leaseToken(String resource) {
        return LEASE_TOKEN_PREFIX + resource;
    }

    public static String node(String nodeId) {
        return NODE_PREFIX + nodeId;
    }

    public static String instance(String instanceId) {
        return INSTANCE_PREFIX + instanceId;
    }

    public static String player(String uuid) {
        return PLAYER_PREFIX + uuid;
    }

    public static String pluginToken(String token) {
        return PLUGIN_TOKEN_PREFIX + token;
    }

    public static String jwtRevoked(String jti) {
        return JWT_REVOKED_PREFIX + jti;
    }

    public static String nodeCertRevokedSerial(String serialHex) {
        return NODE_CERT_REVOKED_PREFIX + "serial:" + serialHex;
    }

    public static String nodeCertRevokedCn(String cn) {
        return NODE_CERT_REVOKED_PREFIX + "cn:" + cn;
    }

    public static String platformModulePrefix(String sanitizedModuleId) {
        return PLATFORM_MODULE_PREFIX + sanitizedModuleId + ":";
    }

    public static String rateLimit(String bucket, String key) {
        return RATE_LIMIT_PREFIX + bucket + ":" + key;
    }

    public static String cooldown(String group) {
        return COOLDOWN_PREFIX + group;
    }

    public static String workloadSequence(String instanceId) {
        return WORKLOAD_SEQUENCE_PREFIX + instanceId;
    }

    public static String startRetryClaim(String instanceId) {
        return START_RETRY_CLAIM_PREFIX + instanceId;
    }

    public static String consoleWindow(String instanceId) {
        return CONSOLE_WINDOW_PREFIX + instanceId;
    }

    public static String sseTicket(String ticket) {
        return SSE_TICKET_PREFIX + ticket;
    }

    public static String loginFailures(String username) {
        return LOGIN_FAILURES_PREFIX + username;
    }

    public static String loginLock(String username) {
        return LOGIN_LOCK_PREFIX + username;
    }

    public static String passwordResetToken(String tokenId) {
        return PASSWORD_RESET_PREFIX + tokenId;
    }
}
