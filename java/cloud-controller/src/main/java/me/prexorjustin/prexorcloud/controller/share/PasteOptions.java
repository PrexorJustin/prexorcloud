package me.prexorjustin.prexorcloud.controller.share;

/**
 * Per-call options for {@link PasteClient#create(String, PasteOptions)}.
 *
 * @param expiry        preset expiry key ({@code 1h}/{@code 1d}/{@code 30d}/{@code never}) — passed as {@code x-expiry}
 * @param language      syntax-highlight hint — passed as {@code x-language} (defaults to {@code text})
 * @param burnAfterRead destroy-on-read flag — passed as {@code x-burn-after-read} when true
 * @param idempotencyKey fresh UUID per share — passed as {@code x-idempotency-key} to prevent dup on retry
 */
public record PasteOptions(String expiry, String language, boolean burnAfterRead, String idempotencyKey) {
    public PasteOptions {
        if (expiry == null || expiry.isBlank()) expiry = "1d";
        if (language == null || language.isBlank()) language = "text";
    }
}
