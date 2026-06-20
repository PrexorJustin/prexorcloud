package me.prexorjustin.prexorcloud.controller.cluster;

import java.time.Instant;

/**
 * A freshly minted cluster join token: the opaque wire {@code token} handed to the operator, its
 * {@code jti} (globally unique id, also the {@code cluster_join_tokens} key), and the {@code expiresAt}.
 * Store-neutral so both the Raft- and Mongo-backed {@link ClusterPlane} return the same type.
 */
public record IssuedJoinToken(String token, String jti, Instant expiresAt) {}
