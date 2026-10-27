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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
                <version>3.2.5</version><configuration><useManifestOnlyJar>false</useManifestOnlyJar></configuration>
                </plugin></plugins></build></project>
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
        StringBuilder diagnostics = new StringBuilder();
        String result = tool((p, s, e, timeout) -> {
            TestResult run = maven.run(p, s, e, timeout);
            diagnostics.append(run.getStdout()).append(run.getStderr());
            Path reports = p.resolve("target/surefire-reports");
            if (Files.isDirectory(reports)) {
                try (var files = Files.list(reports)) {
                    for (Path file : files.filter(path -> path.toString().endsWith(".dump")
                            || path.toString().endsWith(".dumpstream")).toList()) {
                        diagnostics.append("\n").append(file.getFileName()).append(":\n").append(Files.readString(file));
                    }
                }
            }
            return run;
        }).execute(ARGUMENTS);
        assertTrue(result.contains("per-test parity matched"), result + "\n" + diagnostics);
        assertTrue(result.contains("Trace:"), result);
        assertTrue(result.contains("LOCAL_DECLARATION"), result);
        assertTrue(result.contains("BRANCH_THEN"), result);
        assertTrue(result.contains("result=\"-1\""), result);
        assertEquals(2, copies.size());
        assertTrue(result.length() <= 3500, result);
    }

    @Test
    void acceptsExactParityAndFreshTracePath() {
        String result = tool((project, selector, environment, timeout) -> {
            assertEquals("CalculatorTest#adds", selector);
            report(project, "adds", "failure");
            if (!environment.isEmpty()) {
                Path trace = Path.of(environment.get("JAVAFIX_TRACE_FILE"));
                assertTrue(trace.startsWith(project.resolve("target")));
                assertFalse(Files.exists(trace));
                assertFalse(Files.exists(Path.of(trace + ".state")));
                assertFalse(Files.exists(Path.of(trace + ".failed")));
                assertArrayEquals(new byte[]{0}, Files.readAllBytes(Path.of(trace + ".status")));
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
        String result = tool((project, selector, environment, timeout) -> {
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
        String result = tool((project, selector, environment, timeout) -> {
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
        String result = tool((project, selector, environment, timeout) -> {
            if (!stage.equals(environment.isEmpty() ? "baseline" : "instrumented")) report(project, "adds", "failure");
            return failed();
        }).execute(ARGUMENTS);
        noTrace(result, stage + " missing reports");
    }

    @Test
    void rejectsPassingBaseline() {
        String result = tool((project, selector, environment, timeout) -> {
            report(project, "adds", "");
            return new TestResult(0, "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0\nBUILD SUCCESS", "");
        }).execute(ARGUMENTS);
        noTrace(result, "baseline has no FAILED/ERROR tests");
        assertEquals(1, copies.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"The forked VM terminated without properly saying goodbye",
            "There was an error in the forked process", "SurefireBooterForkException"})
    void rejectsPartialReportsAfterForkFailure(String marker) {
        for (String stage : List.of("baseline", "instrumented")) {
            for (boolean stderr : List.of(false, true)) {
                String result = tool((p, s, e, timeout) -> {
                    report(p, "completedA", "failure");
                    if (!e.isEmpty()) trace(e, 1, 0);
                    boolean crash = stage.equals(e.isEmpty() ? "baseline" : "instrumented");
                    return new TestResult(1, failed().getStdout() + (crash && !stderr ? "\n" + marker : ""),
                            crash && stderr ? marker : "");
                }).execute(ARGUMENTS);
                noTrace(result, stage + " infrastructure failure");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"jvmRun1.dump", "jvmRun1.dumpstream"})
    void rejectsForkDumpArtifactsEvenWithMatchingXml(String dump) {
        for (String stage : List.of("baseline", "instrumented")) {
            String result = tool((p, s, e, timeout) -> {
                report(p, "completedA", "failure");
                if (!e.isEmpty()) trace(e, 1, 0);
                if (stage.equals(e.isEmpty() ? "baseline" : "instrumented")) {
                    Files.writeString(p.resolve("target/surefire-reports").resolve(dump), "incomplete fork");
                }
                return failed();
            }).execute(ARGUMENTS);
            noTrace(result, stage + " infrastructure failure");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"baseline", "instrumented"})
    void rejectsCompletedTestTotalThatExceedsXmlCount(String stage) {
        String result = tool((p, s, e, timeout) -> {
            report(p, "completedA", "failure");
            if (!e.isEmpty()) trace(e, 1, 0);
            int total = stage.equals(e.isEmpty() ? "baseline" : "instrumented") ? 2 : 1;
            return new TestResult(1, "[ERROR] Tests run: " + total + ", Failures: 1, Errors: 0, Skipped: 0\nBUILD FAILURE", "");
        }).execute(ARGUMENTS);
        noTrace(result, stage + " incomplete reports");
    }

    @Test
    void comparesOnlyUnambiguousAggregateCounts() {
        String aggregate = "[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0\n";
        DiagnosticTestRunner completed = (p, s, e, timeout) -> {
            report(p, "adds", "failure");
            if (!e.isEmpty()) trace(e, 1, 0);
            return new TestResult(1, aggregate.stripTrailing()
                    + ", Time elapsed: 0.1 s <<< FAILURE! -- in example.CalculatorTest\n" + aggregate, "");
        };
        String result = tool(completed).execute(ARGUMENTS);
        assertTrue(result.contains("Trace:"), result);
        noTrace(tool((p, s, e, timeout) -> {
            report(p, "adds", "failure");
            if (!e.isEmpty()) trace(e, 1, 0);
            return new TestResult(1, aggregate + aggregate, "");
        }).execute(ARGUMENTS), "baseline incomplete reports");
    }

    @ParameterizedTest
    @ValueSource(strings = {"report-file", "reports-directory", "target-directory"})
    void rejectsLinkedReportsOutsideTemporaryProject(String linkKind) throws Exception {
        Path probe = root.resolve("link-probe");
        try {
            Files.createSymbolicLink(probe, root.resolve(SOURCE));
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            assumeTrue(false, "Symbolic links unavailable: " + unavailable);
        }
        Files.delete(probe);
        Path external = root.resolve("stale");
        report(external, "adds", "failure");
        for (String stage : List.of("baseline", "instrumented")) {
            String result = tool((p, s, e, timeout) -> {
                if (stage.equals(e.isEmpty() ? "baseline" : "instrumented")) {
                    Path reports = p.resolve("target/surefire-reports");
                    if (linkKind.equals("target-directory")) {
                        Files.deleteIfExists(p.resolve("target"));
                        Files.createSymbolicLink(p.resolve("target"), external.resolve("target"));
                    } else if (linkKind.equals("reports-directory")) {
                        Files.createDirectories(reports.getParent());
                        Files.createSymbolicLink(reports, external.resolve("target/surefire-reports"));
                    } else {
                        Files.createDirectories(reports);
                        Files.createSymbolicLink(reports.resolve("TEST-fixture.xml"),
                                external.resolve("target/surefire-reports/TEST-fixture.xml"));
                    }
                } else {
                    report(p, "adds", "failure");
                }
                if (!e.isEmpty()) trace(e, 1, 0);
                return failed();
            }).execute(ARGUMENTS);
            noTrace(result, stage + " unsafe reports");
        }
    }

    @Test
    void distinguishesBuildFailureFromReportAmbiguity() {
        noTrace(tool((p, s, e, timeout) -> new TestResult(1, "COMPILATION ERROR", "")).execute(ARGUMENTS),
                "baseline build failure");
        noTrace(tool((p, s, e, timeout) -> {
            report(p, "adds", "failure");
            Files.writeString(p.resolve("target/surefire-reports/TEST-fixture.xml"), "<broken");
            return failed();
        }).execute(ARGUMENTS), "baseline report parse ambiguity");
    }

    @Test
    void rejectsBaselineStartFailureAndRunnerExceptions() {
        noTrace(tool((p, s, e, timeout) -> new TestResult(TestResult.EXIT_START_FAILURE, "", "")).execute(ARGUMENTS),
                "baseline execution failure");
        noTrace(tool((p, s, e, timeout) -> { throw new IOException("runner failed"); }).execute(ARGUMENTS),
                "diagnostic I/O failure");
    }

    @Test
    void checksOverallDeadlineBeforeSecondRun() {
        TraceDiagnosticTool tool = new TraceDiagnosticTool(root, tracking((p, s, e, timeout) -> {
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
        noTrace(tool((p, s, e, timeout) -> fail("must not launch Maven")).execute(arguments), "argument");
    }

    @ParameterizedTest
    @ValueSource(strings = {"../escape.java", "pom.xml", "missing.java"})
    void rejectsInvalidSourcePaths(String path) {
        var arguments = new HashMap<>(ARGUMENTS);
        arguments.put("path", path);
        noTrace(tool((p, s, e, timeout) -> fail("must not launch Maven")).execute(arguments), "source path");
    }

    @Test
    void rejectsMissingAndUnknownArguments() {
        var arguments = new HashMap<>(ARGUMENTS);
        arguments.remove("method");
        noTrace(tool((p, s, e, timeout) -> fail("must not launch Maven")).execute(arguments), "argument");
        arguments.put("unexpected", "value");
        noTrace(tool((p, s, e, timeout) -> fail("must not launch Maven")).execute(arguments), "argument");
    }

    @Test
    void rejectsAmbiguousMethodBeforeRunningTests() throws Exception {
        Files.writeString(root.resolve(SOURCE), "package example; class Calculator { int add(int x){return x;} int add(){return 1;} }");
        originalHash = hash();
        noTrace(tool((p, s, e, timeout) -> fail("must not launch Maven")).execute(ARGUMENTS), "Ambiguous method");
    }

    @Test
    void cleanupFailureOverridesOtherwiseSuccessfulTrace() {
        AtomicBoolean closed = new AtomicBoolean();
        int[] runs = {0};
        TraceDiagnosticTool tool = new TraceDiagnosticTool(root, (p, s, e, timeout) -> {
            runs[0]++;
            report(p, "adds", "failure");
            if (!e.isEmpty()) trace(e, 1, 0);
            return failed();
        }, Duration.ofMinutes(1), (project, checkpoint) -> {
            DiagnosticWorkspace workspace = DiagnosticWorkspace.create(project);
            copies.add(workspace.baselineRoot());
            copies.add(workspace.instrumentedRoot());
            return new DiagnosticWorkspaceOwner(workspace, () -> {
                workspace.close();
                closed.set(true);
                throw new IOException("injected close failure");
            });
        });
        noTrace(tool.execute(ARGUMENTS), "workspace cleanup failure");
        assertEquals(2, runs[0], "both matching test runs must complete before cleanup fails");
        assertTrue(closed.get(), "owned cleanup must execute");
    }

    @Test
    void closesWorkspaceWhenInstrumentationFailsBeforeRunnerLaunch() throws Exception {
        Files.writeString(root.resolve(SOURCE), "package example; class Calculator { int add(int x){return x;} int add(){return 1;} }");
        originalHash = hash();
        AtomicBoolean closed = new AtomicBoolean();
        TraceDiagnosticTool tool = new TraceDiagnosticTool(root, (p, s, e, timeout) -> fail("must not launch Maven"),
                Duration.ofMinutes(1), (project, checkpoint) -> {
            DiagnosticWorkspace workspace = DiagnosticWorkspace.create(project);
            copies.add(workspace.baselineRoot());
            copies.add(workspace.instrumentedRoot());
            return new DiagnosticWorkspaceOwner(workspace, () -> {
                workspace.close();
                closed.set(true);
            });
        });
        noTrace(tool.execute(ARGUMENTS), "Ambiguous method");
        assertTrue(closed.get(), "instrumentation failure must close its workspace");
    }

    @Test
    void reportsExactOverflowAndBoundsObservation() {
        String result = tool((p, s, e, timeout) -> {
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
        String result = tool((p, s, e, timeout) -> {
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsWriterFailureAfterAValidPrefix(boolean blockNewMarker) {
        String result = tool((p, s, e, timeout) -> {
            report(p, "adds", "failure");
            if (!e.isEmpty()) {
                Path status = Path.of(e.get("JAVAFIX_TRACE_FILE") + ".status");
                // Also lets the pre-fix helper demonstrate that it leaves preallocated status healthy.
                if (!Files.exists(status)) Files.write(status, new byte[]{0});
                Path fixture = p.resolve("InterruptedProbe.java");
                Files.writeString(fixture, """
                        public class InterruptedProbe {
                            public static void main(String[] args) throws Exception {
                                Probe.record("METHOD_ENTRY", 1, "", null);
                                if (args[0].equals("block")) java.nio.file.Files.createDirectory(
                                        java.nio.file.Paths.get(System.getenv("JAVAFIX_TRACE_FILE") + ".failed"));
                                Thread.currentThread().interrupt();
                                Probe.record("METHOD_ENTRY", 2, "", null);
                                if (!Thread.currentThread().isInterrupted()) throw new AssertionError("interrupt lost");
                            }
                        }
                        """ + TraceEventWriter.helperSource("Probe", SOURCE, "example.Calculator.add", 500, 160));
                assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
                        "-encoding", "UTF-8", "-d", p.toString(), fixture.toString()));
                try {
                    var run = ProcessRunner.run(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                            "-cp", p.toString(), "InterruptedProbe", blockNewMarker ? "block" : ""), p, p.resolve("probe.log"), Duration.ofSeconds(20), e);
                    assertEquals(0, run.exitCode(), run.output());
                } catch (InterruptedException ex) { throw new IOException(ex); }
                assertEquals(1, Files.readAllLines(Path.of(e.get("JAVAFIX_TRACE_FILE"))).size());
                assertEquals(24, Files.size(Path.of(e.get("JAVAFIX_TRACE_FILE") + ".state")));
                assertTrue(Files.exists(Path.of(e.get("JAVAFIX_TRACE_FILE") + ".failed")));
                assertArrayEquals(new byte[]{1}, Files.readAllBytes(status), "already-open status must record failure");
                if (blockNewMarker) {
                    // Remove the obstruction to prove rejection comes from status, not the legacy marker path.
                    Files.delete(Path.of(e.get("JAVAFIX_TRACE_FILE") + ".failed"));
                }
            }
            return failed();
        }).execute(ARGUMENTS);
        noTrace(result, blockNewMarker ? "capture status" : "capture failed");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void abortsIsolatedRunOnlyWhenNoCaptureFailureSignalCanBePersisted(boolean blockMarker) {
        String result = tool((p, s, e, timeout) -> {
            report(p, "adds", "failure");
            if (e.isEmpty()) return failed();
            Path trace = Path.of(e.get("JAVAFIX_TRACE_FILE"));
            Path status = Path.of(trace + ".status");
            Path blockedMarker = Path.of(trace + ".failed");
            Path fixture = p.resolve("UnpersistableProbe.java");
            Files.writeString(fixture, """
                    public class UnpersistableProbe {
                        public static void main(String[] args) throws Exception {
                            Probe.record("METHOD_ENTRY", 1, "", null);
                            if (args.length > 0 && args[0].equals("block")) java.nio.file.Files.createDirectory(
                                    java.nio.file.Paths.get(System.getenv("JAVAFIX_TRACE_FILE") + ".failed"));
                            java.lang.reflect.Field field = Probe.class.getDeclaredField("status");
                            field.setAccessible(true);
                            ((java.io.RandomAccessFile) field.get(null)).close();
                            Thread.currentThread().interrupt();
                            Probe.record("METHOD_ENTRY", 2, "", null);
                            if (!Thread.currentThread().isInterrupted()) throw new AssertionError("interrupt was not preserved");
                        }
                    }
                    """ + TraceEventWriter.helperSource("Probe", SOURCE, "example.Calculator.add", 500, 160));
            assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
                    "-encoding", "UTF-8", "-d", p.toString(), fixture.toString()));
            try {
                var run = ProcessRunner.run(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-cp", p.toString(), "UnpersistableProbe", blockMarker ? "block" : ""),
                        p, p.resolve("unpersistable.log"), Duration.ofSeconds(20), e);
                assertEquals(blockMarker ? 86 : 0, run.exitCode(), run.output());
                assertEquals(1, Files.readAllLines(trace).size(), "valid trace prefix should remain on disk");
                assertArrayEquals(new byte[]{0}, Files.readAllBytes(status), "failed status write should leave the preallocated zero byte");
                if (blockMarker) assertTrue(Files.isDirectory(blockedMarker));
                else assertTrue(Files.isRegularFile(blockedMarker), "secondary signal should be persisted");
                return blockMarker ? new TestResult(run.exitCode(), run.output(), "") : failed();
            } catch (InterruptedException ex) {
                throw new IOException(ex);
            }
        }).execute(ARGUMENTS);
        noTrace(result, blockMarker ? "instrumented build failure" : "capture failed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "empty", "unknown", "oversized"})
    void rejectsMissingOrMalformedCaptureStatus(String defect) {
        String result = tool((p, s, e, timeout) -> {
            report(p, "adds", "failure");
            if (!e.isEmpty()) {
                trace(e, 1, 0);
                Path status = Path.of(e.get("JAVAFIX_TRACE_FILE") + ".status");
                if (defect.equals("missing")) Files.deleteIfExists(status);
                else Files.write(status, switch (defect) {
                    case "empty" -> new byte[0];
                    case "unknown" -> new byte[]{2};
                    default -> new byte[]{0, 0};
                });
            }
            return failed();
        }).execute(ARGUMENTS);
        noTrace(result, "capture status");
    }

    @Test
    void rejectsMissingCompletionAggregate() {
        noTrace(tool((p, s, e, timeout) -> {
            report(p, "adds", "failure");
            if (!e.isEmpty()) trace(e, 1, 0);
            return new TestResult(1, "BUILD FAILURE", "");
        }).execute(ARGUMENTS), "incomplete reports");
    }

    @Test
    void refusesSelectorThatReportsTwoTestcases() {
        String result = tool((p, s, e, timeout) -> {
            report(p, "adds", "failure");
            Path report = p.resolve("target/surefire-reports/TEST-fixture.xml");
            Files.writeString(report, Files.readString(report).replace("tests=\"1\"", "tests=\"2\"")
                    .replace("</testsuite>", "<testcase classname=\"example.CalculatorTest\" name=\"other\"/></testsuite>"));
            if (!e.isEmpty()) trace(e, 1, 0);
            return new TestResult(1, "Tests run: 2, Failures: 1, Errors: 0, Skipped: 0", "");
        }).execute(ARGUMENTS);
        noTrace(result, "select exactly one failing test case");
        assertEquals(1, copies.size());
    }

    @Test
    void successfulTraceNamesItsSingleTestcase() {
        String result = tool((p, s, e, timeout) -> {
            report(p, "adds", "failure");
            if (!e.isEmpty()) trace(e, 1, 0);
            return failed();
        }).execute(ARGUMENTS);
        assertTrue(result.contains("example.CalculatorTest#adds"), result);
    }

    @Test
    void propagatesRemainingBudgetToSecondRun() {
        var clock = new java.util.concurrent.atomic.AtomicLong();
        List<Duration> timeouts = new ArrayList<>();
        TraceDiagnosticTool tool = new TraceDiagnosticTool(root, tracking((p, s, e, timeout) -> {
            timeouts.add(timeout);
            report(p, "adds", "failure");
            if (e.isEmpty()) clock.addAndGet(Duration.ofSeconds(70).toNanos());
            else trace(e, 1, 0);
            return failed();
        }), Duration.ofSeconds(90), clock::get);
        assertTrue(tool.execute(ARGUMENTS).contains("Trace:"));
        assertEquals(List.of(Duration.ofSeconds(90), Duration.ofSeconds(20)), timeouts);
    }

    @Test
    void expirationAfterBaselinePreventsAnotherLaunch() {
        var clock = new java.util.concurrent.atomic.AtomicLong();
        TraceDiagnosticTool tool = new TraceDiagnosticTool(root, tracking((p, s, e, timeout) -> {
            report(p, "adds", "failure");
            clock.set(Duration.ofMinutes(1).toNanos());
            return failed();
        }), Duration.ofMinutes(1), clock::get);
        noTrace(tool.execute(ARGUMENTS), "overall deadline");
        assertEquals(1, copies.size());
    }

    @Test
    void expirationAfterInstrumentedRunCannotReturnSuccess() {
        var clock = new java.util.concurrent.atomic.AtomicLong();
        TraceDiagnosticTool tool = new TraceDiagnosticTool(root, tracking((p, s, e, timeout) -> {
            report(p, "adds", "failure");
            if (!e.isEmpty()) {
                trace(e, 1, 0);
                clock.set(Duration.ofMinutes(1).toNanos());
            }
            return failed();
        }), Duration.ofMinutes(1), clock::get);
        noTrace(tool.execute(ARGUMENTS), "overall deadline");
        assertEquals(2, copies.size());
    }

    @Test
    void refusesOversizedSourceBeforeParsingOrLaunching() throws Exception {
        Files.writeString(root.resolve(SOURCE), Files.readString(root.resolve(SOURCE)) + " ".repeat(1024 * 1024));
        originalHash = hash();
        noTrace(tool((p, s, e, timeout) -> fail("must not launch Maven")).execute(ARGUMENTS), "source size limit");
    }

    private TraceDiagnosticTool tool(DiagnosticTestRunner runner) {
        return new TraceDiagnosticTool(root, tracking(runner), Duration.ofMinutes(3));
    }

    private DiagnosticTestRunner tracking(DiagnosticTestRunner runner) {
        return (project, selector, environment, timeout) -> {
            copies.add(project);
            assertNotEquals(root, project);
            return runner.run(project, selector, environment, timeout);
        };
    }

    private static TestResult failed() { return new TestResult(1, "Tests run: 1, Failures: 1, Errors: 0, Skipped: 0\nBUILD FAILURE", ""); }

    private static void report(Path project, String identity, String outcome) throws IOException {
        Path directory = project.resolve("target/surefire-reports");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("TEST-fixture.xml"), "<testsuite tests=\"1\" failures=\"" + (outcome.equals("failure") ? 1 : 0)
                + "\" errors=\"" + (outcome.equals("error") ? 1 : 0) + "\" skipped=\"0\"><testcase classname=\"example.CalculatorTest\" name=\""
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
