package me.prexorjustin.prexorcloud.api.module;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("SemverRange")
class SemverRangeTest {

    @Nested
    @DisplayName("Version parsing")
    class VersionParsing {

        @Test
        @DisplayName("parses three-part version")
        void threePart() {
            Version v = Version.parse("1.20.4");
            assertEquals(1, v.major());
            assertEquals(20, v.minor());
            assertEquals(4, v.patch());
            assertNull(v.preRelease());
        }

        @Test
        @DisplayName("parses two-part version as X.Y.0")
        void twoPart() {
            Version v = Version.parse("1.20");
            assertEquals(1, v.major());
            assertEquals(20, v.minor());
            assertEquals(0, v.patch());
        }

        @Test
        @DisplayName("parses one-part version as X.0.0")
        void onePart() {
            Version v = Version.parse("1");
            assertEquals(1, v.major());
            assertEquals(0, v.minor());
            assertEquals(0, v.patch());
        }

        @Test
        @DisplayName("captures pre-release suffix")
        void preRelease() {
            Version v = Version.parse("1.20.4-pre1");
            assertEquals("pre1", v.preRelease());
        }

        @Test
        @DisplayName("drops build metadata")
        void buildMetadata() {
            Version v = Version.parse("1.20.4+build.42");
            assertEquals(4, v.patch());
            assertNull(v.preRelease());
        }

        @Test
        @DisplayName("release beats pre-release in ordering")
        void ordering() {
            Version release = Version.parse("1.20.4");
            Version pre = Version.parse("1.20.4-rc1");
            assertTrue(release.compareTo(pre) > 0);
        }

        @Test
        @DisplayName("rejects non-numeric parts")
        void rejectsNonNumeric() {
            assertThrows(IllegalArgumentException.class, () -> Version.parse("1.x.0"));
        }

        @Test
        @DisplayName("rejects four-part version")
        void rejectsFourParts() {
            assertThrows(IllegalArgumentException.class, () -> Version.parse("1.2.3.4"));
        }
    }

    @Nested
    @DisplayName("range parsing + matching")
    class Ranges {

        @Test
        @DisplayName("'*' matches everything")
        void wildcard() {
            SemverRange r = SemverRange.parse("*");
            assertTrue(r.isAny());
            assertTrue(r.contains(Version.parse("0.0.1")));
            assertTrue(r.contains(Version.parse("99.99.99")));
        }

        @Test
        @DisplayName("exact version only matches itself")
        void exact() {
            SemverRange r = SemverRange.parse("1.20.4");
            assertTrue(r.contains(Version.parse("1.20.4")));
            assertFalse(r.contains(Version.parse("1.20.5")));
            assertFalse(r.contains(Version.parse("1.20.3")));
        }

        @Test
        @DisplayName("'>=1.20 <1.22' matches 1.20.x and 1.21.x")
        void compoundRange() {
            SemverRange r = SemverRange.parse(">=1.20 <1.22");
            assertTrue(r.contains(Version.parse("1.20.0")));
            assertTrue(r.contains(Version.parse("1.20.4")));
            assertTrue(r.contains(Version.parse("1.21.0")));
            assertFalse(r.contains(Version.parse("1.22.0")));
            assertFalse(r.contains(Version.parse("1.19.4")));
        }

        @Test
        @DisplayName("caret expands to next major")
        void caret() {
            SemverRange r = SemverRange.parse("^1.2.3");
            assertTrue(r.contains(Version.parse("1.2.3")));
            assertTrue(r.contains(Version.parse("1.9.9")));
            assertFalse(r.contains(Version.parse("2.0.0")));
            assertFalse(r.contains(Version.parse("1.2.2")));
        }

        @Test
        @DisplayName("tilde expands to next minor")
        void tilde() {
            SemverRange r = SemverRange.parse("~1.2.3");
            assertTrue(r.contains(Version.parse("1.2.3")));
            assertTrue(r.contains(Version.parse("1.2.99")));
            assertFalse(r.contains(Version.parse("1.3.0")));
        }

        @Test
        @DisplayName("interval syntax supports inclusive/exclusive bounds")
        void intervalSyntax() {
            SemverRange r = SemverRange.parse("[1.20,1.21)");
            assertTrue(r.contains(Version.parse("1.20")));
            assertTrue(r.contains(Version.parse("1.20.9")));
            assertFalse(r.contains(Version.parse("1.19.9")));
            assertFalse(r.contains(Version.parse("1.21")));
            assertFalse(r.isExact());
            assertEquals(Version.parse("1.20"), r.lowerBound().orElseThrow().value());
            assertTrue(r.lowerBound().orElseThrow().inclusive());
            assertEquals(Version.parse("1.21"), r.upperBound().orElseThrow().value());
            assertFalse(r.upperBound().orElseThrow().inclusive());
        }

        @Test
        @DisplayName("open interval bounds behave like one-sided ranges")
        void openIntervalBounds() {
            SemverRange r = SemverRange.parse("[1.20,)");
            assertTrue(r.contains(Version.parse("1.20")));
            assertTrue(r.contains(Version.parse("1.99")));
            assertFalse(r.contains(Version.parse("1.19.9")));
            assertTrue(r.lowerBound().isPresent());
            assertTrue(r.upperBound().isEmpty());
        }

        @Test
        @DisplayName("bare version is treated as exact range")
        void exactRange() {
            SemverRange r = SemverRange.parse("1.20");
            assertTrue(r.isExact());
            assertEquals(Version.parse("1.20"), r.lowerBound().orElseThrow().value());
            assertEquals(Version.parse("1.20"), r.upperBound().orElseThrow().value());
        }

        @Test
        @DisplayName("rejects garbage range")
        void rejectsGarbage() {
            assertThrows(IllegalArgumentException.class, () -> SemverRange.parse(">>>1.0.0"));
            assertThrows(IllegalArgumentException.class, () -> SemverRange.parse("^not-a-version"));
            assertThrows(IllegalArgumentException.class, () -> SemverRange.parse("[1.20 1.21)"));
        }
    }
}
