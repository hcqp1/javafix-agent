package com.javafix.agent.tool;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javafix.agent.core.TestResult;

import java.io.DataInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.function.LongSupplier;

/** Compares isolated test runs before exposing diagnostic-only runtime evidence. */
public final class TraceDiagnosticTool extends ProjectTool {
    private static final int EVENT_LIMIT = 500;
    private static final int VALUE_LIMIT = 160;
    private static final int OBSERVATION_LIMIT = 3500;
    private static final Set<String> EVENT_KINDS = Set.of(
            "METHOD_ENTRY", "LOCAL_DECLARATION", "ASSIGNMENT", "BRANCH_THEN", "BRANCH_ELSE");
    private static final Pattern COMPLETED_TEST_TOTAL = Pattern.compile(
            "(?m)^(?:\\[(?:INFO|WARNING|ERROR)]\\s*)?Tests run:\\s*(\\d+),\\s*Failures:\\s*\\d+,"
                    + "\\s*Errors:\\s*\\d+,\\s*Skipped:\\s*\\d+\\s*$");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final DiagnosticTestRunner runner;
    private final Duration deadline;
    private final DiagnosticWorkspaceFactory workspaces;
    private final LongSupplier nanoTime;

    public TraceDiagnosticTool(MavenTestRunner runner, Path root) {
        this(root, runner::run, Duration.ofMinutes(10));
    }

    TraceDiagnosticTool(Path root, DiagnosticTestRunner runner, Duration deadline) {
        this(root, runner, deadline, System::nanoTime);
    }

    TraceDiagnosticTool(Path root, DiagnosticTestRunner runner, Duration deadline, LongSupplier nanoTime) {
        this(root, runner, deadline, (project, checkpoint) -> {
            DiagnosticWorkspace workspace = DiagnosticWorkspace.create(project, checkpoint);
            return new DiagnosticWorkspaceOwner(workspace, workspace::close);
        }, nanoTime);
    }

    TraceDiagnosticTool(Path root, DiagnosticTestRunner runner, Duration deadline, DiagnosticWorkspaceFactory workspaces) {
        this(root, runner, deadline, workspaces, System::nanoTime);
    }

    TraceDiagnosticTool(Path root, DiagnosticTestRunner runner, Duration deadline, DiagnosticWorkspaceFactory workspaces, LongSupplier nanoTime) {
        super(root);
        if (deadline.isNegative() || deadline.isZero()) throw new IllegalArgumentException("deadline must be positive");
        this.runner = runner;
        this.deadline = deadline;
        this.workspaces = workspaces;
        this.nanoTime = nanoTime;
    }

    @Override public String name() { return "diagnose_trace"; }

    @Override public String description() {
        return "Run a focused failing Maven test twice in disposable copies and return bounded runtime evidence only"
                + " when exactly one failing test case has the same identity and outcome in both runs. Required arguments: path (Java source), class,"
                + " method (unambiguous name), test (focused selector, e.g. CalculatorTest#adds).";
    }

    @Override public String execute(Map<String, String> arguments) {
        long started = nanoTime.getAsLong();
        try {
            rejectUnknownArguments(arguments, "path", "class", "method", "test");
            for (String required : List.of("path", "class", "method", "test")) {
                if (arguments.get(required) == null || arguments.get(required).isBlank()) {
                    throw new IllegalArgumentException(required + " is required");
                }
            }
            if (!arguments.get("test").matches("[A-Za-z0-9_.$#,*?-]+")) {
                throw new IllegalArgumentException("unsafe test selector");
            }
        } catch (IllegalArgumentException e) {
            return failure("invalid argument", e.getMessage());
        }

        Path source;
        try {
            source = resolve(arguments.get("path"));
            if (!source.getFileName().toString().endsWith(".java")
                    || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                    || !source.toRealPath().startsWith(root.toRealPath())) {
                return failure("invalid source path", "Expected a regular Java file inside the project");
            }
            // Copies omit links; reject linked components even when their destination is inside the project.
            for (Path component = source; component != null && component.startsWith(root); component = component.getParent()) {
                if (Files.isSymbolicLink(component)) return failure("invalid source path", "Symbolic links are unsupported");
            }
        } catch (IllegalArgumentException | IOException e) {
            return failure("invalid source path", e.getMessage());
        }

        DiagnosticWorkspaceOwner owner;
        try {
            checkDeadline(started);
            owner = workspaces.create(root, () -> checkDeadline(started));
        } catch (DeadlineExceeded e) {
            return failure(e.getSuppressed().length > 0 ? "workspace cleanup failure" : "overall deadline", e.getMessage());
        } catch (IOException e) {
            return failure(e.getSuppressed().length > 0 ? "workspace cleanup failure" : "workspace creation failure", e.getMessage());
        }
        try (owner) {
            try {
                checkDeadline(started);
                String result = diagnose(owner.workspace(), source, arguments, started);
                checkDeadline(started);
                return result;
            } catch (JavaSourceInstrumenter.InstrumentationException e) {
                return failure("source parse ambiguity or unsupported instrumentation", e.getMessage());
            } catch (IOException e) {
                return failure("diagnostic I/O failure", e.getMessage());
            } catch (DeadlineExceeded e) {
                return failure("overall deadline", e.getMessage());
            } catch (RuntimeException e) {
                return failure("diagnostic execution failure", e.getMessage());
            }
        } catch (IOException e) {
            // A close failure replaces even a successful observation, so leaked workspaces never report success.
            return failure("workspace cleanup failure", e.getMessage());
        }
    }

