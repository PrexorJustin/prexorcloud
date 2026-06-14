package me.prexorjustin.prexorcloud.controller.config;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GrpcConfig(
        @JsonProperty("host") String host,
        @JsonProperty("port") int port,
        @JsonProperty("subjectAltNames") List<String> subjectAltNames) {

    public GrpcConfig {
        if (host == null) host = "0.0.0.0";
        if (port <= 0) port = 9090;
        // Extra SANs (hostnames/IPs daemons and proxies use to reach this controller) baked into the
        // gRPC server certificate. localhost/127.0.0.1 are always included; remote nodes need the
        // controller's real address here or mTLS hostname verification fails. Required when the
        // controller runs in Docker, where it cannot auto-detect the host's reachable address.
        if (subjectAltNames == null) subjectAltNames = List.of();
    }

    public GrpcConfig() {
        this("0.0.0.0", 9090, List.of());
    }

    public GrpcConfig(String host, int port) {
        this(host, port, List.of());
    }
}
