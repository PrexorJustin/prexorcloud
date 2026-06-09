FROM eclipse-temurin:25-jdk AS builder

WORKDIR /build
COPY . .
RUN ./gradlew :cloud-daemon:shadowJar -x test --no-daemon

# Create minimal JRE
RUN jlink \
    --module-path /opt/java/openjdk/jmods \
    --add-modules java.base,java.management,java.naming,java.logging,jdk.unsupported,jdk.crypto.ec \
    --output /jre \
    --strip-debug \
    --no-man-pages \
    --no-header-files \
    --compress zip-6

FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates && rm -rf /var/lib/apt/lists/*

COPY --from=builder /jre /opt/java
COPY --from=builder /build/cloud-daemon/build/libs/PrexorCloudDaemon.jar /app/PrexorCloudDaemon.jar

WORKDIR /app
EXPOSE 9090

ENTRYPOINT ["/opt/java/bin/java", "--enable-preview", "--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow", "-Dio.netty.noUnsafe=true", "-jar", "PrexorCloudDaemon.jar"]
