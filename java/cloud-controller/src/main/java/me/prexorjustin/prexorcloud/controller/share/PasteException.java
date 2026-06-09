package me.prexorjustin.prexorcloud.controller.share;

/**
 * Thrown by {@link PasteClient} when the upstream paste service rejects the
 * request, returns a non-2xx response, or is unreachable. Mapped to HTTP
 * {@code 502 Bad Gateway} by the REST layer.
 */
public class PasteException extends RuntimeException {

    private final int upstreamStatus;
    private final String retryAfter;

    public PasteException(String message) {
        this(message, -1, null, null);
    }

    public PasteException(String message, Throwable cause) {
        this(message, -1, null, cause);
    }

    public PasteException(String message, int upstreamStatus, String retryAfter, Throwable cause) {
        super(message, cause);
        this.upstreamStatus = upstreamStatus;
        this.retryAfter = retryAfter;
    }

    /** Upstream HTTP status code, or {@code -1} if the request never completed. */
    public int upstreamStatus() {
        return upstreamStatus;
    }

    /** Value of the {@code Retry-After} header on a 429, or {@code null} if absent. */
    public String retryAfter() {
        return retryAfter;
    }
}
