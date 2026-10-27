package com.javafix.agent.tool;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javafix.agent.core.TestResult;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Compares isolated test runs before exposing diagnostic-only runtime evidence. */
public final class TraceDiagnosticTool extends ProjectTool {
    private static final int EVENT_LIMIT = 500;
    private static final int VALUE_LIMIT = 160;
    private static final int OBSERVATION_LIMIT = 3500;
    private static final Set<String> EVENT_KINDS = Set.of(
            "METHOD_ENTRY", "LOCAL_DECLARATION", "ASSIGNMENT", "BRANCH_THEN", "BRANCH_ELSE");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final DiagnosticTestRunner runner;
    private final Duration deadline;

    public TraceDiagnosticTool(MavenTestRunner runner, Path root) {
        this(root, runner::run, Duration.ofMinutes(10));
    }

    TraceDiagnosticTool(Path root, DiagnosticTestRunner runner, Duration deadline) {
        super(root);
        if (deadline.isNegative() || deadline.isZero()) throw new IllegalArgumentException("deadline must be positive");
        this.runner = runner;
        this.deadline = deadline;
    }

    @Override public String name() { return "diagnose_trace"; }

    @Override public String description() {
        return "Run a focused failing Maven test twice in disposable copies and return bounded runtime evidence only"
                + " when all per-test identities and outcomes match. Required arguments: path (Java source), class,"
                + " method (unambiguous name), test (focused selector, e.g. CalculatorTest#adds).";
    }

    @Override public String execute(Map<String, String> arguments) {
        long started = System.nanoTime();
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

        DiagnosticWorkspace workspace;
        try {
            workspace = DiagnosticWorkspace.create(root);
        } catch (IOException e) {
            return failure(e.getSuppressed().length > 0 ? "workspace cleanup failure" : "workspace creation failure", e.getMessage());
        }
        try (workspace) {
            try {
                return diagnose(workspace, source, arguments, started);
            } catch (JavaSourceInstrumenter.InstrumentationException e) {
                return failure("source parse ambiguity or unsupported instrumentation", e.getMessage());
            } catch (IOException e) {
                return failure("diagnostic I/O failure", e.getMessage());
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
        var instrumented = new JavaSourceInstrumenter().instrument(temporarySource,
                arguments.get("class"), arguments.get("method"), helper);
        Files.writeString(temporarySource, instrumented.source() + "\n"
                + TraceEventWriter.helperSource(helper, sourceName, method, EVENT_LIMIT, VALUE_LIMIT));

        if (expired(started)) return failure("overall deadline", "No time remains for baseline tests");
        TestResult baseline = runner.run(workspace.baselineRoot(), arguments.get("test"), Map.of());
        String runFailure = runFailure("baseline", baseline);
        if (runFailure != null) return runFailure;
        if (expired(started)) return failure("overall deadline", "Baseline exhausted the diagnostic budget");
        TestOutcomeSignature before;
        try {
            before = readSignature(workspace.baselineRoot(), "baseline");
        } catch (IOException e) {
            return e.getMessage();
        }
        if (before.entries().stream().noneMatch(entry -> entry.outcome() == TestOutcomeSignature.Outcome.FAILED
                || entry.outcome() == TestOutcomeSignature.Outcome.ERROR)) {
            return failure("baseline has no FAILED/ERROR tests", "Select a reproducibly failing test");
        }
        if (baseline.isSuccess()) return failure("baseline build failure", "Exit status contradicts failing reports");

        Path target = workspace.instrumentedRoot().resolve("target");
        Files.createDirectories(target);
        Path traceFile = target.resolve("javafix-trace-" + UUID.randomUUID() + ".jsonl");
        Files.deleteIfExists(traceFile);
        Files.deleteIfExists(Path.of(traceFile + ".state"));
        if (expired(started)) return failure("overall deadline", "No time remains for instrumented tests");
        TestResult traced = runner.run(workspace.instrumentedRoot(), arguments.get("test"),
                Map.of("JAVAFIX_TRACE_FILE", traceFile.toString()));
        runFailure = runFailure("instrumented", traced);
        if (runFailure != null) return runFailure;
        if (expired(started)) return failure("overall deadline", "Instrumented run exhausted the diagnostic budget");
        TestOutcomeSignature after;
        try {
            after = readSignature(workspace.instrumentedRoot(), "instrumented");
        } catch (IOException e) {
            return e.getMessage();
        }
        if (!before.matches(after)) return failure("per-test parity mismatch", "Test identities or outcomes changed; trace discarded");
        if (traced.isSuccess()) return failure("instrumented build failure", "Exit status contradicts failing reports");
        try {
            Trace trace = readTrace(traceFile, sourceName, method);
            return summarize(trace, before.entries().size(), sourceName, method, instrumented.skippedProbes().size());
        } catch (IOException | IllegalArgumentException e) {
            return failure("trace unavailable", e.getMessage());
        }
    }

    private boolean expired(long started) { return System.nanoTime() - started >= deadline.toNanos(); }

    private static String runFailure(String stage, TestResult result) {
        if (result.getExitCode() == TestResult.EXIT_TIMEOUT) return failure(stage + " timeout", "Tests did not finish");
        if (result.getExitCode() == TestResult.EXIT_START_FAILURE) return failure(stage + " execution failure", "Tests did not start or were interrupted");
        if (result.getExitCode() < 0 || result.getExitCode() > 1
                || (result.getStdout() != null && result.getStdout().contains("COMPILATION ERROR"))) {
            return failure(stage + " build failure", "No trustworthy completed test run");
        }
        return null;
    }

    private static TestOutcomeSignature readSignature(Path project, String stage) throws IOException {
        Path reports = project.resolve("target/surefire-reports");
        boolean hasReports = false;
        if (Files.isDirectory(reports)) {
            try (var files = Files.walk(reports)) {
                hasReports = files.anyMatch(path -> Files.isRegularFile(path) && path.toString().endsWith(".xml"));
            }
        }
        if (!hasReports) throw new IOException(failure(stage + " missing reports", "No Surefire XML reports were produced"));
        try {
            return TestOutcomeSignature.read(reports);
        } catch (TestOutcomeSignature.ParseException e) {
            throw new IOException(failure(stage + " report parse ambiguity", e.getMessage()), e);
        }
    }

    private static Trace readTrace(Path file, String source, String method) throws IOException {
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

    private static String summarize(Trace trace, int tests, String source, String method, int skipped) {
        StringBuilder result = new StringBuilder("Diagnostic success: per-test parity matched (" + tests + " tests). ");
        result.append(trace.events().size()).append(" events captured; ").append(skipped).append(" unsupported probes skipped.\n");
        if (trace.omitted() > 0) result.append("trace overflow: ").append(trace.omitted()).append(" events omitted.\n");
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
    TestResult run(Path project, String selector, Map<String, String> environment) throws IOException;
}
