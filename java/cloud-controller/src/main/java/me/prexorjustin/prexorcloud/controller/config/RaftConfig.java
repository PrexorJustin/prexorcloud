package me.prexorjustin.prexorcloud.controller.config;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Node-local configuration for the Raft transport that backs the cluster control
 * plane. Cluster-wide tuning (snapshot retention, election timeout) lives in the
 * Raft state machine itself, not here — this struct only describes how THIS node
 * binds and finds its peers. See docs/engineering/cluster-join-plan.md.
 *
 * <p>{@code joinAddrs} are the gRPC endpoints of existing cluster members used at
 * boot to discover the cluster. Empty list means "I'm either the first controller
 * of a new cluster or I'm restarting an existing member" — the bootstrap reads
 * the on-disk Raft data dir to disambiguate.
 */
public record RaftConfig(
        @JsonProperty("host") String host,
        @JsonProperty("port") int port,
        @JsonProperty("dataDir") String dataDir,
        @JsonProperty("joinAddrs") List<String> joinAddrs) {

    public RaftConfig {
        if (host == null || host.isBlank()) host = "0.0.0.0";
        if (port <= 0) port = 9190;
        if (dataDir == null || dataDir.isBlank()) dataDir = "data/raft";
        if (joinAddrs == null) joinAddrs = List.of();
        else joinAddrs = List.copyOf(joinAddrs);
    }

    public RaftConfig() {
        this("0.0.0.0", 9190, "data/raft", List.of());
    }
}
