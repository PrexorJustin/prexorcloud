package me.prexorjustin.prexorcloud.controller.share;

/**
 * Operator-supplied overrides for a single share invocation. Any {@code null}
 * field falls back to the corresponding {@code share.default*} from
 * {@link me.prexorjustin.prexorcloud.controller.config.ShareConfig}.
 *
 * @param expiry        optional pste expiry preset ({@code 1h}/{@code 1d}/{@code 30d}/{@code never})
 * @param visibility    optional {@code private}/{@code public} visibility marker (stored, not sent to pste)
 * @param burnAfterRead optional burn-after-read override
 */
public record ShareRequest(String expiry, Boolean visibility, Boolean burnAfterRead) {

    public static ShareRequest empty() {
        return new ShareRequest(null, null, null);
    }
}
