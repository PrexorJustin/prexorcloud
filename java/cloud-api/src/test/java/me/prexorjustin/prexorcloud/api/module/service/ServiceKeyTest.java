package me.prexorjustin.prexorcloud.api.module.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ServiceKey")
class ServiceKeyTest {

    interface Foo {}

    @Test
    @DisplayName("of() builds an unqualified key")
    void unqualified() {
        var key = ServiceKey.of(Foo.class);
        assertEquals(Foo.class, key.type());
        assertTrue(key.qualifier().isEmpty());
        assertEquals(Foo.class.getName(), key.asString());
    }

    @Test
    @DisplayName("named() includes the qualifier in asString()")
    void qualified() {
        var key = ServiceKey.named(Foo.class, "primary");
        assertEquals("primary", key.qualifier().orElseThrow());
        assertEquals(Foo.class.getName() + "#primary", key.asString());
    }

    @Test
    @DisplayName("equality considers type and qualifier")
    void equality() {
        var a = ServiceKey.of(Foo.class);
        var b = ServiceKey.of(Foo.class);
        var c = ServiceKey.named(Foo.class, "alt");
        var d = ServiceKey.named(Foo.class, "alt");

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals(c, d);
        assertNotEquals(a, c);
    }

    @Test
    @DisplayName("named() rejects null or blank qualifiers")
    void rejectsBadQualifier() {
        assertThrows(NullPointerException.class, () -> ServiceKey.named(Foo.class, null));
        assertThrows(IllegalArgumentException.class, () -> ServiceKey.named(Foo.class, " "));
    }
}
