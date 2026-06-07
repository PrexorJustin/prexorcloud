package me.prexorjustin.prexorcloud.protocol.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class ProtoContractDriftTest {

    @Test
    void protoSnapshotMatchesCommittedContract() throws Exception {
        Path repoRoot = Path.of(System.getProperty("prexor.repo.root"));
        Path protoDir = repoRoot.resolve("java/cloud-protocol/src/main/proto/prexorcloud");
        Path snapshot = repoRoot.resolve("java/cloud-protocol/contracts/proto-contracts.sha256");

        String actual = Files.list(protoDir)
                .filter(path -> path.getFileName().toString().endsWith(".proto"))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .map(path -> path.getFileName() + " " + sha256(path))
                .collect(Collectors.joining("\n", "", "\n"));

        String expected = Files.readString(snapshot, StandardCharsets.UTF_8);
        assertEquals(
                expected,
                actual,
                "Proto contract snapshot drifted. Update java/cloud-protocol/contracts/proto-contracts.sha256 if the change is intentional.");
    }

    private static String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash proto file " + path, e);
        }
    }
}
