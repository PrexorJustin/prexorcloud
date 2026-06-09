package me.prexorjustin.prexorcloud.controller.share;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;

import me.prexorjustin.prexorcloud.controller.config.ShareConfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin client for the pste paste service. Uses the JDK {@link HttpClient} as
 * the mockable boundary — tests pass a stubbed client in via the
 * package-private constructor.
 *
 * <p>
 * Contract (see {@code paste-share-plan.md}):
 * <ul>
 *   <li>{@code POST {pasteUrl}/api/v1/paste} — raw text body.</li>
 *   <li>Headers: {@code Authorization: Bearer <token>} (when configured),
 *       {@code x-expiry} (preset key — {@code 1h}/{@code 1d}/{@code 30d}/{@code never}),
 *       {@code x-language}, {@code x-burn-after-read}, {@code x-idempotency-key}.</li>
 *   <li>{@code 201 Created} returns {@code { id, url, rawUrl, deleteToken, expiresAt, language, burnAfterRead, e2e }}.</li>
 *   <li>Errors: {@code 400 / 401 / 409 / 413 / 429 (Retry-After) / 451} → {@link PasteException}.</li>
 * </ul>
 */
public final class PasteClient {

    private static final Logger logger = LoggerFactory.getLogger(PasteClient.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    /** Single-retry ceiling honoring {@code Retry-After} on a 429. Anything larger is capped to keep operators waiting bounded. */
    static final Duration RETRY_AFTER_CEILING = Duration.ofSeconds(5);

    private final ShareConfig config;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public PasteClient(ShareConfig config) {
        this(
                config,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                new ObjectMapper());
    }

    PasteClient(ShareConfig config, HttpClient http, ObjectMapper mapper) {
        this.config = config;
        this.http = http;
        this.mapper = mapper;
    }

    /**
     * Upload {@code text} as a paste and return the resulting paste descriptor.
     *
     * @throws PasteException on any non-2xx response, timeout, network error, or malformed body.
     */
    public PasteResult create(String text, PasteOptions options) {
        HttpResponse<String> response = send(text, options);
        int status = response.statusCode();
        if (status == 201) return parseSuccess(response.body());
        // Single retry on 429, honoring Retry-After (bounded). Any other 4xx/5xx propagates straight to caller.
        if (status == 429) {
            Duration wait = parseRetryAfter(response).orElse(Duration.ofSeconds(1));
            if (wait.compareTo(RETRY_AFTER_CEILING) > 0) wait = RETRY_AFTER_CEILING;
            logger.info("pste returned 429; retrying once after {}", wait);
            try {
                Thread.sleep(wait.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new PasteException("Paste retry interrupted", interrupted);
            }
            HttpResponse<String> second = send(text, options);
            if (second.statusCode() == 201) return parseSuccess(second.body());
            throw mapErrorStatus(second.statusCode(), second);
        }
        throw mapErrorStatus(status, response);
    }

    /**
     * Issue a {@code DELETE} against the upstream pste service using the
     * delete token returned in the original 201. Returns {@code true} on a 2xx
     * (or 404 — already gone counts as success); throws {@link PasteException}
     * on transport/upstream errors.
     */
    public boolean delete(String deleteToken) {
        if (deleteToken == null || deleteToken.isBlank()) {
            throw new PasteException("Missing delete token");
        }
        URI endpoint = URI.create(stripTrailingSlash(config.pasteUrl()) + "/api/v1/paste/" + deleteToken);
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(endpoint).timeout(REQUEST_TIMEOUT).DELETE();
        if (config.pasteToken() != null && !config.pasteToken().isBlank()) {
            builder.header("Authorization", "Bearer " + config.pasteToken());
        }
        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.net.http.HttpTimeoutException timeout) {
            throw new PasteException("Paste delete timed out", timeout);
        } catch (java.io.IOException io) {
            throw new PasteException("Paste service unreachable: " + io.getMessage(), io);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new PasteException("Paste delete interrupted", interrupted);
        }
        int status = response.statusCode();
        if (status >= 200 && status < 300) return true;
        if (status == 404) return true;
        throw mapErrorStatus(status, response);
    }

    private HttpResponse<String> send(String text, PasteOptions options) {
        URI endpoint = URI.create(stripTrailingSlash(config.pasteUrl()) + "/api/v1/paste");
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "text/plain; charset=utf-8")
                .header("x-expiry", options.expiry())
                .header("x-language", options.language())
                .header("x-idempotency-key", options.idempotencyKey())
                .POST(HttpRequest.BodyPublishers.ofString(text, StandardCharsets.UTF_8));
        if (options.burnAfterRead()) builder.header("x-burn-after-read", "1");
        if (config.e2e()) builder.header("x-e2e", "1");
        if (config.pasteToken() != null && !config.pasteToken().isBlank()) {
            builder.header("Authorization", "Bearer " + config.pasteToken());
        }

        try {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.net.http.HttpTimeoutException timeout) {
            throw new PasteException("Paste service timed out", timeout);
        } catch (java.io.IOException io) {
            throw new PasteException("Paste service unreachable: " + io.getMessage(), io);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new PasteException("Paste upload interrupted", interrupted);
        }
    }

    private static java.util.Optional<Duration> parseRetryAfter(HttpResponse<String> response) {
        return response.headers().firstValue("retry-after").flatMap(value -> {
            try {
                long seconds = Long.parseLong(value.trim());
                if (seconds <= 0) return java.util.Optional.empty();
                return java.util.Optional.of(Duration.ofSeconds(seconds));
            } catch (NumberFormatException ignored) {
                return java.util.Optional.empty();
            }
        });
    }

    private PasteResult parseSuccess(String body) {
        try {
            JsonNode node = mapper.readTree(body);
            String id = node.path("id").asText(null);
            String url = node.path("url").asText(null);
            String rawUrl = node.path("rawUrl").asText(null);
            String deleteToken = node.path("deleteToken").asText(null);
            String language = node.path("language").asText("text");
            boolean burnAfterRead = node.path("burnAfterRead").asBoolean(false);
            Instant expiresAt = parseInstant(node.path("expiresAt").asText(null));
            if (url == null || url.isBlank()) {
                throw new PasteException("Paste service returned 201 without a url field");
            }
            return new PasteResult(id, url, rawUrl, deleteToken, expiresAt, language, burnAfterRead);
        } catch (PasteException e) {
            throw e;
        } catch (Exception e) {
            throw new PasteException("Failed to parse paste response: " + e.getMessage(), e);
        }
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private PasteException mapErrorStatus(int status, HttpResponse<String> response) {
        String retryAfter = response.headers().firstValue("retry-after").orElse(null);
        String message =
                switch (status) {
                    case 400 -> "Paste rejected (400 Bad Request) — invalid options";
                    case 401 -> "Paste unauthorized (401) — check share.pasteToken";
                    case 409 -> "Paste conflict (409) — idempotency key collision";
                    case 413 -> "Paste too large (413) — content exceeds upstream limit";
                    case 429 -> "Paste rate limited (429)" + (retryAfter == null ? "" : " — retry after " + retryAfter);
                    case 451 -> "Paste blocked (451) — content rejected by upstream policy";
                    default -> "Paste service returned HTTP " + status;
                };
        logger.warn(
                "pste upload failed: status={} retryAfter={} bodyChars={}",
                status,
                retryAfter,
                response.body() == null ? 0 : response.body().length());
        return new PasteException(message, status, retryAfter, null);
    }

    private static String stripTrailingSlash(String url) {
        if (url == null || url.isEmpty()) return url;
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
