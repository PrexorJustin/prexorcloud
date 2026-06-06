package me.prexorjustin.prexorcloud.controller.state;

import java.time.Instant;
import java.util.UUID;

public record PlayerInfo(
        UUID uuid,
        String name,
        String instanceId,
        String group,
        String proxyInstanceId,
        Instant connectedAt,
        String edition) {

    /**
     * Derives {@link #edition} from the UUID when it isn't supplied — covers both the registry
     * (which passes it explicitly) and Jackson rehydration of players persisted before the field
     * existed (missing → {@code null} → derived). See {@link PlayerEdition}.
     */
    public PlayerInfo {
        if (edition == null || edition.isBlank()) {
            edition = PlayerEdition.detect(uuid);
        }
    }
}
