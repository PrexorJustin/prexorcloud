package me.prexorjustin.prexorcloud.controller.cluster.reload;

import java.util.List;
import java.util.Map;

import me.prexorjustin.prexorcloud.controller.rest.middleware.CorsAllowList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Projects {@code http.cors.allowedOrigins} from the effective cluster config
 * onto the live {@link CorsAllowList}. The cluster config is authoritative for
 * the cluster-shared origin set, so this <em>replaces</em> the list rather than
 * unioning — a removed origin must stop being accepted on the next request.
 */
public final class CorsAllowListReloader implements ClusterConfigSubscriber {

    private static final Logger logger = LoggerFactory.getLogger(CorsAllowListReloader.class);

    private final CorsAllowList allowList;

    public CorsAllowListReloader(CorsAllowList allowList) {
        this.allowList = allowList;
    }

    @Override
    public String name() {
        return "cors-allow-list";
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onClusterConfig(Map<String, Object> effectiveConfig) {
        Object http = effectiveConfig.get("http");
        if (!(http instanceof Map<?, ?> httpMap)) {
            return;
        }
        Object cors = ((Map<String, Object>) httpMap).get("cors");
        if (!(cors instanceof Map<?, ?> corsMap)) {
            return;
        }
        Object origins = ((Map<String, Object>) corsMap).get("allowedOrigins");
        if (!(origins instanceof List<?> list)) {
            return;
        }
        List<String> resolved = list.stream()
                .filter(o -> o instanceof String s && !s.isBlank())
                .map(Object::toString)
                .toList();
        if (resolved.isEmpty()) {
            // Never let a bad patch lock the dashboard out by replacing with an empty
            // allow-list — leave the current origins in place and warn.
            logger.warn("cluster_config http.cors.allowedOrigins is empty — keeping current CORS allow-list");
            return;
        }
        if (allowList.replaceAll(resolved)) {
            logger.info("CORS allow-list reloaded from cluster_config: {} origin(s)", resolved.size());
        }
    }
}
