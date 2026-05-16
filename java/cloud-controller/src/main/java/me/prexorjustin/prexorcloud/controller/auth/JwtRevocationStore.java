package me.prexorjustin.prexorcloud.controller.auth;

import java.time.Duration;

public interface JwtRevocationStore {

    void revoke(String jti, Duration ttl);

    boolean isRevoked(String jti);
}
