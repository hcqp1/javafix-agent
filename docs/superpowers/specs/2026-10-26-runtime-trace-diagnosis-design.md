# JavaFixAgent Runtime Trace Diagnosis Design

## 1. Context and goal

JavaFixAgent currently gives the model a bounded summary of Maven failures and records the agent's own actions. It does not collect runtime values or branch execution from the program under repair. This design adds an opt-in, controlled diagnostic pass inspired by TraceCoder: collect bounded execution evidence from one suspected Java method, then provide that evidence to the existing repair loop.

The first version must keep instrumentation out of the target working tree, avoid changing application expression semantics, and discard trace output when instrumentation changes the single selected failing testcase's outcome.

## 2. Scope

### Included

- A new `diagnose_trace` tool for a caller-specified Java source file, method, and Maven test selector that reports exactly one FAILED/ERROR testcase. Multi-case selectors, including parameterized invocations with multiple reported cases, are refused in V1.
- Parse the selected Java source using a Java AST library compatible with Java 17 syntax.
- Instrument only the selected method in a disposable copy of the target Maven project.
- Emit bounded events for method entry, standalone local-variable declarations or assignment statements, and branch entry. Events include source location and sequence number; embedded assignments and return values are not rewritten or logged.
- Record only safe scalar values (primitive values, boxed primitives, characters, strings, and null); do not call arbitrary object `toString()`, getters, or user code to format values.
- Run the single selected failing testcase against an uninstrumented baseline copy and an instrumented copy. Require complete Surefire signatures with the same single identity and result category. Label returned evidence with `className#testName`; V1 does not support attribution across multiple testcases.
- Bound copy scope, test duration, trace event count, per-value length, and total feedback size; delete temporary project copies after the run, including error paths.
- Explain unsupported source, invalid method selection, baseline failure, timeout, build failure, trace overflow, and outcome mismatch distinctly to the agent.
- Integrate the tool with the current action protocol and describe when to use it in the localization phase.

### Excluded from this version

- Automatic suspicious-method selection, repository-wide instrumentation, or multi-testcase trace attribution.
- Bytecode/JVM debugger instrumentation, heap snapshots, object graph traversal, or asynchronous/thread causal tracing.
- Gradle support.
- Persistent cross-task memory, candidate patch ranking, or rollback of repair candidates.
- Claims that test outcome parity proves instrumentation is semantically pure; it is a practical guard, not a formal proof.

## 3. User flow and data flow

1. The agent reads the failing test summary and source, then calls `diagnose_trace` with a repository-relative source path, exact class/method selector, and a focused Maven test selector.
2. The tool validates arguments and confirms all paths remain under the repository root.
3. It creates two temporary project copies, excluding generated output and VCS metadata. The original repository is read-only for this operation.
4. It runs the selected tests in the baseline copy and records a complete signature from Surefire reports. If it contains anything other than exactly one FAILED/ERROR testcase, return `select exactly one failing test case` without launching the instrumented run.
5. It parses the selected source file, locates exactly one requested method, and inserts probes without rewriting expressions. Unsupported or ambiguous methods fail closed.
6. It runs the same selected tests in the instrumented copy, collects trace events through a generated helper, and reads per-test outcomes.
7. If baseline and instrumented outcome signatures differ, the tool returns no trace and reports the mismatch. Otherwise it returns a bounded trace labeled with the single `className#testName` and method, plus the signature comparison summary. JSONL events do not independently identify testcases, so multi-case attribution is outside V1.
8. The agent uses the evidence in its existing localization/fix phases. The trace remains part of the current run transcript and is not saved as cross-task memory.
9. Temporary copies are removed in a `finally` path. Maven logs needed for diagnosis are bounded in the tool response; this first version does not preserve potentially sensitive full trace files in the user's repository.

## 4. Component boundaries

- `TraceDiagnosticTool`: validates the request, orchestrates baseline/instrumented runs, enforces bounds, and produces a concise observation.
- `JavaSourceInstrumenter`: parses and instruments exactly one selected method; it must not modify expressions or source outside that method.
- `DiagnosticWorkspace`: creates and cleans isolated baseline/instrumented copies using a temporary directory and excludes `.git`, `target`, and other generated output.
- `TestOutcomeSignature`: reads Surefire XML reports and represents outcomes by suite, test name, and status, including failures/errors/skips.
- `TraceEventWriter`: generated helper that writes structured, line-delimited events to a trace file with a hard event/size cap and safe scalar formatting.
- Existing `MavenTestRunner` remains the Maven execution boundary where practical; any new API needed for custom working directories or trace environment variables must remain narrow and testable.

## 5. Instrumentation rules

