package me.prexorjustin.prexorcloud.controller.rest.route;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AuditRoutes")
class AuditRoutesTest {

    @Test
    @DisplayName("prefers page and pageSize over legacy offset and limit")
    void prefersExplicitPageParameters() {
        AuditRoutes.AuditPageRequest request = AuditRoutes.resolveAuditPageRequest(3, 50, 5, 10, 500);

        assertEquals(3, request.page());
        assertEquals(50, request.pageSize());
        assertEquals(100, request.offset());
        assertEquals(50, request.limit());
    }

    @Test
    @DisplayName("derives page metadata from legacy offset and limit when page parameters are absent")
    void derivesPageFromLegacyParameters() {
        AuditRoutes.AuditPageRequest request = AuditRoutes.resolveAuditPageRequest(null, null, 40, 20, 500);

        assertEquals(3, request.page());
        assertEquals(20, request.pageSize());
        assertEquals(40, request.offset());
        assertEquals(20, request.limit());
    }
}
