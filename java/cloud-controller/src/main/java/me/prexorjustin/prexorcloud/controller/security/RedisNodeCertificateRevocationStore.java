package me.prexorjustin.prexorcloud.controller.security;

import java.math.BigInteger;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import me.prexorjustin.prexorcloud.controller.redis.RedisKeys;

import io.lettuce.core.api.sync.RedisCommands;

/**
 * Redis-backed revocation store. Cross-controller-visible, TTL-bounded.
 * Stores both the serial-number and subject-CN keys so a revocation can be
 * applied even if only one identifier is known at the time of the request.
 */
public final class RedisNodeCertificateRevocationStore implements NodeCertificateRevocationStore {

    private final RedisCommands<String, String> commands;

    public RedisNodeCertificateRevocationStore(RedisCommands<String, String> commands) {
        this.commands = commands;
    }

    @Override
    public void revoke(BigInteger serial, String subjectCn, Duration ttl) {
        long seconds =
                RedisKeys.sanitizedTtl(ttl == null ? Duration.ofDays(365) : ttl).getSeconds();
        String cn = safeCn(subjectCn);
        if (serial != null) {
            commands.setex(RedisKeys.nodeCertRevokedSerial(serial.toString(16)), seconds, cn);
        }
        if (!cn.isEmpty()) {
            String marker = serial == null ? "" : serial.toString(16);
            commands.setex(RedisKeys.nodeCertRevokedCn(cn), seconds, marker);
        }
    }

    @Override
    public void unrevoke(BigInteger serial, String subjectCn) {
        if (serial != null) {
            commands.del(RedisKeys.nodeCertRevokedSerial(serial.toString(16)));
        }
        String cn = safeCn(subjectCn);
        if (!cn.isEmpty()) {
            commands.del(RedisKeys.nodeCertRevokedCn(cn));
        }
    }

    @Override
    public boolean isRevoked(BigInteger serial, String subjectCn) {
        if (serial != null && commands.exists(RedisKeys.nodeCertRevokedSerial(serial.toString(16))) > 0) {
            return true;
        }
        String cn = safeCn(subjectCn);
        if (!cn.isEmpty() && commands.exists(RedisKeys.nodeCertRevokedCn(cn)) > 0) {
            return true;
        }
        return false;
    }

    @Override
    public Set<String> revokedSubjectCns() {
        Set<String> result = new HashSet<>();
        var keys = commands.keys(RedisKeys.NODE_CERT_REVOKED_PREFIX + "cn:*");
        if (keys != null) {
            String prefix = RedisKeys.NODE_CERT_REVOKED_PREFIX + "cn:";
            for (String key : keys) {
                if (key.startsWith(prefix)) {
                    result.add(key.substring(prefix.length()));
                }
            }
        }
        return Set.copyOf(result);
    }

    private static String safeCn(String cn) {
        return cn == null ? "" : cn.trim();
    }
}