    private String diagnose(DiagnosticWorkspace workspace, Path source, Map<String, String> arguments, long started)
            throws IOException, JavaSourceInstrumenter.InstrumentationException {
        Path relative = root.relativize(source);
        String sourceName = relative.toString().replace('\\', '/');
        String method = arguments.get("class") + "." + arguments.get("method");
        String helper = "JavafixTrace" + UUID.randomUUID().toString().replace("-", "");
        Path temporarySource = workspace.instrumentedRoot().resolve(relative);
        checkDeadline(started);
        var instrumented = new JavaSourceInstrumenter().instrument(temporarySource,
                arguments.get("class"), arguments.get("method"), helper);
        checkDeadline(started);
        Files.writeString(temporarySource, instrumented.source() + "\n"
                + TraceEventWriter.helperSource(helper, sourceName, method, EVENT_LIMIT, VALUE_LIMIT));

        if (expired(started)) return failure("overall deadline", "No time remains for baseline tests");
        TestResult baseline = runner.run(workspace.baselineRoot(), arguments.get("test"), Map.of(), remainingBudget(started));
        String runFailure = runFailure("baseline", baseline);
        if (runFailure != null) return runFailure;
        if (expired(started)) return failure("overall deadline", "Baseline exhausted the diagnostic budget");
        TestOutcomeSignature before;
        try {
            before = readSignature(workspace.baselineRoot(), "baseline", baseline, () -> checkDeadline(started));
        } catch (IOException e) {
            return e.getMessage();
        }
        checkDeadline(started);
        if (before.entries().size() != 1) {
            return failure("select exactly one failing test case", "The selector must report exactly one testcase");
        }
        if (before.entries().stream().noneMatch(entry -> entry.outcome() == TestOutcomeSignature.Outcome.FAILED
                || entry.outcome() == TestOutcomeSignature.Outcome.ERROR)) {
            return failure("baseline has no FAILED/ERROR tests", "select exactly one failing test case");
        }
        if (baseline.isSuccess()) return failure("baseline build failure", "Exit status contradicts failing reports");

        Path target = workspace.instrumentedRoot().resolve("target");
        Files.createDirectories(target);
        Path traceFile = target.resolve("javafix-trace-" + UUID.randomUUID() + ".jsonl");
        Files.deleteIfExists(traceFile);
        Files.deleteIfExists(Path.of(traceFile + ".state"));
        Files.deleteIfExists(Path.of(traceFile + ".failed"));
        Path statusFile = Path.of(traceFile + ".status");
        Files.deleteIfExists(statusFile);
        Files.write(statusFile, new byte[]{0}, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        if (expired(started)) return failure("overall deadline", "No time remains for instrumented tests");
        TestResult traced = runner.run(workspace.instrumentedRoot(), arguments.get("test"),
                Map.of("JAVAFIX_TRACE_FILE", traceFile.toString()), remainingBudget(started));
        runFailure = runFailure("instrumented", traced);
        if (runFailure != null) return runFailure;
        if (expired(started)) return failure("overall deadline", "Instrumented run exhausted the diagnostic budget");
        TestOutcomeSignature after;
        try {
            after = readSignature(workspace.instrumentedRoot(), "instrumented", traced, () -> checkDeadline(started));
        } catch (IOException e) {
            return e.getMessage();
        }
        checkDeadline(started);
        if (!before.matches(after)) return failure("per-test parity mismatch", "Test identities or outcomes changed; trace discarded");
        if (traced.isSuccess()) return failure("instrumented build failure", "Exit status contradicts failing reports");
        try {
            checkDeadline(started);
            Trace trace = readTrace(traceFile, sourceName, method);
            checkDeadline(started);
            return summarize(trace, before.entries().get(0), sourceName, method, instrumented.skippedProbes().size());
        } catch (IOException | IllegalArgumentException e) {
            return failure("trace unavailable", e.getMessage());
        }
    }

    private boolean expired(long started) { return nanoTime.getAsLong() - started >= deadline.toNanos(); }

    private Duration remainingBudget(long started) {
        long remaining = deadline.toNanos() - (nanoTime.getAsLong() - started);
        if (remaining <= 0) throw new DeadlineExceeded();
        return Duration.ofNanos(remaining).compareTo(Duration.ofMinutes(5)) < 0
                ? Duration.ofNanos(remaining) : Duration.ofMinutes(5);
    }

    private void checkDeadline(long started) {
        if (expired(started)) throw new DeadlineExceeded();
    }

    private static final class DeadlineExceeded extends RuntimeException {
        DeadlineExceeded() { super("Diagnostic time budget exhausted"); }
    }

    private static String runFailure(String stage, TestResult result) {
        if (result.getExitCode() == TestResult.EXIT_TIMEOUT) return failure(stage + " timeout", "Tests did not finish");
        if (result.getExitCode() == TestResult.EXIT_START_FAILURE) return failure(stage + " execution failure", "Tests did not start or were interrupted");
        String output = output(result).toLowerCase(Locale.ROOT);
        for (String marker : List.of("the forked vm terminated without properly saying goodbye",
                "there was an error in the forked process", "surefirebooterforkexception",
                "error occurred in starting fork", "there was a timeout in the fork")) {
            if (output.contains(marker)) return failure(stage + " infrastructure failure", "Surefire fork did not complete normally");
        }
        if (result.getExitCode() < 0 || result.getExitCode() > 1
                || (result.getStdout() != null && result.getStdout().contains("COMPILATION ERROR"))) {
            return failure(stage + " build failure", "No trustworthy completed test run");
        }
        return null;
    }

    private static String output(TestResult result) {
        return (result.getStdout() == null ? "" : result.getStdout()) + "\n"
                + (result.getStderr() == null ? "" : result.getStderr());
    }

    private static TestOutcomeSignature readSignature(Path project, String stage, TestResult result, Runnable checkpoint) throws IOException {
        checkpoint.run();
        Path reports = project.resolve("target/surefire-reports");
        Path realProject = project.toRealPath();
        for (Path component = reports; component != null && component.startsWith(project); component = component.getParent()) {
            validateReportPath(component, realProject, stage);
        }
        boolean hasReports = false;
        if (Files.isDirectory(reports, LinkOption.NOFOLLOW_LINKS)) {
            try (var files = Files.walk(reports)) {
                for (var iterator = files.iterator(); iterator.hasNext();) {
                    checkpoint.run();
                    Path path = iterator.next();
                    validateReportPath(path, realProject, stage);
                    if (path.toString().endsWith(".dump") || path.toString().endsWith(".dumpstream")) {
                        throw new IOException(failure(stage + " infrastructure failure", "Surefire dump artifact found"));
                    }
                    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && path.toString().endsWith(".xml")) hasReports = true;
                }
            }
        }
        if (!hasReports) throw new IOException(failure(stage + " missing reports", "No Surefire XML reports were produced"));
        try {
            TestOutcomeSignature signature = TestOutcomeSignature.read(reports, checkpoint);
            checkpoint.run();
            var totals = COMPLETED_TEST_TOTAL.matcher(output(result));
            if (!totals.find()) {
                throw new IOException(failure(stage + " incomplete reports", "Missing recognized completed Maven aggregate"));
            } else {
                // Per-class lines have a timing/name suffix; only an unadorned aggregate is comparable.
                String count = totals.group(1);
                if (!count.equals(Integer.toString(signature.entries().size())) || totals.find()) {
                    throw new IOException(failure(stage + " incomplete reports", "Completed Maven total is ambiguous or differs from XML testcase count"));
                }
            }
            return signature;
        } catch (TestOutcomeSignature.ParseException e) {
            throw new IOException(failure(stage + " report parse ambiguity", e.getMessage()), e);
        }
    }

