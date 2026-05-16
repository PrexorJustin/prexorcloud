package me.prexorjustin.prexorcloud.controller.observability;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative text-line scrubber for operator-facing log shares. Sibling to
 * {@link ControllerConfigRedactor}; both replace secret-bearing substrings with
 * the same {@link ControllerConfigRedactor#REDACTED} marker.
 *
 * <p>
 * Patterns applied (in order):
 * <ol>
 *   <li>{@code Authorization: Bearer …} / {@code Authorization: Basic …} headers and bare {@code Bearer …} tokens.</li>
 *   <li>JWT-like tokens — {@code eyJ…\.…\.…} (three base64url segments separated by dots).</li>
 *   <li>Distinctive provider tokens: AWS access key IDs ({@code AKIA…}), GitHub personal access tokens ({@code ghp_…} / {@code github_pat_…}), Slack tokens ({@code xox[abpors]-…}).</li>
 *   <li>URI userinfo passwords — any {@code scheme://user:password@host} occurrence is rewritten via
 *       {@link ControllerConfigRedactor#redactUriUserinfo(String)}.</li>
 *   <li>IPv4 dotted-quad and IPv6 colon-hex addresses.</li>
 *   <li>Env-var-style and JSON {@code key=value} / {@code "key":"value"} pairs where the key
 *       contains {@code password}/{@code passwd}/{@code secret}/{@code token}/{@code api[_-]?key}
 *       (e.g. {@code AWS_SECRET_ACCESS_KEY}, {@code DB_PASSWORD}, {@code MY_API_TOKEN}).</li>
 * </ol>
 *
 * <p>
 * The catalogue is intentionally narrow — it MUST NOT mangle normal stack
 * traces, ISO timestamps, instance/node UUIDs, or absolute file paths. Every
 * pattern has positive + negative coverage in {@code LogRedactorTest}.
 * </p>
 */
public final class LogRedactor {

    public static final String REDACTED = ControllerConfigRedactor.REDACTED;

    // Authorization: Bearer <token> — header form (case-insensitive header name)
    private static final Pattern AUTHORIZATION_HEADER =
            Pattern.compile("(?i)(Authorization\\s*[:=]\\s*Bearer\\s+)\\S+");
    // Authorization: Basic <base64> — base64 carries user:password
    private static final Pattern AUTHORIZATION_BASIC =
            Pattern.compile("(?i)(Authorization\\s*[:=]\\s*Basic\\s+)[A-Za-z0-9+/=]{4,}");
    // Bare Bearer <token> — when the header prefix is missing
    private static final Pattern BEARER_BARE = Pattern.compile("(?i)(\\bBearer\\s+)[A-Za-z0-9._\\-+/=]{8,}");

    // AWS access key id — fixed-shape ID, low false-positive risk.
    private static final Pattern AWS_ACCESS_KEY = Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b");
    // GitHub personal access token (classic + fine-grained) + GitHub OAuth/app tokens.
    private static final Pattern GITHUB_TOKEN =
            Pattern.compile("\\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{30,}\\b|\\bgithub_pat_[A-Za-z0-9_]{20,}\\b");
    // Slack tokens — xoxb-/xoxa-/xoxp-/xoxr-/xoxs- prefix.
    private static final Pattern SLACK_TOKEN = Pattern.compile("\\bxox[abpors]-[A-Za-z0-9-]{10,}\\b");

    // JWT-like: three base64url segments separated by dots; first segment starts with eyJ
    private static final Pattern JWT = Pattern.compile("\\beyJ[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+");

    // URI with userinfo containing a password — capture the whole URI for delegated redaction.
    // User portion may be empty (e.g. {@code redis://:hunter2@host}), so the pre-colon class allows zero chars.
    private static final Pattern URI_WITH_USERINFO =
            Pattern.compile("\\b([a-zA-Z][a-zA-Z0-9+.\\-]*://[^\\s/@:]*:[^\\s/@]+@[^\\s]+)");

    // IPv4 dotted-quad — each octet 0-255 with word boundaries
    private static final Pattern IPV4 = Pattern.compile(
            "\\b((?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\b");

    // IPv6 — full or compressed (must contain at least one "::" or 7 colons)
    // Conservative: require at least 2 hex groups separated by ":" and at least one "::" OR full 8-group form.
    private static final Pattern IPV6_FULL = Pattern.compile("\\b(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}\\b");
    private static final Pattern IPV6_COMPRESSED = Pattern.compile(
            "\\b(?:[0-9a-fA-F]{1,4}:){1,7}:(?:[0-9a-fA-F]{1,4})?\\b|::(?:[0-9a-fA-F]{1,4}:){0,6}[0-9a-fA-F]{1,4}\\b");

    // key=value secrets — bare form. Allows env-var-style prefixes/suffixes
    // (e.g. {@code AWS_SECRET_ACCESS_KEY}, {@code DB_PASSWORD}, {@code MY_API_TOKEN})
    // so we don't quietly miss them.
    private static final Pattern KV_SECRET = Pattern.compile(
            "(?i)\\b([A-Za-z0-9_-]*(?:password|passwd|secret|token|api[_-]?key)[A-Za-z0-9_-]*)\\s*=\\s*([^\\s,;&]+)");
    // JSON-style "key":"value" secrets — same broadened key shape.
    private static final Pattern JSON_SECRET = Pattern.compile(
            "(?i)\"([A-Za-z0-9_-]*(?:password|passwd|secret|token|api[_-]?key)[A-Za-z0-9_-]*)\"\\s*:\\s*\"([^\"]*)\"");

    private LogRedactor() {}

    /** Apply every redaction rule to a single line. */
    public static String redactLine(String line) {
        if (line == null || line.isEmpty()) return line;
        String result = line;
        result = AUTHORIZATION_HEADER.matcher(result).replaceAll("$1" + REDACTED);
        result = AUTHORIZATION_BASIC.matcher(result).replaceAll("$1" + REDACTED);
        result = BEARER_BARE.matcher(result).replaceAll("$1" + REDACTED);
        result = JWT.matcher(result).replaceAll(REDACTED);
        result = AWS_ACCESS_KEY.matcher(result).replaceAll(REDACTED);
        result = GITHUB_TOKEN.matcher(result).replaceAll(REDACTED);
        result = SLACK_TOKEN.matcher(result).replaceAll(REDACTED);
        result = redactUris(result);
        result = IPV4.matcher(result).replaceAll(REDACTED);
        result = IPV6_FULL.matcher(result).replaceAll(REDACTED);
        result = IPV6_COMPRESSED.matcher(result).replaceAll(REDACTED);
        result = KV_SECRET.matcher(result).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + "=" + REDACTED));
        result = JSON_SECRET
                .matcher(result)
                .replaceAll(m -> Matcher.quoteReplacement("\"" + m.group(1) + "\":\"" + REDACTED + "\""));
        return result;
    }

    /** Convenience wrapper applying {@link #redactLine(String)} to each line. */
    public static List<String> redactLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) return lines;
        return lines.stream().map(LogRedactor::redactLine).toList();
    }

    /** Redact every URI userinfo password embedded in a line. */
    private static String redactUris(String line) {
        Matcher m = URI_WITH_USERINFO.matcher(line);
        if (!m.find()) return line;
        StringBuilder out = new StringBuilder();
        int last = 0;
        do {
            out.append(line, last, m.start());
            out.append(ControllerConfigRedactor.redactUriUserinfo(m.group(1)));
            last = m.end();
        } while (m.find());
        out.append(line, last, line.length());
        return out.toString();
    }
}
