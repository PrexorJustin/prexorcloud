package me.prexorjustin.prexorcloud.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.extension.*;

/**
 * JUnit 5 extension that collects test results across all suites and produces
 * an ASCII console summary + JSON report file at the end of the test run.
 *
 * <p>Register globally via {@code META-INF/services/org.junit.jupiter.api.extension.Extension}
 * or annotate individual test classes with {@code @ExtendWith(TestReportExtension.class)}.</p>
 */
public final class TestReportExtension
        implements BeforeAllCallback,
                AfterAllCallback,
                TestWatcher,
                BeforeTestExecutionCallback,
                AfterTestExecutionCallback {

    // --- Shared state (static so it survives across test class instances) ---

    private static final Instant RUN_START = Instant.now();
    private static final Map<String, SuiteResult> SUITES = new ConcurrentHashMap<>();
    private static final List<TestResult> ALL_RESULTS = new CopyOnWriteArrayList<>();
    private static volatile boolean shutdownHookRegistered = false;

    // Per-test timing (stored in ExtensionContext.Store)
    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(TestReportExtension.class);

    // --- Records ---

    public record TestResult(String suite, String testName, String status, long durationMs, String failureMessage) {}

    public record SuiteResult(
            String name, String category, List<TestResult> tests, Instant startedAt, Instant finishedAt) {

        public long passed() {
            return tests.stream().filter(t -> "PASSED".equals(t.status)).count();
        }

        public long failed() {
            return tests.stream().filter(t -> "FAILED".equals(t.status)).count();
        }

        public long skipped() {
            return tests.stream().filter(t -> "SKIPPED".equals(t.status)).count();
        }

        public long durationMs() {
            return startedAt != null && finishedAt != null
                    ? Duration.between(startedAt, finishedAt).toMillis()
                    : 0;
        }
    }

    // --- Category mapping ---

    private static String categorize(String className) {
        String simple = className.contains(".") ? className.substring(className.lastIndexOf('.') + 1) : className;
        return switch (simple) {
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

    // --- JUnit callbacks ---

    @Override
    public void beforeAll(ExtensionContext context) {
        String className = context.getRequiredTestClass().getName();
        SUITES.computeIfAbsent(
                className,
                k -> new SuiteResult(simpleName(k), categorize(k), new CopyOnWriteArrayList<>(), Instant.now(), null));

        // Register shutdown hook once to generate reports at the very end
        if (!shutdownHookRegistered) {
            synchronized (TestReportExtension.class) {
                if (!shutdownHookRegistered) {
                    Runtime.getRuntime()
                            .addShutdownHook(new Thread(TestReportExtension::generateReports, "test-report"));
                    shutdownHookRegistered = true;
                }
            }
        }
    }

    @Override
    public void afterAll(ExtensionContext context) {
        String className = context.getRequiredTestClass().getName();
        SUITES.computeIfPresent(
                className, (k, v) -> new SuiteResult(v.name, v.category, v.tests, v.startedAt, Instant.now()));
    }

    @Override
    public void beforeTestExecution(ExtensionContext context) {
        context.getStore(NS).put("start", System.currentTimeMillis());
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        // duration is computed later in testSuccessful/testFailed/etc.
    }

    @Override
    public void testSuccessful(ExtensionContext context) {
        record(context, "PASSED", null);
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        record(context, "FAILED", cause.getMessage());
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        record(context, "SKIPPED", cause != null ? cause.getMessage() : "aborted");
    }

    @Override
    public void testDisabled(ExtensionContext context, Optional<String> reason) {
        record(context, "SKIPPED", reason.orElse("disabled"));
    }

    private void record(ExtensionContext context, String status, String failureMessage) {
        long durationMs = 0;
        Long start = context.getStore(NS).remove("start", Long.class);
        if (start != null) {
            durationMs = System.currentTimeMillis() - start;
        }

        String className = context.getRequiredTestClass().getName();
        String testName = context.getDisplayName();

        var result = new TestResult(simpleName(className), testName, status, durationMs, failureMessage);
        ALL_RESULTS.add(result);

        var suite = SUITES.get(className);
        if (suite != null) {
            suite.tests().add(result);
        }
    }

    // --- Report generation ---

    private static void generateReports() {
        printAsciiSummary();
        writeJsonReport();
    }

    private static void printAsciiSummary() {
        long totalPassed =
                ALL_RESULTS.stream().filter(t -> "PASSED".equals(t.status)).count();
        long totalFailed =
                ALL_RESULTS.stream().filter(t -> "FAILED".equals(t.status)).count();
        long totalSkipped =
                ALL_RESULTS.stream().filter(t -> "SKIPPED".equals(t.status)).count();
        long totalDuration = Duration.between(RUN_START, Instant.now()).toMillis();

        var sb = new StringBuilder();
        sb.append("\n");
        sb.append("=".repeat(80)).append("\n");
        sb.append("  PREXORCLOUD TEST HARNESS REPORT\n");
        sb.append("=".repeat(80)).append("\n\n");

        // Overall summary
        sb.append(String.format(
                "  Total: %d  |  Passed: %d  |  Failed: %d  |  Skipped: %d  |  Time: %s%n%n",
                ALL_RESULTS.size(), totalPassed, totalFailed, totalSkipped, formatDuration(totalDuration)));

        double passRate = ALL_RESULTS.isEmpty() ? 0 : (double) totalPassed / (totalPassed + totalFailed) * 100;
        sb.append(String.format(
                "  Pass Rate: %.1f%%  %s%n%n",
                passRate, passRate == 100 ? "[ALL GREEN]" : totalFailed > 0 ? "[FAILURES DETECTED]" : "[CLEAN]"));

        // Per-category breakdown
        sb.append("-".repeat(80)).append("\n");
        sb.append(String.format("  %-30s %7s %7s %7s %10s%n", "CATEGORY", "PASSED", "FAILED", "SKIP", "TIME"));
        sb.append("-".repeat(80)).append("\n");

        var byCategory = new TreeMap<String, List<SuiteResult>>();
        for (var suite : SUITES.values()) {
            byCategory.computeIfAbsent(suite.category(), k -> new ArrayList<>()).add(suite);
        }

        for (var entry : byCategory.entrySet()) {
            long catPassed =
                    entry.getValue().stream().mapToLong(SuiteResult::passed).sum();
            long catFailed =
                    entry.getValue().stream().mapToLong(SuiteResult::failed).sum();
            long catSkipped =
                    entry.getValue().stream().mapToLong(SuiteResult::skipped).sum();
            long catDuration =
                    entry.getValue().stream().mapToLong(SuiteResult::durationMs).sum();

            String marker = catFailed > 0 ? " !!" : catSkipped > 0 ? " *" : "";
            sb.append(String.format(
                    "  %-30s %7d %7d %7d %10s%s%n",
                    entry.getKey(), catPassed, catFailed, catSkipped, formatDuration(catDuration), marker));
        }

        sb.append("-".repeat(80)).append("\n\n");

        // Per-suite breakdown
        sb.append(String.format("  %-35s %7s %7s %7s %10s%n", "SUITE", "PASSED", "FAILED", "SKIP", "TIME"));
        sb.append("-".repeat(80)).append("\n");

        var sortedSuites = SUITES.values().stream()
                .sorted(Comparator.comparing(SuiteResult::category).thenComparing(SuiteResult::name))
                .toList();

        String lastCategory = "";
        for (var suite : sortedSuites) {
            if (!suite.category().equals(lastCategory)) {
                if (!lastCategory.isEmpty()) sb.append("\n");
                sb.append("  [").append(suite.category()).append("]\n");
                lastCategory = suite.category();
            }
            String marker = suite.failed() > 0 ? " FAIL" : suite.skipped() > 0 ? " skip" : " ok";
            sb.append(String.format(
                    "    %-33s %7d %7d %7d %10s%s%n",
                    suite.name(),
                    suite.passed(),
                    suite.failed(),
                    suite.skipped(),
                    formatDuration(suite.durationMs()),
                    marker));
        }

        sb.append("\n");

        // Failed tests detail
        var failures =
                ALL_RESULTS.stream().filter(t -> "FAILED".equals(t.status)).toList();
        if (!failures.isEmpty()) {
            sb.append("-".repeat(80)).append("\n");
            sb.append("  FAILURES:\n");
            sb.append("-".repeat(80)).append("\n");
            for (var f : failures) {
                sb.append(String.format("  %s > %s%n", f.suite, f.testName));
                if (f.failureMessage != null) {
                    String msg = f.failureMessage.length() > 120
                            ? f.failureMessage.substring(0, 120) + "..."
                            : f.failureMessage;
                    sb.append("    ").append(msg).append("\n");
                }
            }
            sb.append("\n");
        }

        // Skipped tests
        var skipped =
                ALL_RESULTS.stream().filter(t -> "SKIPPED".equals(t.status)).toList();
        if (!skipped.isEmpty()) {
            sb.append("-".repeat(80)).append("\n");
            sb.append("  SKIPPED:\n");
            sb.append("-".repeat(80)).append("\n");
            for (var s : skipped) {
                sb.append(String.format("  %s > %s%n", s.suite, s.testName));
                if (s.failureMessage != null) {
                    String msg = s.failureMessage.length() > 100
                            ? s.failureMessage.substring(0, 100) + "..."
                            : s.failureMessage;
                    sb.append("    ").append(msg).append("\n");
                }
            }
            sb.append("\n");
        }

        // Slowest tests
        var slowest = ALL_RESULTS.stream()
                .filter(t -> "PASSED".equals(t.status))
                .sorted(Comparator.comparingLong(TestResult::durationMs).reversed())
                .limit(5)
                .toList();
        if (!slowest.isEmpty()) {
            sb.append("-".repeat(80)).append("\n");
            sb.append("  SLOWEST TESTS:\n");
            sb.append("-".repeat(80)).append("\n");
            for (var s : slowest) {
                sb.append(String.format("  %10s  %s > %s%n", formatDuration(s.durationMs), s.suite, s.testName));
            }
            sb.append("\n");
        }

        sb.append("=".repeat(80)).append("\n");

        System.out.println(sb);
    }

    private static void writeJsonReport() {
        try {
            long totalDuration = Duration.between(RUN_START, Instant.now()).toMillis();
            long totalPassed =
                    ALL_RESULTS.stream().filter(t -> "PASSED".equals(t.status)).count();
            long totalFailed =
                    ALL_RESULTS.stream().filter(t -> "FAILED".equals(t.status)).count();
            long totalSkipped =
                    ALL_RESULTS.stream().filter(t -> "SKIPPED".equals(t.status)).count();

            var report = new LinkedHashMap<String, Object>();
            report.put("timestamp", Instant.now().toString());
            report.put("durationMs", totalDuration);
            report.put("totalTests", ALL_RESULTS.size());
            report.put("passed", totalPassed);
            report.put("failed", totalFailed);
            report.put("skipped", totalSkipped);
            report.put(
                    "passRate",
                    ALL_RESULTS.isEmpty()
                            ? 0.0
                            : Math.round((double) totalPassed / (totalPassed + totalFailed) * 1000.0) / 10.0);

            // Suites
            var suitesJson = new ArrayList<Map<String, Object>>();
            for (var suite : SUITES.values()) {
                var s = new LinkedHashMap<String, Object>();
                s.put("name", suite.name());
                s.put("category", suite.category());
                s.put("passed", suite.passed());
                s.put("failed", suite.failed());
                s.put("skipped", suite.skipped());
                s.put("durationMs", suite.durationMs());

                var tests = new ArrayList<Map<String, Object>>();
                for (var t : suite.tests()) {
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
            var byCategory = new TreeMap<String, List<SuiteResult>>();
            for (var suite : SUITES.values()) {
                byCategory
                        .computeIfAbsent(suite.category(), k -> new ArrayList<>())
                        .add(suite);
            }
            for (var entry : byCategory.entrySet()) {
                var c = new LinkedHashMap<String, Object>();
                c.put(
                        "passed",
                        entry.getValue().stream().mapToLong(SuiteResult::passed).sum());
                c.put(
                        "failed",
                        entry.getValue().stream().mapToLong(SuiteResult::failed).sum());
                c.put(
                        "skipped",
                        entry.getValue().stream()
                                .mapToLong(SuiteResult::skipped)
                                .sum());
                c.put(
                        "durationMs",
                        entry.getValue().stream()
                                .mapToLong(SuiteResult::durationMs)
                                .sum());
                c.put("suites", entry.getValue().stream().map(SuiteResult::name).toList());
                categories.put(entry.getKey(), c);
            }
            report.put("categories", categories);

            // Failures list
            report.put(
                    "failures",
                    ALL_RESULTS.stream()
                            .filter(t -> "FAILED".equals(t.status))
                            .map(t -> Map.of(
                                    "suite",
                                    t.suite(),
                                    "test",
                                    t.testName(),
                                    "message",
                                    t.failureMessage() != null ? t.failureMessage() : ""))
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

    // --- Helpers ---

    private static String simpleName(String fqcn) {
        return fqcn.contains(".") ? fqcn.substring(fqcn.lastIndexOf('.') + 1) : fqcn;
    }

    private static String formatDuration(long ms) {
        if (ms < 1000) return ms + "ms";
        if (ms < 60_000) return String.format("%.1fs", ms / 1000.0);
        long minutes = ms / 60_000;
        long seconds = (ms % 60_000) / 1000;
        return String.format("%dm %ds", minutes, seconds);
    }
}
