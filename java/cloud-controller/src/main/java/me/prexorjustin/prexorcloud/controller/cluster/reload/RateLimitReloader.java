package me.prexorjustin.prexorcloud.controller.cluster.reload;

import java.util.Map;

import me.prexorjustin.prexorcloud.controller.config.RateLimitingConfig;
import me.prexorjustin.prexorcloud.controller.rest.middleware.RateLimitMiddleware;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Projects {@code security.rateLimiting} from the effective cluster config onto
 * the live {@link RateLimitMiddleware}. Only the operator-tunable limits
 * ({@code perIpPerMinute}, {@code perUserPerMinute}, {@code failOpenOnRedisError})
 * are reloadable; the stricter hard-coded login bucket is not cluster-config
 * driven.
 */
public final class RateLimitReloader implements ClusterConfigSubscriber {

    private static final Logger logger = LoggerFactory.getLogger(RateLimitReloader.class);

    private final RateLimitMiddleware rateLimiter;

    public RateLimitReloader(RateLimitMiddleware rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public String name() {
        return "rate-limiter";
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onClusterConfig(Map<String, Object> effectiveConfig) {
        Object security = effectiveConfig.get("security");
        if (!(security instanceof Map<?, ?> securityMap)) {
            return;
        }
        Object rateLimiting = ((Map<String, Object>) securityMap).get("rateLimiting");
        if (!(rateLimiting instanceof Map<?, ?> rl)) {
            return;
        }
        Map<String, Object> map = (Map<String, Object>) rl;
        // RateLimitingConfig's compact constructor floors non-positive values back to
        // the defaults, so a partial/garbage patch can't disable rate limiting.
        RateLimitingConfig config = new RateLimitingConfig(
                intValue(map.get("perIpPerMinute")),
                intValue(map.get("perUserPerMinute")),
                boolValue(map.get("failOpenOnRedisError")));
        if (rateLimiter.reconfigure(config)) {
            logger.info(
                    "Rate limiter reloaded from cluster_config: perIp={}/min perUser={}/min failOpen={}",
                    config.perIpPerMinute(),
                    config.perUserPerMinute(),
                    config.failOpenOnRedisError());
        }
    }

    private static int intValue(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static boolean boolValue(Object value) {
        return value instanceof Boolean b && b;
    }
}
