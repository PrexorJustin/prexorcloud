package me.prexorjustin.prexorcloud.controller.rest.route;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CrashRoutes")
class CrashRoutesTest {

    @Test
    @DisplayName("uses page and pageSize when provided")
    void usesCanonicalPaginationParameters() {
        CrashRoutes.CrashPageRequest request = CrashRoutes.resolveCrashPageRequest(2, 25, 10, 500);

        assertEquals(2, request.page());
        assertEquals(25, request.pageSize());
        assertEquals(25, request.offset());
        assertEquals(25, request.limit());
    }

    @Test
    @DisplayName("falls back to legacy limit when page parameters are absent")
    void fallsBackToLegacyLimit() {
        CrashRoutes.CrashPageRequest request = CrashRoutes.resolveCrashPageRequest(null, null, 40, 500);

        assertEquals(1, request.page());
        assertEquals(40, request.pageSize());
        assertEquals(0, request.offset());
        assertEquals(40, request.limit());
    }

    @Test
    @DisplayName("parseWindow accepts duration suffixes")
    void parseWindowAcceptsSuffixes() {
        Duration fallback = Duration.ofHours(24);
        assertEquals(Duration.ofHours(6), CrashRoutes.parseWindow("6h", fallback));
        assertEquals(Duration.ofMinutes(30), CrashRoutes.parseWindow("30m", fallback));
        assertEquals(Duration.ofDays(7), CrashRoutes.parseWindow("7d", fallback));
        assertEquals(Duration.ofSeconds(45), CrashRoutes.parseWindow("45s", fallback));
    }

    @Test
    @DisplayName("parseWindow falls back on garbage input and clamps to 30d")
    void parseWindowFallbackAndClamp() {
        Duration fallback = Duration.ofHours(24);
        assertEquals(fallback, CrashRoutes.parseWindow(null, fallback));
        assertEquals(fallback, CrashRoutes.parseWindow("", fallback));
        assertEquals(fallback, CrashRoutes.parseWindow("forever", fallback));
        assertEquals(Duration.ofDays(30), CrashRoutes.parseWindow("365d", fallback));
    }
}
