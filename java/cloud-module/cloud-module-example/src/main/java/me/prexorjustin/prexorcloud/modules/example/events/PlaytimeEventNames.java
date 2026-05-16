package me.prexorjustin.prexorcloud.modules.example.events;

/**
 * Event type constants for the example-playtime module.
 *
 * <p>STEP 5 — Cloud events use the {@code "MODULE:ACTION"} string convention
 * (see {@link me.prexorjustin.prexorcloud.api.event.CustomCloudEvent}). Keeping
 * them in one file makes it trivial to rename them globally.
 */
public final class PlaytimeEventNames {

    public static final String SESSION_START = "PLAYTIME:SESSION_START";
    public static final String SESSION_END = "PLAYTIME:SESSION_END";
    public static final String TOP_UPDATED = "PLAYTIME:TOP_UPDATED";

    private PlaytimeEventNames() {}
}
