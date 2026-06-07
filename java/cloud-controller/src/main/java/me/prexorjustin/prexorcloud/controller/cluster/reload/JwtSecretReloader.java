package me.prexorjustin.prexorcloud.controller.cluster.reload;

import java.util.List;
import java.util.Map;

import me.prexorjustin.prexorcloud.security.jwt.JwtManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Projects {@code security.jwtSecret} (+ {@code security.jwtPreviousSecrets})
 * from the effective cluster config onto the live {@link JwtManager}, giving a
 * cluster-wide JWT-secret rotation: an operator patches the secret once, every
 * controller rotates its active signing key while keeping the prior key in the
 * acceptance window so in-flight tokens stay valid.
 *
 * <p>The reloader tracks the last secret it applied so it only rotates on an
 * actual change — re-dispatching the same config (startup priming, an unrelated
 * patch) is a no-op. {@link JwtManager} itself rejects malformed secrets, so a
 * bad patch is logged and the active key is left untouched.
 */
public final class JwtSecretReloader implements ClusterConfigSubscriber {

    private static final Logger logger = LoggerFactory.getLogger(JwtSecretReloader.class);

    private final JwtManager jwtManager;
    private volatile String lastAppliedSecret;

    /**
     * @param jwtManager the live manager to rotate
     * @param bootSecret the secret the manager was constructed with, so the first
     *     reload that carries the same value doesn't pointlessly rotate
     */
    public JwtSecretReloader(JwtManager jwtManager, String bootSecret) {
        this.jwtManager = jwtManager;
        this.lastAppliedSecret = bootSecret;
    }

    @Override
    public String name() {
        return "jwt-signing-key";
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onClusterConfig(Map<String, Object> effectiveConfig) {
        Object security = effectiveConfig.get("security");
        if (!(security instanceof Map<?, ?> securityMap)) {
            return;
        }
        Map<String, Object> map = (Map<String, Object>) securityMap;
        Object secretObj = map.get("jwtSecret");
        if (!(secretObj instanceof String secret) || secret.isBlank()) {
            return;
        }
        if (secret.equals(lastAppliedSecret)) {
            return;
        }
        try {
            // Keep the secret being retired in the acceptance window first so tokens
            // signed with it validate through the rotation, then switch the active key.
            if (lastAppliedSecret != null && !lastAppliedSecret.isBlank()) {
                jwtManager.addPreviousKey(lastAppliedSecret);
            }
            Object previous = map.get("jwtPreviousSecrets");
            if (previous instanceof List<?> list) {
                for (Object p : list) {
                    if (p instanceof String s && !s.isBlank()) {
                        jwtManager.addPreviousKey(s);
                    }
                }
            }
            jwtManager.rotate(secret);
            lastAppliedSecret = secret;
            logger.info("JWT signing key rotated from cluster_config");
        } catch (RuntimeException e) {
            logger.error(
                    "cluster_config carried an invalid jwtSecret — active signing key unchanged: {}", e.getMessage());
        }
    }
}
