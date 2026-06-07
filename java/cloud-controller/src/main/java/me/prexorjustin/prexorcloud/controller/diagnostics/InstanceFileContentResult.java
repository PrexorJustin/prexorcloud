package me.prexorjustin.prexorcloud.controller.diagnostics;

/**
 * Controller-side view of a {@link me.prexorjustin.prexorcloud.protocol.InstanceFileContent}
 * reply. {@code content} is the decoded UTF-8 string (best-effort — the daemon
 * is told the file is text by convention); {@code totalSizeBytes} is the full
 * on-disk size so the UI can render "showing X of Y bytes".
 */
public record InstanceFileContentResult(String content, long totalSizeBytes, boolean truncated, String error) {

    public static InstanceFileContentResult unavailable(String error) {
        return new InstanceFileContentResult("", 0L, false, error);
    }
}