    private static void validateReportPath(Path path, Path realProject, String stage) throws IOException {
        if (Files.isSymbolicLink(path) || (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                && !path.toRealPath().startsWith(realProject))) {
            throw new IOException(failure(stage + " unsafe reports", "Linked or external Surefire reports are unsupported"));
        }
    }

    private static Trace readTrace(Path file, String source, String method) throws IOException {
        if (Files.exists(Path.of(file + ".failed"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("capture failed; trace discarded");
        }
        Path statusFile = Path.of(file + ".status");
        if (!Files.isRegularFile(statusFile, LinkOption.NOFOLLOW_LINKS) || Files.size(statusFile) != 1) {
            throw new IOException("capture status missing or malformed; trace discarded");
        }
        byte status = Files.readAllBytes(statusFile)[0];
        if (status != 0) throw new IOException("capture status records failure; trace discarded");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 1024 * 1024) {
            throw new IOException("Missing or oversized trace");
        }
        String text = Files.readString(file);
        if (text.isEmpty() || !text.endsWith("\n")) throw new IOException("Empty or incomplete JSONL trace");
        List<JsonNode> events = new ArrayList<>();
        long omitted = 0;
        long end = 0;
        int sequence = 0;
        for (String line : text.substring(0, text.length() - 1).split("\n", -1)) {
            JsonNode event = JSON.readTree(line);
            if (event == null || !event.isObject() || !event.path("sequence").isIntegralNumber()
                    || !event.path("sequence").canConvertToLong()
                    || event.path("sequence").asLong() != ++sequence || omitted > 0) {
                throw new IOException("Incomplete or out-of-order trace");
            }
            if (event.path("kind").asText().equals("overflow")) {
                if (events.size() != EVENT_LIMIT || !event.path("omitted").canConvertToLong()
                        || !event.path("omitted").isIntegralNumber() || event.path("omitted").asLong() <= 0) {
                    throw new IOException("Invalid trace overflow record");
                }
                omitted = event.path("omitted").asLong();
            } else {
                if (events.size() >= EVENT_LIMIT || !event.path("source").asText().equals(source)
                        || !event.path("method").asText().equals(method) || !EVENT_KINDS.contains(event.path("kind").asText())
                        || !event.path("line").isIntegralNumber() || !event.path("line").canConvertToInt()
                        || event.path("line").asInt() <= 0 || !event.path("variable").isTextual()
                        || !(event.path("value").isNull() || event.path("value").isTextual())
                        || (event.path("value").isTextual() && event.path("value").asText().length() > VALUE_LIMIT)
                        || !event.path("truncated").isBoolean()) {
                    throw new IOException("Malformed trace event");
                }
                events.add(event);
                end += (line + "\n").getBytes(StandardCharsets.UTF_8).length;
            }
        }
        // The sidecar detects a valid-JSON prefix left by an interrupted or failed write.
        Path stateFile = Path.of(file + ".state");
        if (events.isEmpty() || !Files.isRegularFile(stateFile, LinkOption.NOFOLLOW_LINKS) || Files.size(stateFile) != 24) {
            throw new IOException("Incomplete trace state");
        }
        try (var state = new DataInputStream(Files.newInputStream(stateFile))) {
            if (state.readLong() != events.size() || state.readLong() != omitted || state.readLong() != end) {
                throw new IOException("Trace/state mismatch");
            }
        }
        return new Trace(events, omitted);
    }

    private static String summarize(Trace trace, TestOutcomeSignature.Entry test, String source, String method, int skipped) {
        StringBuilder result = new StringBuilder("Diagnostic success: per-test parity matched (1 test). ");
        result.append(trace.events().size()).append(" events captured; ").append(skipped).append(" unsupported probes skipped.\n");
        if (trace.omitted() > 0) result.append("trace overflow: ").append(trace.omitted()).append(" events omitted.\n");
        result.append("Test: ").append(shorten(test.className() + "#" + test.testName(), 300)).append('\n');
        result.append("Trace: ").append(shorten(source, 180)).append(" :: ").append(shorten(method, 180)).append('\n');
        int shown = 0;
        for (JsonNode event : trace.events()) {
            String row = "#" + event.path("sequence").asLong() + " L" + event.path("line").asInt()
                    + " " + event.path("kind").asText() + (event.path("variable").asText().isEmpty() ? ""
                    : " " + shorten(event.path("variable").asText(), 100) + "=" + event.path("value"))
                    + (event.path("truncated").asBoolean() ? " [value truncated]" : "") + "\n";
            if (result.length() + row.length() > OBSERVATION_LIMIT - 80) break;
            result.append(row);
            shown++;
        }
        if (shown < trace.events().size()) result.append("display omitted: ").append(trace.events().size() - shown).append(" captured events.\n");
        return result.toString();
    }

    private static String failure(String reason, String detail) {
        return "Diagnostic unavailable: " + reason + ". " + shorten(detail == null ? "" : detail, 600);
    }

    private static String shorten(String text, int limit) { return text.length() <= limit ? text : text.substring(0, limit) + "…"; }
    private record Trace(List<JsonNode> events, long omitted) { }
}

@FunctionalInterface
interface DiagnosticTestRunner {
    TestResult run(Path project, String selector, Map<String, String> environment, Duration timeout) throws IOException;
}

@FunctionalInterface
interface DiagnosticWorkspaceFactory {
    DiagnosticWorkspaceOwner create(Path project, Runnable checkpoint) throws IOException;
}

record DiagnosticWorkspaceOwner(DiagnosticWorkspace workspace, Closeable cleanup) implements AutoCloseable {
    @Override public void close() throws IOException { cleanup.close(); }
}
