package me.prexorjustin.prexorcloud.controller.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RedisKeysTest {

    @Test
    void formatsCanonicalRuntimeKeys() {
        assertEquals("prexor:v1:lease:group:lobby", RedisKeys.lease("group:lobby"));
        assertEquals("prexor:v1:lease-token:group:lobby", RedisKeys.leaseToken("group:lobby"));
        assertEquals("prexor:v1:nodeowner:node-1", RedisKeys.nodeOwner("node-1"));
        assertEquals("prexor:v1:node:node-1", RedisKeys.node("node-1"));
        assertEquals("prexor:v1:instance:lobby-1", RedisKeys.instance("lobby-1"));
        assertEquals("prexor:v1:player:uuid", RedisKeys.player("uuid"));
        assertEquals("prexor:v1:plugintoken:token", RedisKeys.pluginToken("token"));
        assertEquals("prexor:v1:jwt:revoked:jti", RedisKeys.jwtRevoked("jti"));
        assertEquals("prexor:v1:platform:chat:", RedisKeys.platformModulePrefix("chat"));
        assertEquals("prexor:v1:ratelimit:ip:127.0.0.1", RedisKeys.rateLimit("ip", "127.0.0.1"));
        assertEquals("prexor:v1:cooldown:lobby", RedisKeys.cooldown("lobby"));
        assertEquals("prexor:v1:workloadseq:lobby-1", RedisKeys.workloadSequence("lobby-1"));
        assertEquals("prexor:v1:console:window:lobby-1", RedisKeys.consoleWindow("lobby-1"));
        assertEquals("prexor:v1:sse:ticket:ticket", RedisKeys.sseTicket("ticket"));
    }

    @Test
    void backupPrefixesCoverControllerOwnedKeyFamilies() {
        assertTrue(RedisKeys.backupPrefixes().contains(RedisKeys.LEASE_PREFIX));
        assertTrue(RedisKeys.backupPrefixes().contains(RedisKeys.LEASE_TOKEN_PREFIX));
        assertTrue(RedisKeys.backupPrefixes().contains(RedisKeys.NODE_OWNER_PREFIX));
        assertTrue(RedisKeys.backupPrefixes().contains(RedisKeys.NODE_PREFIX));
        assertTrue(RedisKeys.backupPrefixes().contains(RedisKeys.INSTANCE_PREFIX));
        assertTrue(RedisKeys.backupPrefixes().contains(RedisKeys.PLAYER_PREFIX));
        assertTrue(RedisKeys.backupPrefixes().contains(RedisKeys.PLUGIN_TOKEN_PREFIX));
        assertTrue(RedisKeys.backupPrefixes().contains(RedisKeys.JWT_PREFIX));
        assertTrue(RedisKeys.backupPrefixes().contains(RedisKeys.PLATFORM_MODULE_PREFIX));
        assertTrue(RedisKeys.backupPrefixes().contains(RedisKeys.RATE_LIMIT_PREFIX));
        assertTrue(RedisKeys.backupPrefixes().contains(RedisKeys.COOLDOWN_PREFIX));
        assertTrue(RedisKeys.backupPrefixes().contains(RedisKeys.WORKLOAD_SEQUENCE_PREFIX));
        assertTrue(RedisKeys.backupPrefixes().contains(RedisKeys.CONSOLE_PREFIX));
        assertTrue(RedisKeys.backupPrefixes().contains(RedisKeys.SSE_PREFIX));
    }

    @Test
    void exposesVersionedSchemaPolicies() {
        assertEquals("prexor:v1:", RedisKeys.NAMESPACE_PREFIX);
        assertTrue(RedisKeys.keyPolicies().stream()
                .anyMatch(policy -> policy.family().equals("sse")));
    }
}
