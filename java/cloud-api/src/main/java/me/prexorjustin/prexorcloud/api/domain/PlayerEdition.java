package me.prexorjustin.prexorcloud.api.domain;

import java.util.UUID;

/**
 * Classifies a connected player as Java- or Bedrock-edition (northstar-plan Track F.1 — first-class
 * Bedrock support).
 *
 * <p>Detection is derived from the player UUID, so it needs no extra transport from the plugins:
 * Floodgate (the standard Geyser auth companion) assigns every Bedrock player a Java UUID of the
 * form {@code new UUID(0, xuid)} — i.e. the high 64 bits are zero. Checking
 * {@link UUID#getMostSignificantBits()} {@code == 0} is the canonical programmatic Bedrock test
 * (equivalent to Floodgate's own {@code isFloodgatePlayer}).
 *
 * <p>Lives in {@code cloud-api} so both the controller (player visibility) and the proxy plugins
 * ({@code NetworkRouter} edition-aware routing) share one detector and the same edition constants.
 *
 * <p><b>Scope/limitation (honest):</b> this recognises Bedrock players that authenticate through
 * Floodgate in its default (non-prefixed) UUID mode — the recommended setup. A standalone Geyser
 * proxy <em>without</em> Floodgate hands out ordinary random Java UUIDs and is therefore
 * indistinguishable here; such players are reported as {@link #JAVA}.
 */
public final class PlayerEdition {

    public static final String JAVA = "java";
    public static final String BEDROCK = "bedrock";

    private PlayerEdition() {}

    /** {@link #BEDROCK} for a Floodgate-shaped UUID (high bits zero), else {@link #JAVA}. */
    public static String detect(UUID uuid) {
        return uuid != null && uuid.getMostSignificantBits() == 0L ? BEDROCK : JAVA;
    }
}
