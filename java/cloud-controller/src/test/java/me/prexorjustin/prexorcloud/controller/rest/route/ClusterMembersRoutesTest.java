package me.prexorjustin.prexorcloud.controller.rest.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import me.prexorjustin.prexorcloud.controller.cluster.state.Member;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ClusterMembersRoutes.decideLeavability")
class ClusterMembersRoutesTest {

    private static Member member(String nodeId) {
        return new Member(nodeId, "127.0.0.1:9190", "127.0.0.1:8443", "127.0.0.1:9090", nodeId,
                Instant.parse("2026-05-31T10:00:00Z"), Instant.parse("2026-05-31T10:00:00Z"));
    }

    @Test
    @DisplayName("refuses when the cluster has a single member (sole controller cannot graceful-leave)")
    void refusesLastMember() {
        var decision = ClusterMembersRoutes.decideLeavability(List.of(member("self")), "self");
        assertFalse(decision.ok());
        assertEquals("LAST_MEMBER", decision.refusalCode());
    }

    @Test
    @DisplayName("refuses when self is not in the member list (already removed by a prior leave)")
    void refusesWhenSelfNotMember() {
        var decision = ClusterMembersRoutes.decideLeavability(List.of(member("a"), member("b")), "self");
        assertFalse(decision.ok());
        assertEquals("NOT_A_MEMBER", decision.refusalCode());
    }

    @Test
    @DisplayName("allows leave when self is one of multiple members")
    void allowsLeaveWhenSelfIsMember() {
        var decision = ClusterMembersRoutes.decideLeavability(List.of(member("a"), member("self")), "self");
        assertTrue(decision.ok());
        assertNull(decision.refusalCode());
    }
}