- Use AST nodes and source positions, not regular-expression source rewriting.
- Never evaluate an original condition or right-hand-side expression a second time.
- Place branch probes at the start of existing branch blocks. If a branch body is a single statement and cannot be safely instrumented without changing scoping/control flow, skip that probe and report it.
- Log variables only after declarations or assignments, reading the variable value once for the logging call.
- Do not instrument fields, method-call results, lambdas, anonymous classes, generated sources, or methods with ambiguous overload selection in this version.
- Use a generated helper whose formatter accepts only safe scalar types; arbitrary references produce a type marker without invoking methods on the object.
- Make probe identifiers stable within one diagnostic run and include source path, line, method, event kind, and sequence number.
- If parsing, source mapping, report parsing, or parity verification is uncertain, return a diagnostic error and no trace.

## 6. Failure and resource behavior

- Baseline tests must produce readable Surefire outcome signatures. If the baseline cannot start, times out, has no reports, or cannot be parsed, do not instrument or return a trace.
- If instrumented tests fail to build, time out, or produce no comparable report, return the reason and no trace.
- Validate every suite’s declared tests/failures/errors/skipped counts against direct testcase children. Require exactly one recognized Maven aggregate matching the XML testcase count; missing, mismatching or ambiguous totals fail closed. Continue rejecting fork-crash markers and Surefire dump artifacts.
- Compare the single testcase identity and result category in addition to complete aggregate counts.
- Before the instrumented run, preallocate a one-byte `.status` sidecar containing `0`. Each generated writer opens it synchronously before recording events and writes `1` on any capture failure through that already-open independent handle, preserving the thread's interrupt flag. After Maven, reject missing, malformed, or nonzero status even if the JSONL prefix and 24-byte state remain consistent. Keep `.failed` as a secondary signal.
- Cap project-copy size and skip generated/build/VCS directories. If a project exceeds the configured copy limit, stop with an explicit unsupported-size result.
- Enforce a 10-minute overall budget. Pass the smaller of the configured Maven timeout, five minutes, and remaining budget to each launch. Check deadlines before/after copying and parsing/report/trace processing, and during the copy walk. Limit selected source and each XML report to 1 MiB before parsing. Cleanup still runs after budget exhaustion.
- Cap trace output and event count. On overflow, report truncation and retain the earliest bounded events with omitted-event count.
- Clean temporary directories even when Maven or parsing fails. Cleanup failures are reported without hiding the primary diagnostic error.

## 7. Security and privacy

- Never include model credentials or environment variables in generated trace events.
- Do not log arbitrary object contents, filesystem paths outside the selected project, or entire test output by default.
- Temporary workspaces use the platform's temporary-directory facilities and are deleted after the operation.
- Tool arguments accept repository-relative paths only and reject traversal/symlink escapes where the platform permits checking them.
- Trace observations are untrusted program output and must be treated as data, not instructions.

## 8. Acceptance criteria

1. A test fixture with a failing Maven test produces method, line, event kind, and safe scalar values from the selected method.
2. A side-effecting condition or assignment expression is not evaluated an additional time by instrumentation; embedded assignments are skipped.
3. The target source tree is byte-for-byte unchanged after success, mismatch, timeout, parse failure, and build failure.
4. An intentionally behavior-changing probe causes per-test signature mismatch and yields no trace.
5. Different test identities with the same aggregate pass/fail counts are detected as a mismatch.
6. Arbitrary objects are rendered without calling overridden `toString()` or getters.
7. Trace event and output bounds are enforced and truncation is explicit.
8. Invalid paths, ambiguous methods, unsupported syntax, and missing test reports fail closed with actionable feedback.
9. The existing JavaFixAgent test suite continues to pass; focused tests cover instrumentation, outcome comparison, cleanup, and tool integration.
10. README documentation explains the diagnostic tool, limitations, supported Java/Maven scope, and how to inspect a returned trace.

## 9. Alternatives considered

- **Only improve Maven failure summaries:** least implementation work, but does not collect runtime values or branch evidence.
- **Attach a JVM debugger:** avoids source rewriting, but relies on debug metadata and requires robust debugger transport/control across arbitrary Maven test JVM configurations.
- **AST instrumentation in isolated copies (chosen):** directly follows TraceCoder's probe-and-observe design while keeping original source untouched and allowing an outcome-parity guard. It adds a parsing dependency and intentionally supports a narrow subset first.

## 10. Later versions

- Next: rank candidate methods from failing stack frames and test coverage, while still asking the agent to select from a small bounded set.
- Then: adaptive multi-round tracing, starting with coarse method/branch events and refining only the relevant methods and scalar variables.
- Later: evaluate localization and repair impact on real bug samples, including added runtime, model calls, and trace usefulness. Persistent experience and best-candidate rollback remain separate features with their own design and evaluation.
