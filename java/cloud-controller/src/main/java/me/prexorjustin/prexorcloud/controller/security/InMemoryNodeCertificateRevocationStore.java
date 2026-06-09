package me.prexorjustin.prexorcloud.controller.security;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Process-local revocation store for the development profile. Entries expire
 * lazily on read and on {@link #revokedSubjectCns()}.
 */
public final class InMemoryNodeCertificateRevocationStore implements NodeCertificateRevocationStore {

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public void revoke(BigInteger serial, String subjectCn, Duration ttl) {
        Instant expiry = Instant.now().plus(ttl == null ? Duration.ofDays(365) : ttl);
        Entry entry = new Entry(safeCn(subjectCn), expiry);
        if (serial != null) {
            entries.put(serialKey(serial), entry);
        }
        if (!entry.cn.isEmpty()) {
            entries.put(cnKey(entry.cn), entry);
        }
    }

    @Override
    public void unrevoke(BigInteger serial, String subjectCn) {
        if (serial != null) {
            entries.remove(serialKey(serial));
        }
        String cn = safeCn(subjectCn);
        if (!cn.isEmpty()) {
            entries.remove(cnKey(cn));
        }
    }

    @Override
    public boolean isRevoked(BigInteger serial, String subjectCn) {
        Instant now = Instant.now();
        if (serial != null) {
            Entry e = entries.get(serialKey(serial));
            if (e != null) {
                if (e.expiresAt.isAfter(now)) {
                    return true;
                }
                entries.remove(serialKey(serial));
            }
        }
        String cn = safeCn(subjectCn);
        if (!cn.isEmpty()) {
            Entry e = entries.get(cnKey(cn));
            if (e != null) {
                if (e.expiresAt.isAfter(now)) {
                    return true;
                }
                entries.remove(cnKey(cn));
            }
        }
        return false;
    }

    @Override
    public Set<String> revokedSubjectCns() {
        Instant now = Instant.now();
        return entries.entrySet().stream()
                .filter(e ->
                        e.getKey().startsWith("cn:") && e.getValue().expiresAt.isAfter(now))
                .map(e -> e.getValue().cn)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String serialKey(BigInteger serial) {
        return "serial:" + serial.toString(16);
    }

    private static String cnKey(String cn) {
        return "cn:" + cn;
    }

    private static String safeCn(String cn) {
        return cn == null ? "" : cn.trim();
    }

    private record Entry(String cn, Instant expiresAt) {}
}
