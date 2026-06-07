package me.prexorjustin.prexorcloud.controller.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LogRedactorTest {

    @Test
    void redactsAuthorizationBearerHeader() {
        String line = "GET /api HTTP/1.1 Authorization: Bearer abc123.def456.ghi789";
        String out = LogRedactor.redactLine(line);
        assertFalse(out.contains("abc123.def456.ghi789"), out);
        assertTrue(out.contains("Authorization: Bearer " + LogRedactor.REDACTED), out);
    }

    @Test
    void redactsBareBearerToken() {
        String out = LogRedactor.redactLine("token: Bearer ZmFrZS10b2tlbg==");
        assertFalse(out.contains("ZmFrZS10b2tlbg=="));
        assertTrue(out.contains(LogRedactor.REDACTED));
    }

    @Test
    void redactsJwtLikeToken() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyMSJ9.signaturepart";
        String out = LogRedactor.redactLine("auth failed for " + jwt);
        assertFalse(out.contains(jwt), out);
        assertTrue(out.contains(LogRedactor.REDACTED), out);
    }

    @Test
    void redactsUriUserinfoPassword() {
        String out = LogRedactor.redactLine("connecting to mongodb://admin:hunter2@mongo:27017/db");
        assertFalse(out.contains("hunter2"), out);
        assertTrue(out.contains("admin:" + LogRedactor.REDACTED), out);
    }

    @Test
    void redactsIpv4() {
        String out = LogRedactor.redactLine("daemon connected from 10.0.4.7 sessionId=abc");
        assertFalse(out.contains("10.0.4.7"), out);
        assertTrue(out.contains(LogRedactor.REDACTED), out);
    }

    @Test
    void redactsIpv6Full() {
        String out = LogRedactor.redactLine("peer=2001:0db8:85a3:0000:0000:8a2e:0370:7334");
        assertFalse(out.contains("2001:0db8:85a3:0000:0000:8a2e:0370:7334"), out);
        assertTrue(out.contains(LogRedactor.REDACTED), out);
    }

    @Test
    void redactsBareKeyValueSecrets() {
        String out = LogRedactor.redactLine("password=swordfish secret=42 token=zz api_key=APIKEY");
        assertFalse(out.contains("swordfish"));
        assertFalse(out.contains("APIKEY"));
        // values gone, keys stay
        assertTrue(out.contains("password="));
        assertTrue(out.contains("api_key="));
    }

    @Test
    void redactsJsonStyleSecrets() {
        String out = LogRedactor.redactLine("{\"password\":\"hunter2\",\"user\":\"alice\"}");
        assertFalse(out.contains("hunter2"));
        assertTrue(out.contains("\"user\":\"alice\""), out);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "2026-05-15T08:42:17.123Z INFO  scheduler started",
                "java.lang.NullPointerException: oops",
                "at me.prexorjustin.cloud.Foo.bar(Foo.java:42)",
                "/var/lib/prexorcloud/templates/lobby/server.jar",
                "instance lobby-1 transitioned RUNNING → STOPPING",
                "node-id node-7 cpu=0.84"
            })
    void leavesNonSecretLinesUnchanged(String line) {
        assertEquals(line, LogRedactor.redactLine(line));
    }

    @Test
    void idempotent() {
        String line = "Authorization: Bearer secret-token mongodb://u:p@h:1/d 10.1.2.3 password=topsecret";
        String once = LogRedactor.redactLine(line);
        String twice = LogRedactor.redactLine(once);
        assertEquals(once, twice);
    }

    @Test
    void redactsRealCrashLogTailFixture() {
        var lines = List.of(
                "2026-05-15T08:42:17.123Z ERROR  Authorization: Bearer THIS_LEAKS_BAD",
                "Caused by: java.sql.SQLException: connection failed to mongodb://admin:hunter2@mongo:27017",
                "  remote=10.0.4.99 password=topsecret apikey=ABCD1234",
                "  jwt=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyMSJ9.signpart");
        var redacted = LogRedactor.redactLines(lines);
        String joined = String.join("\n", redacted);
        for (String secret : List.of("THIS_LEAKS_BAD", "hunter2", "10.0.4.99", "topsecret", "ABCD1234", "signpart")) {
            assertFalse(joined.contains(secret), "secret leaked: " + secret + " — line:\n" + joined);
        }
    }

    // === Adversarial fixtures: things we want to make sure DON'T survive a share === //

    @Test
    void redactsAuthorizationBasicBase64() {
        // base64("admin:hunter2") = "YWRtaW46aHVudGVyMg=="
        String line = "GET /api HTTP/1.1 Authorization: Basic YWRtaW46aHVudGVyMg==";
        String out = LogRedactor.redactLine(line);
        assertFalse(out.contains("YWRtaW46aHVudGVyMg=="), out);
        assertTrue(out.contains("Authorization: Basic " + LogRedactor.REDACTED), out);
    }

    @Test
    void redactsJdbcUrlWithPasswordInQueryString() {
        String out = LogRedactor.redactLine(
                "jdbc:postgresql://prod-db.internal:5432/payments?user=svc_payments&password=Tr0ub4dor&3&sslmode=require");
        assertFalse(out.contains("Tr0ub4dor"), out);
        assertTrue(out.contains("password=" + LogRedactor.REDACTED), out);
    }

    @Test
    void redactsDotNetStyleConnectionString() {
        String out = LogRedactor.redactLine(
                "Server=db.internal;Database=app;User Id=svc;Password=Pa$$w0rd!;Trusted_Connection=False;");
        assertFalse(out.contains("Pa$$w0rd"), out);
    }

    @Test
    void redactsAwsAccessKeyId() {
        String out = LogRedactor.redactLine("env AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE booting");
        assertFalse(out.contains("AKIAIOSFODNN7EXAMPLE"), out);
    }

    @Test
    void redactsAwsSecretAccessKeyEnvVar() {
        String out =
                LogRedactor.redactLine("env AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY booting");
        assertFalse(out.contains("wJalrXUtnFEMI"), out);
    }

    @Test
    void redactsGithubPersonalAccessToken() {
        String out = LogRedactor.redactLine("hook payload token=ghp_1234567890abcdefghijklmnopqrstuvwxAB");
        assertFalse(out.contains("ghp_1234567890abcdefghijklmnopqrstuvwxAB"), out);
    }

    @Test
    void redactsGithubFineGrainedPat() {
        String out = LogRedactor.redactLine("auth header github_pat_11ABCDEF0_someopaquedata12345678901234567890");
        assertFalse(out.contains("github_pat_11ABCDEF0_someopaquedata12345678901234567890"), out);
    }

    @Test
    void redactsSlackBotToken() {
        String out = LogRedactor.redactLine("slack notification using xoxb-1234567890-XYZ-1234567890abcdef");
        assertFalse(out.contains("xoxb-1234567890"), out);
    }

    @Test
    void redactsEnvVarStyleDbPassword() {
        String out = LogRedactor.redactLine("DB_PASSWORD=hunter2 starting service");
        assertFalse(out.contains("hunter2"), out);
        assertTrue(out.contains("DB_PASSWORD=" + LogRedactor.REDACTED), out);
    }

    @Test
    void redactsK8sServiceAccountTokenJsonValue() {
        // k8s SA tokens are JWTs — covered by JWT pattern; also test JSON envelope.
        String out = LogRedactor.redactLine(
                "{\"service_account_token\":\"eyJhbGciOiJSUzI1NiJ9.eyJpc3MiOiJrdWJlcm5ldGVzIn0.signed-part\"}");
        assertFalse(out.contains("signed-part"), out);
    }

    @Test
    void redactsRedisUriWithPassword() {
        String out = LogRedactor.redactLine("connecting redis://:hunter2@redis-master.internal:6379/0");
        assertFalse(out.contains("hunter2"), out);
    }

    @Test
    void redactsAnonymizedIncidentLogFixture() {
        // Composite — single-line log entries that would have leaked in a real incident.
        var lines = List.of(
                "2026-05-15T08:42:17.123Z INFO  StartingApp env=prod",
                "  config: jdbc:postgresql://db-prod-eu.internal:5432/app?user=svc&password=Tr0ub4dor&3",
                "  Slack: token=xoxb-99-XYZ-abcdef0123456789",
                "  AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                "  Authorization: Basic YWxpY2U6c3VwZXJzZWNyZXQ=",
                "  GitHub: ghp_abcdefghijklmnopqrstuvwxyz0123456789",
                "  remote=10.5.6.7 jwt=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyMSJ9.sigpart");
        var joined = String.join("\n", LogRedactor.redactLines(lines));
        for (String secret : List.of(
                "Tr0ub4dor",
                "xoxb-99-XYZ-abcdef0123456789",
                "wJalrXUtnFEMI",
                "YWxpY2U6c3VwZXJzZWNyZXQ=",
                "ghp_abcdefghijklmnopqrstuvwxyz0123456789",
                "10.5.6.7",
                "sigpart")) {
            assertFalse(joined.contains(secret), "leaked secret: " + secret + "\n" + joined);
        }
    }
}
