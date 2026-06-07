package me.prexorjustin.prexorcloud.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * JUnit Platform TestExecutionListener that collects results across all suites
 * and produces an ASCII console summary + JSON report at the end.
 *
 * <p>Registered via META-INF/services/org.junit.platform.launcher.TestExecutionListener.</p>
 */
public final class TestReportListener implements TestExecutionListener {

    private Instant runStart;
    private final Map<String, Long> testStartTimes = new ConcurrentHashMap<>();
    private final Map<String, SuiteData> suites = new ConcurrentHashMap<>();
    private final List<TestResult> allResults = Collections.synchronizedList(new ArrayList<>());

    record TestResult(String suite, String testName, String status, long durationMs, String failureMessage) {}

    static class SuiteData {
        final String name;
        final String category;
        final List<TestResult> tests = Collections.synchronizedList(new ArrayList<>());
        Instant startedAt;
        Instant finishedAt;

        SuiteData(String name, String category) {
            this.name = name;
            this.category = category;
        }

        long passed() {
            return tests.stream().filter(t -> "PASSED".equals(t.status)).count();
        }

        long failed() {
            return tests.stream().filter(t -> "FAILED".equals(t.status)).count();
        }

        long skipped() {
            return tests.stream().filter(t -> "SKIPPED".equals(t.status)).count();
        }

        long durationMs() {
            return startedAt != null && finishedAt != null
                    ? Duration.between(startedAt, finishedAt).toMillis()
                    : 0;
        }
    }

