package me.prexorjustin.prexorcloud.controller.observability;

import java.net.URI;
import java.net.URISyntaxException;

import me.prexorjustin.prexorcloud.controller.config.ControllerConfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Redacts secret-bearing fields from a {@link ControllerConfig} for inclusion
 * in operator-facing diagnostics bundles. Backs `prexorctl diagnostics bundle`.
 *
 * <p>
 * Redaction targets (replaced with {@code REDACTED} when present):
 * <ul>
 *   <li>{@code security.jwtSecret}</li>
 *   <li>{@code security.initialAdminPassword}</li>
 *   <li>each entry of {@code security.jwtPreviousSecrets}</li>
 *   <li>userinfo password component of {@code database.uri} and {@code redis.uri}</li>
 * </ul>
 *
 * <p>
 * The redactor mutates a deep-copied JSON tree so the in-memory config record
 * is never modified.
 * </p>
 */
public final class ControllerConfigRedactor {

    public static final String REDACTED = "***REDACTED***";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private ControllerConfigRedactor() {}

    public static ObjectNode redact(ControllerConfig config) {
        if (config == null) return MAPPER.createObjectNode();
        ObjectNode root = MAPPER.valueToTree(config);
        redactSecurity(root);
        redactUriPassword(root, "database");
        redactUriPassword(root, "redis");
        return root;
    }

    private static void redactSecurity(ObjectNode root) {
        JsonNode securityNode = root.path("security");
        if (!(securityNode instanceof ObjectNode security)) return;
        if (security.has("jwtSecret") && !security.path("jwtSecret").isNull()) {
            security.put("jwtSecret", REDACTED);
        }
        if (security.has("initialAdminPassword")
                && !security.path("initialAdminPassword").isNull()) {
            security.put("initialAdminPassword", REDACTED);
        }
        JsonNode previous = security.path("jwtPreviousSecrets");
        if (previous instanceof ArrayNode array) {
            ArrayNode redacted = MAPPER.createArrayNode();
            for (int i = 0; i < array.size(); i++) {
                redacted.add(REDACTED);
            }
            security.set("jwtPreviousSecrets", redacted);
        }
    }

    private static void redactUriPassword(ObjectNode root, String section) {
        JsonNode sectionNode = root.path(section);
        if (!(sectionNode instanceof ObjectNode obj)) return;
        if (!obj.has("uri") || obj.path("uri").isNull()) return;
        String uri = obj.path("uri").asText("");
        String redacted = redactUriUserinfo(uri);
        obj.put("uri", redacted);
    }

    /**
     * Replaces the password component of a URI's userinfo with {@code REDACTED}
     * while keeping the username, host, port, path, query, and fragment intact.
     * Leaves URIs without userinfo unchanged.
     */
    public static String redactUriUserinfo(String uri) {
        if (uri == null || uri.isBlank()) return uri;
        try {
            URI parsed = new URI(uri);
            String userInfo = parsed.getUserInfo();
            if (userInfo == null || !userInfo.contains(":")) return uri;
            String user = userInfo.substring(0, userInfo.indexOf(':'));
            String redactedUserInfo = user + ":" + REDACTED;
            URI rebuilt = new URI(
                    parsed.getScheme(),
                    redactedUserInfo,
                    parsed.getHost(),
                    parsed.getPort(),
                    parsed.getPath(),
                    parsed.getQuery(),
                    parsed.getFragment());
            return rebuilt.toString();
        } catch (URISyntaxException _) {
            // Couldn't parse it — fall back to a best-effort string replacement so
            // we never leak through the diagnostics path. We replace the userinfo
            // chunk between scheme:// and the next @.
            int schemeIdx = uri.indexOf("://");
            int atIdx = uri.indexOf('@');
            if (schemeIdx <= 0 || atIdx <= schemeIdx) return uri;
            String prefix = uri.substring(0, schemeIdx + 3);
            String userInfo = uri.substring(schemeIdx + 3, atIdx);
            int colon = userInfo.indexOf(':');
            String user = colon < 0 ? userInfo : userInfo.substring(0, colon);
            return prefix + user + ":" + REDACTED + uri.substring(atIdx);
        }
    }
}
