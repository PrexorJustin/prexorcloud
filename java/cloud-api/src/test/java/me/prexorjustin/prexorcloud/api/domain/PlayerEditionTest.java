package me.prexorjustin.prexorcloud.api.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PlayerEdition")
class PlayerEditionTest {

    @Test
    @DisplayName("a Floodgate-shaped UUID (high bits zero) is Bedrock")
    void floodgateUuidIsBedrock() {
        assertEquals(PlayerEdition.BEDROCK, PlayerEdition.detect(new UUID(0L, 9876543210L)));
    }

    @Test
    @DisplayName("an ordinary Java UUID is Java")
    void javaUuidIsJava() {
        assertEquals(PlayerEdition.JAVA, PlayerEdition.detect(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")));
    }

    @Test
    @DisplayName("null is treated as Java (defensive)")
    void nullIsJava() {
        assertEquals(PlayerEdition.JAVA, PlayerEdition.detect(null));
    }
}