    private static String categorize(String suiteName) {
        return switch (suiteName) {
            case "AuthTest", "UserTest", "RoleTest", "PermissionTest", "SecurityTest" -> "Auth & Security";
            case "GroupTest", "DeploymentTest" -> "Groups & Deployments";
            case "TemplateTest" -> "Templates";
            case "InstanceTest", "NodeTest" -> "Instances & Nodes";
            case "PlayerTest", "PlayerSimulationTest" -> "Players";
            case "ProxyApiTest", "PluginApiTest" -> "Proxy & Plugin API";
            case "TokenTest", "CatalogTest", "AuditTest" -> "Admin";
            case "SystemTest" -> "System";
            case "SseEventTest" -> "SSE Events";
            case "StressTest", "FaultInjectionTest" -> "Stress & Fault Injection";
            default -> "Other";
        };
    }

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        runStart = Instant.now();
    }

    @Override
    public void executionStarted(TestIdentifier id) {
        if (id.isTest()) {
            testStartTimes.put(id.getUniqueId(), System.currentTimeMillis());

            String suiteName = extractSuiteName(id);
            suites.computeIfAbsent(suiteName, k -> {
                var sd = new SuiteData(k, categorize(k));
                sd.startedAt = Instant.now();
                return sd;
            });
        }
    }

    @Override
    public void executionFinished(TestIdentifier id, TestExecutionResult result) {
        if (id.isTest()) {
            long durationMs = 0;
            Long start = testStartTimes.remove(id.getUniqueId());
            if (start != null) durationMs = System.currentTimeMillis() - start;

            String status =
                    switch (result.getStatus()) {
                        case SUCCESSFUL -> "PASSED";
                        case FAILED -> "FAILED";
                        case ABORTED -> "SKIPPED";
                    };

            String failureMsg = result.getThrowable().map(Throwable::getMessage).orElse(null);

            String suiteName = extractSuiteName(id);
            String testName = id.getDisplayName();

            var tr = new TestResult(suiteName, testName, status, durationMs, failureMsg);
            allResults.add(tr);

            var suite = suites.get(suiteName);
            if (suite != null) {
                suite.tests.add(tr);
                suite.finishedAt = Instant.now();
            }
        }
    }

    @Override
    public void executionSkipped(TestIdentifier id, String reason) {
        if (id.isTest()) {
            String suiteName = extractSuiteName(id);
            String testName = id.getDisplayName();

            var tr = new TestResult(suiteName, testName, "SKIPPED", 0, reason);
            allResults.add(tr);

            var suite = suites.computeIfAbsent(suiteName, k -> new SuiteData(k, categorize(k)));
            suite.tests.add(tr);
        }
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        if (allResults.isEmpty()) return;
        printAsciiSummary();
        writeJsonReport();
    }

    private String extractSuiteName(TestIdentifier id) {
        // The parent source is typically "[class:com.example.FooTest]"
        return id.getParentId()
                .map(pid -> {
                    int lastDot = pid.lastIndexOf('.');
                    int bracket = pid.lastIndexOf(']');
                    if (lastDot >= 0 && bracket > lastDot) {
                        return pid.substring(lastDot + 1, bracket);
                    }
                    return pid;
                })
                .orElse("Unknown");
    }

    // --- ASCII Report ---

    private void printAsciiSummary() {
        long totalPassed =
                allResults.stream().filter(t -> "PASSED".equals(t.status)).count();
        long totalFailed =
                allResults.stream().filter(t -> "FAILED".equals(t.status)).count();
        long totalSkipped =
                allResults.stream().filter(t -> "SKIPPED".equals(t.status)).count();
        long totalDuration =
                runStart != null ? Duration.between(runStart, Instant.now()).toMillis() : 0;

        var sb = new StringBuilder();
        sb.append("\n");
        sb.append("=".repeat(80)).append("\n");
        sb.append("  PREXORCLOUD TEST HARNESS REPORT\n");
        sb.append("=".repeat(80)).append("\n\n");

        sb.append(String.format(
                "  Total: %d  |  Passed: %d  |  Failed: %d  |  Skipped: %d  |  Time: %s%n%n",
                allResults.size(), totalPassed, totalFailed, totalSkipped, fmt(totalDuration)));

        double passRate =
                (totalPassed + totalFailed) == 0 ? 100.0 : (double) totalPassed / (totalPassed + totalFailed) * 100;
        String badge =
                totalFailed > 0 ? "[FAILURES DETECTED]" : totalSkipped > 0 ? "[CLEAN - some skipped]" : "[ALL GREEN]";
        sb.append(String.format("  Pass Rate: %.1f%%  %s%n%n", passRate, badge));

        // Category breakdown
        sb.append("-".repeat(80)).append("\n");
        sb.append(String.format("  %-30s %7s %7s %7s %10s%n", "CATEGORY", "PASSED", "FAILED", "SKIP", "TIME"));
        sb.append("-".repeat(80)).append("\n");

        var byCategory = new TreeMap<String, List<SuiteData>>();
        for (var suite : suites.values()) {
            byCategory.computeIfAbsent(suite.category, k -> new ArrayList<>()).add(suite);
        }

        for (var entry : byCategory.entrySet()) {
            long p = entry.getValue().stream().mapToLong(SuiteData::passed).sum();
            long f = entry.getValue().stream().mapToLong(SuiteData::failed).sum();
            long s = entry.getValue().stream().mapToLong(SuiteData::skipped).sum();
            long d = entry.getValue().stream().mapToLong(SuiteData::durationMs).sum();
            String marker = f > 0 ? " !!" : s > 0 ? " *" : "";
            sb.append(String.format("  %-30s %7d %7d %7d %10s%s%n", entry.getKey(), p, f, s, fmt(d), marker));
        }

        sb.append("-".repeat(80)).append("\n\n");

        // Per-suite breakdown
        sb.append(String.format("  %-35s %7s %7s %7s %10s%n", "SUITE", "PASSED", "FAILED", "SKIP", "TIME"));
        sb.append("-".repeat(80)).append("\n");

        var sorted = suites.values().stream()
                .sorted(Comparator.comparing((SuiteData s) -> s.category).thenComparing(s -> s.name))
                .toList();

        String lastCat = "";
        for (var suite : sorted) {
            if (!suite.category.equals(lastCat)) {
                if (!lastCat.isEmpty()) sb.append("\n");
                sb.append("  [").append(suite.category).append("]\n");
                lastCat = suite.category;
            }
            String m = suite.failed() > 0 ? " FAIL" : suite.skipped() > 0 ? " skip" : " ok";
            sb.append(String.format(
                    "    %-33s %7d %7d %7d %10s%s%n",
                    suite.name, suite.passed(), suite.failed(), suite.skipped(), fmt(suite.durationMs()), m));
        }
        sb.append("\n");

        // Failures
        var failures =
                allResults.stream().filter(t -> "FAILED".equals(t.status)).toList();
        if (!failures.isEmpty()) {
            sb.append("-".repeat(80)).append("\n");
            sb.append("  FAILURES:\n");
            sb.append("-".repeat(80)).append("\n");
            for (var f : failures) {
                sb.append(String.format("  %s > %s%n", f.suite, f.testName));
                if (f.failureMessage != null) {
                    sb.append("    ").append(truncate(f.failureMessage, 120)).append("\n");
                }
            }
            sb.append("\n");
        }

        // Skipped
        var skipped =
                allResults.stream().filter(t -> "SKIPPED".equals(t.status)).toList();
        if (!skipped.isEmpty()) {
            sb.append("-".repeat(80)).append("\n");
            sb.append("  SKIPPED:\n");
            sb.append("-".repeat(80)).append("\n");
            for (var s : skipped) {
                sb.append(String.format("  %s > %s%n", s.suite, s.testName));
                if (s.failureMessage != null)
                    sb.append("    ").append(truncate(s.failureMessage, 100)).append("\n");
            }
            sb.append("\n");
        }

        // Slowest
        var slowest = allResults.stream()
                .filter(t -> "PASSED".equals(t.status))
                .sorted(Comparator.comparingLong(TestResult::durationMs).reversed())
                .limit(5)
                .toList();
        if (!slowest.isEmpty()) {
            sb.append("-".repeat(80)).append("\n");
            sb.append("  SLOWEST TESTS:\n");
            sb.append("-".repeat(80)).append("\n");
            for (var s : slowest) {
                sb.append(String.format("  %10s  %s > %s%n", fmt(s.durationMs), s.suite, s.testName));
            }
            sb.append("\n");
        }

        sb.append("=".repeat(80)).append("\n");
        System.out.println(sb);
    }

    // --- JSON Report ---

    private void writeJsonReport() {
        try {
            long totalDuration =
                    runStart != null ? Duration.between(runStart, Instant.now()).toMillis() : 0;
            long totalPassed =
                    allResults.stream().filter(t -> "PASSED".equals(t.status)).count();
            long totalFailed =
                    allResults.stream().filter(t -> "FAILED".equals(t.status)).count();
            long totalSkipped =
                    allResults.stream().filter(t -> "SKIPPED".equals(t.status)).count();

            var report = new LinkedHashMap<String, Object>();
            report.put("timestamp", Instant.now().toString());
            report.put("durationMs", totalDuration);
            report.put("totalTests", allResults.size());
            report.put("passed", totalPassed);
            report.put("failed", totalFailed);
            report.put("skipped", totalSkipped);
            report.put(
                    "passRate",
                    (totalPassed + totalFailed) == 0
                            ? 100.0
                            : Math.round((double) totalPassed / (totalPassed + totalFailed) * 1000.0) / 10.0);

            // Suites
            var suitesJson = new ArrayList<Map<String, Object>>();
            for (var suite : suites.values()) {
                var s = new LinkedHashMap<String, Object>();
                s.put("name", suite.name);
                s.put("category", suite.category);
                s.put("passed", suite.passed());
                s.put("failed", suite.failed());
                s.put("skipped", suite.skipped());
                s.put("durationMs", suite.durationMs());

                var tests = new ArrayList<Map<String, Object>>();
                for (var t : suite.tests) {
                    var tm = new LinkedHashMap<String, Object>();
                    tm.put("name", t.testName());
                    tm.put("status", t.status());
                    tm.put("durationMs", t.durationMs());
                    if (t.failureMessage() != null) tm.put("failure", t.failureMessage());
                    tests.add(tm);
                }
                s.put("tests", tests);
                suitesJson.add(s);
            }
            report.put("suites", suitesJson);

            // Categories
            var categories = new LinkedHashMap<String, Object>();
            var byCategory = new TreeMap<String, List<SuiteData>>();
            for (var suite : suites.values()) {
                byCategory
                        .computeIfAbsent(suite.category, k -> new ArrayList<>())
                        .add(suite);
            }
            for (var entry : byCategory.entrySet()) {
                var c = new LinkedHashMap<String, Object>();
                c.put(
                        "passed",
                        entry.getValue().stream().mapToLong(SuiteData::passed).sum());
                c.put(
                        "failed",
                        entry.getValue().stream().mapToLong(SuiteData::failed).sum());
                c.put(
                        "skipped",
                        entry.getValue().stream().mapToLong(SuiteData::skipped).sum());
                c.put(
                        "durationMs",
                        entry.getValue().stream()
                                .mapToLong(SuiteData::durationMs)
                                .sum());
                c.put("suites", entry.getValue().stream().map(sd -> sd.name).toList());
                categories.put(entry.getKey(), c);
            }
            report.put("categories", categories);

            // Failures
            report.put(
                    "failures",
                    allResults.stream()
                            .filter(t -> "FAILED".equals(t.status))
                            .map(t -> {
                                var m = new LinkedHashMap<String, String>();
                                m.put("suite", t.suite());
                                m.put("test", t.testName());
                                m.put("message", t.failureMessage() != null ? t.failureMessage() : "");
                                return m;
                            })
                            .toList());

            String reportDir = System.getProperty("test.report.dir", "build");
            Path outputDir = Path.of(reportDir);
            Files.createDirectories(outputDir);
            Path jsonPath = outputDir.resolve("test-harness-report.json");

            var mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            mapper.writeValue(jsonPath.toFile(), report);

            System.out.println("  JSON report: " + jsonPath.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("Failed to write JSON report: " + e.getMessage());
        }
    }

    private static String fmt(long ms) {
        if (ms < 1000) return ms + "ms";
        if (ms < 60_000) return String.format("%.1fs", ms / 1000.0);
        return String.format("%dm %ds", ms / 60_000, (ms % 60_000) / 1000);
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
