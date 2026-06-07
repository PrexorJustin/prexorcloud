package me.prexorjustin.prexorcloud.daemon.module;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Drift-detector for {@link DaemonModuleManager}'s parent-classloader prefix
 * list. Why: modules see ONLY these prefixes through the parent classloader,
 * so adding a new public-API package without registering it here silently
 * breaks module loading. Bump this test deliberately when the set changes.
 */
@DisplayName("DaemonModuleManager parent prefixes")
class DaemonModuleParentPrefixesTest {

    private static final Set<String> EXPECTED =
            Set.of("java.", "javax.", "jdk.", "sun.", "org.slf4j.", "me.prexorjustin.prexorcloud.api.");

    @Test
    @DisplayName("PARENT_PREFIXES matches the expected drift baseline")
    @SuppressWarnings("unchecked")
    void parentPrefixesMatchExpectedBaseline() throws Exception {
        Field field = DaemonModuleManager.class.getDeclaredField("PARENT_PREFIXES");
        field.setAccessible(true);
        Set<String> actual = (Set<String>) field.get(null);

        assertEquals(
                EXPECTED,
                Set.copyOf(actual),
                "PARENT_PREFIXES drift detected. If this is intentional (e.g. a new "
                        + "cloud-api package was added), update EXPECTED in this test. "
                        + "Otherwise revert the DaemonModuleManager change — modules will "
                        + "lose visibility of types not covered by the parent prefix list.");
    }
}
