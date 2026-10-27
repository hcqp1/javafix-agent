package com.javafix.agent.tool;

import com.javafix.agent.core.TestResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TraceDiagnosticToolTest {
    private static final String SOURCE = "src/main/java/example/Calculator.java";
    private static final Map<String, String> ARGUMENTS = Map.of(
            "path", SOURCE, "class", "example.Calculator", "method", "add", "test", "CalculatorTest#adds");
    @TempDir Path root;
    private byte[] originalHash;
    private final List<Path> copies = new ArrayList<>();

    @BeforeEach
    void fixture() throws Exception {
        Files.createDirectories(root.resolve(SOURCE).getParent());
        Files.writeString(root.resolve(SOURCE), """
                package example;
                public class Calculator {
                    public int add(int a, int b) {
                        int result = a - b;
                        if (result < 0) { result = result - 1; }
                        return result;
                    }
                }
                """);
        originalHash = hash();
    }

    @AfterEach
    void preservesSourceAndCleansCopies() throws Exception {
        assertArrayEquals(originalHash, hash(), "original source changed");
        for (Path copy : copies) {
            assertFalse(Files.exists(copy.getParent()), "workspace leaked: " + copy);
        }
    }

    @Test
    void realMavenReturnsMatchedLocalAndBranchTrace() throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>example</groupId>
                <artifactId>trace-fixture</artifactId><version>1</version>
                <properties><maven.compiler.source>17</maven.compiler.source>
                <maven.compiler.target>17</maven.compiler.target><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
                <dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId>
                <version>5.10.2</version><scope>test</scope></dependency></dependencies>
                <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId>
                <version>3.2.5</version></plugin></plugins></build></project>
                """);
        Path test = root.resolve("src/test/java/example/CalculatorTest.java");
        Files.createDirectories(test.getParent());
        Files.writeString(test, """
                package example;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class CalculatorTest {
                    @Test void adds() { assertEquals(5, new Calculator().add(2, 3)); }
                }
                """);
        MavenTestRunner maven = new MavenTestRunner(Duration.ofSeconds(60));
        String result = tool(maven::run).execute(ARGUMENTS);
        assertTrue(result.contains("per-test parity matched"), result);
        assertTrue(result.contains("Trace:"), result);
        assertTrue(result.contains("LOCAL_DECLARATION"), result);
        assertTrue(result.contains("BRANCH_THEN"), result);
        assertTrue(result.contains("result=\"-1\""), result);
        assertEquals(2, copies.size());
        assertTrue(result.length() <= 3500, result);
    }

    @Test
    void acceptsExactParityAndFreshTracePath() {
        String result = tool((project, selector, environment) -> {
            assertEquals("CalculatorTest#adds", selector);
            report(project, "adds", "failure");
            if (!environment.isEmpty()) {
                Path trace = Path.of(environment.get("JAVAFIX_TRACE_FILE"));
                assertTrue(trace.startsWith(project.resolve("target")));
                assertFalse(Files.exists(trace));
                assertFalse(Files.exists(Path.of(trace + ".state")));
                assertTrue(Files.readString(project.resolve(SOURCE)).contains("final class JavafixTrace"));
                trace(environment, 2, 0);
            } else {
                assertArrayEquals(originalHash, digest(Files.readAllBytes(project.resolve(SOURCE))));
            }
            return failed();
        }).execute(ARGUMENTS);
        assertTrue(result.contains("per-test parity matched"), result);
        assertTrue(result.contains("Trace:"), result);
    }

    @ParameterizedTest
    @ValueSource(strings = {"passed", "identity"})
    void rejectsChangedOutcomeOrIdentity(String change) {
        String result = tool((project, selector, environment) -> {
            boolean instrumented = !environment.isEmpty();
            report(project, instrumented && change.equals("identity") ? "different" : "adds",
                    instrumented && change.equals("passed") ? "" : "failure");
            if (instrumented) trace(environment, 1, 0);
            return failed();
        }).execute(ARGUMENTS);
        noTrace(result, "parity mismatch");
        assertEquals(2, copies.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"baseline", "instrumented"})
    void rejectsTimeoutEvenWhenReportsExist(String stage) {
        String result = tool((project, selector, environment) -> {
            report(project, "adds", "failure");
            if (stage.equals(environment.isEmpty() ? "baseline" : "instrumented")) {
                return new TestResult(TestResult.EXIT_TIMEOUT, "", "timeout");
            }
            return failed();
        }).execute(ARGUMENTS);
        noTrace(result, stage + " timeout");
        assertEquals(stage.equals("baseline") ? 1 : 2, copies.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"baseline", "instrumented"})
    void rejectsMissingReports(String stage) {
        String result = tool((project, selector, environment) -> {
            if (!stage.equals(environment.isEmpty() ? "baseline" : "instrumented")) report(project, "adds", "failure");
            return failed();
        }).execute(ARGUMENTS);
        noTrace(result, stage + " missing reports");
    }

    @Test
    void rejectsPassingBaseline() {
        String result = tool((project, selector, environment) -> {
            report(project, "adds", "");
            return new TestResult(0, "BUILD SUCCESS", "");
        }).execute(ARGUMENTS);
        noTrace(result, "baseline has no FAILED/ERROR tests");
        assertEquals(1, copies.size());
    }

    @Test
    void distinguishesBuildFailureFromReportAmbiguity() {
        noTrace(tool((p, s, e) -> new TestResult(1, "COMPILATION ERROR", "")).execute(ARGUMENTS),
                "baseline build failure");
        noTrace(tool((p, s, e) -> {
            report(p, "adds", "failure");
            Files.writeString(p.resolve("target/surefire-reports/TEST-fixture.xml"), "<broken");
            return failed();
        }).execute(ARGUMENTS), "baseline report parse ambiguity");
    }

    @Test
    void rejectsBaselineStartFailureAndRunnerExceptions() {
        noTrace(tool((p, s, e) -> new TestResult(TestResult.EXIT_START_FAILURE, "", "")).execute(ARGUMENTS),
                "baseline execution failure");
        noTrace(tool((p, s, e) -> { throw new IOException("runner failed"); }).execute(ARGUMENTS),
                "diagnostic I/O failure");
    }

    @Test
    void checksOverallDeadlineBeforeSecondRun() {
        TraceDiagnosticTool tool = new TraceDiagnosticTool(root, tracking((p, s, e) -> {
            report(p, "adds", "failure");
            try { Thread.sleep(40); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            return failed();
        }), Duration.ofMillis(20));
        noTrace(tool.execute(ARGUMENTS), "overall deadline");
        assertTrue(copies.size() <= 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "CalculatorTest & whoami", "A|B", "A>B", "A%B", "A\"B", "A B", "A;B"})
    void rejectsUnsafeOrEmptySelector(String selector) {
        var arguments = new HashMap<>(ARGUMENTS);
        arguments.put("test", selector);
        noTrace(tool((p, s, e) -> fail("must not launch Maven")).execute(arguments), "argument");
    }

    @ParameterizedTest
    @ValueSource(strings = {"../escape.java", "pom.xml", "missing.java"})
    void rejectsInvalidSourcePaths(String path) {
        var arguments = new HashMap<>(ARGUMENTS);
        arguments.put("path", path);
        noTrace(tool((p, s, e) -> fail("must not launch Maven")).execute(arguments), "source path");
    }

    @Test
    void rejectsMissingAndUnknownArguments() {
        var arguments = new HashMap<>(ARGUMENTS);
        arguments.remove("method");
        noTrace(tool((p, s, e) -> fail("must not launch Maven")).execute(arguments), "argument");
        arguments.put("unexpected", "value");
        noTrace(tool((p, s, e) -> fail("must not launch Maven")).execute(arguments), "argument");
    }

    @Test
    void rejectsAmbiguousMethodBeforeRunningTests() throws Exception {
        Files.writeString(root.resolve(SOURCE), "package example; class Calculator { int add(int x){return x;} int add(){return 1;} }");
        originalHash = hash();
        noTrace(tool((p, s, e) -> fail("must not launch Maven")).execute(ARGUMENTS), "Ambiguous method");
    }

    @Test
    void reportsExactOverflowAndBoundsObservation() {
        String result = tool((p, s, e) -> {
            report(p, "adds", "failure");
            if (!e.isEmpty()) trace(e, 500, 1234);
            return failed();
        }).execute(ARGUMENTS);
        assertTrue(result.contains("Trace:"), result);
        assertTrue(result.contains("trace overflow: 1234 events omitted"), result);
        assertTrue(result.contains("display omitted"), result);
        assertTrue(result.length() <= 3500, result);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "malformed", "partial", "sequence", "wrong-source", "oversized-value", "state-mismatch",
            "blank-record", "overflowing-sequence", "duplicate-field", "trailing-json"})
    void rejectsUnavailableOrIncompleteTrace(String defect) {
        String result = tool((p, s, e) -> {
            report(p, "adds", "failure");
            if (!e.isEmpty() && !defect.equals("missing")) {
                trace(e, 1, 0);
                Path trace = Path.of(e.get("JAVAFIX_TRACE_FILE"));
                String text = Files.readString(trace);
                text = switch (defect) {
                    case "malformed" -> "not JSON\n";
                    case "partial" -> text.stripTrailing();
                    case "sequence" -> text.replace("\"sequence\":1", "\"sequence\":2");
                    case "wrong-source" -> text.replace(SOURCE, "somewhere.java");
                    case "oversized-value" -> text.replace("\"-1\"", "\"" + "x".repeat(161) + "\"");
                    case "blank-record" -> text + "\n";
                    case "overflowing-sequence" -> text.replace("\"sequence\":1", "\"sequence\":18446744073709551617");
                    case "duplicate-field" -> text.replace("\"sequence\":1", "\"sequence\":2,\"sequence\":1");
                    case "trailing-json" -> text.stripTrailing() + "{}\n";
                    default -> text;
                };
                Files.writeString(trace, text);
                if (defect.equals("state-mismatch")) Files.write(Path.of(trace + ".state"), new byte[24]);
            }
            return failed();
        }).execute(ARGUMENTS);
        noTrace(result, "trace unavailable");
    }

    private TraceDiagnosticTool tool(DiagnosticTestRunner runner) {
        return new TraceDiagnosticTool(root, tracking(runner), Duration.ofMinutes(3));
    }

    private DiagnosticTestRunner tracking(DiagnosticTestRunner runner) {
        return (project, selector, environment) -> {
            copies.add(project);
            assertNotEquals(root, project);
            return runner.run(project, selector, environment);
        };
    }

    private static TestResult failed() { return new TestResult(1, "Tests run: 1, Failures: 1\nBUILD FAILURE", ""); }

    private static void report(Path project, String identity, String outcome) throws IOException {
        Path directory = project.resolve("target/surefire-reports");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("TEST-fixture.xml"), "<testsuite><testcase classname=\"example.CalculatorTest\" name=\""
                + identity + "\">" + (outcome.isEmpty() ? "" : "<" + outcome + "/>") + "</testcase></testsuite>");
    }

    private static void trace(Map<String, String> environment, int count, long omitted) throws IOException {
        Path path = Path.of(environment.get("JAVAFIX_TRACE_FILE"));
        Files.createDirectories(path.getParent());
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= count; i++) text.append("{\"sequence\":").append(i)
                .append(",\"source\":\"").append(SOURCE).append("\",\"method\":\"example.Calculator.add\",\"kind\":\"LOCAL_DECLARATION\",\"line\":4,\"variable\":\"result\",\"value\":\"-1\",\"truncated\":false}\n");
        long end = text.toString().getBytes(StandardCharsets.UTF_8).length;
        if (omitted > 0) text.append("{\"sequence\":501,\"kind\":\"overflow\",\"omitted\":").append(omitted).append("}\n");
        Files.writeString(path, text);
        try (var state = new DataOutputStream(Files.newOutputStream(Path.of(path + ".state")))) {
            state.writeLong(count); state.writeLong(omitted); state.writeLong(end);
        }
    }

    private byte[] hash() throws Exception { return digest(Files.readAllBytes(root.resolve(SOURCE))); }
    private static byte[] digest(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private static void noTrace(String observation, String reason) {
        assertFalse(observation.contains("Trace:"), observation);
        assertTrue(observation.contains(reason), observation);
        assertTrue(observation.length() <= 3500, observation);
    }
}
