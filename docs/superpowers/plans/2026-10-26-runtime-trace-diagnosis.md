# JavaFixAgent Runtime Trace Diagnosis Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a bounded `diagnose_trace` tool that captures safe runtime values and branch execution for one selected Java method in a temporary Maven project copy, returning a trace labeled `className#testName` only when exactly one reported FAILED/ERROR testcase matches the uninstrumented run by identity and category. V1 refuses selectors reporting multiple cases and does not attribute events across testcases.

**Architecture:** Keep orchestration in `TraceDiagnosticTool`; isolate project copying, AST instrumentation, test outcome signatures, trace writing, and Maven process options behind focused components. The target repository stays unchanged. Both baseline and instrumented runs use the same focused test selector, and the tool fails closed if it cannot prove per-test outcome parity.

**Tech Stack:** Java 17, Maven, JavaParser Core 3.26.3, JUnit 5, JDK XML APIs with external entities disabled.

## Global Constraints

- The supported target is Java 17 source in a Maven project; Gradle is out of scope.
- Instrument only one caller-selected source file and method per tool call.
- Never re-evaluate an original condition or right-hand-side expression; skip embedded assignments and any AST shape that cannot be instrumented safely.
- Keep all probe edits inside a temporary instrumented copy; do not write to the target project.
- Return trace data only when baseline and instrumented Surefire signatures match by test identity and result category.
- Record only safe scalar values; arbitrary objects must not invoke `toString()`, getters, or application methods.
- Fail closed on uncertain parsing, source mapping, reports, paths, cleanup, or parity.
- Bound project copy size, Maven runtime, trace events, scalar length, and tool response; delete temporary copies on every exit path.
- Treat test output and trace content as untrusted data.

---

### Task 1: Define trace request and outcome signature

**Files:**
- Create: `src/main/java/com/javafix/agent/tool/TestOutcomeSignature.java`
- Test: `src/test/java/com/javafix/agent/tool/TestOutcomeSignatureTest.java`
- Create: `src/test/resources/surefire/one-failure/TEST-com.example.CalculatorTest.xml`
- Create: `src/test/resources/surefire/renamed-test/TEST-com.example.OtherTest.xml`

**Interfaces:**
- `TestOutcomeSignature.read(Path reportsDirectory)` returns an immutable signature or a typed parse failure.
- Signature entries are sorted records `(className, testName, outcome)` where `outcome` is `PASSED`, `FAILED`, `ERROR`, or `SKIPPED`.
- `matches(TestOutcomeSignature other)` is true only when the complete sorted entry lists are equal; aggregate counts are insufficient.

- [ ] **Step 1: Write the failing signature tests**

Create JUnit tests that load the fixture reports and assert the parsed failing entry is `("com.example.CalculatorTest", "shouldAddTwoNumbers", FAILED)`. Add a second report set with equal aggregate counts but a different test identity, and assert `matches` returns false. Assert malformed XML and an empty report directory produce a parse failure.

- [ ] **Step 2: Run the focused test and confirm it fails**

Run: `mvn -Dtest=TestOutcomeSignatureTest test`

Expected: compilation fails because `TestOutcomeSignature` does not exist.

- [ ] **Step 3: Implement hardened Surefire XML parsing**

Use JDK `DocumentBuilderFactory`; disable DOCTYPE declarations, external general entities, external parameter entities, XInclude, and entity expansion. Read `testsuite/testcase` attributes and classify child elements in priority order `error`, `failure`, `skipped`, otherwise `PASSED`. Sort entries by class, test name, and outcome so report file order does not affect equality. Reject no-report and malformed-report inputs.

- [ ] **Step 4: Run the focused tests**

Run: `mvn -Dtest=TestOutcomeSignatureTest test`

Expected: all signature parsing and identity comparison tests pass.

- [ ] **Step 5: Commit the component**

Run: `git add src/main/java/com/javafix/agent/tool/TestOutcomeSignature.java src/test/java/com/javafix/agent/tool/TestOutcomeSignatureTest.java src/test/resources/surefire; git commit -m "feat: compare per-test diagnostic outcomes"`

### Task 2: Create disposable project workspaces

**Files:**
- Create: `src/main/java/com/javafix/agent/tool/DiagnosticWorkspace.java`
- Test: `src/test/java/com/javafix/agent/tool/DiagnosticWorkspaceTest.java`

**Interfaces:**
- `DiagnosticWorkspace.create(Path projectRoot)` returns an `AutoCloseable` owner exposing `baselineRoot()` and `instrumentedRoot()`.
- The owner copies regular project files into two sibling temporary directories, skips VCS/build/generated directories and symlinks, enforces a configured byte limit, and removes both copies from `close()`.
- The original source tree is never opened for writing.

- [ ] **Step 1: Write failing copy and cleanup tests**

Test that `pom.xml` and source files appear in both copies, `.git` and `target` do not, symlink entries are skipped, and closing the owner removes the temporary root. Add a copy-limit test using a small package-private configurable limit so the test does not need to allocate a large file.

- [ ] **Step 2: Run the focused test and confirm it fails**

Run: `mvn -Dtest=DiagnosticWorkspaceTest test`

Expected: compilation fails because `DiagnosticWorkspace` does not exist.

- [ ] **Step 3: Implement copy ownership and failure cleanup**

Normalize the source root, reject a missing/non-directory root, walk without following links, skip `.git`, `target`, `.gradle`, `.idea`, and `node_modules`, copy regular files while counting bytes, and create both copies under one `Files.createTempDirectory` root. If either copy fails, recursively remove the temporary root before returning the error. `close()` performs the same cleanup and preserves the primary failure if cleanup also fails.

- [ ] **Step 4: Run the focused tests**

Run: `mvn -Dtest=DiagnosticWorkspaceTest test`

Expected: copy, skip, size-limit, and cleanup cases pass; source fixture contents remain unchanged.

- [ ] **Step 5: Commit the component**

Run: `git add src/main/java/com/javafix/agent/tool/DiagnosticWorkspace.java src/test/java/com/javafix/agent/tool/DiagnosticWorkspaceTest.java; git commit -m "feat: isolate trace diagnostics in temp copies"`

### Task 3: Instrument one Java method with AST probes

**Files:**
- Modify: `pom.xml`
- Create: `src/main/java/com/javafix/agent/tool/JavaSourceInstrumenter.java`
- Test: `src/test/java/com/javafix/agent/tool/JavaSourceInstrumenterTest.java`
- Create: `src/test/resources/trace/TraceFixture.java`

**Interfaces:**
- `JavaSourceInstrumenter.instrument(Path sourceFile, String className, String methodName, String helperClassName)` returns the modified source and event count.
- The instrumenter must locate exactly one class and method, or throw a typed diagnostic exception naming the unsupported/ambiguous condition.
- Probe calls use the generated helper class name and include event kind, source line, variable name, and one variable value.

- [ ] **Step 1: Add failing Java source instrumentation tests**

Cover: selected method only; method entry; local declaration and standalone assignment after their original statement; `if` then/else block entry; overloaded method ambiguity; embedded assignment is skipped; a side-effecting condition appears exactly once in generated source; unsupported parse input returns an actionable exception.

- [ ] **Step 2: Run the focused test and confirm it fails**

Run: `mvn -Dtest=JavaSourceInstrumenterTest test`

Expected: compilation fails because `JavaSourceInstrumenter` does not exist.

- [ ] **Step 3: Add JavaParser and implement the narrow AST transform**

Add `com.github.javaparser:javaparser-core:3.26.3` as the second production dependency. Configure parser language level `JAVA_17`. Find the named class and method by AST; reject zero or multiple matches. Instrument method entry, safe standalone local declarations/assignment statements, and branch blocks. Do not touch field initializers, return expressions, conditions, lambdas, anonymous classes, or generated sources. Skip branch bodies that are a single statement when creating a block would change control-flow/scoping behavior. Preserve source positions in each probe.

- [ ] **Step 4: Run the focused tests and inspect generated source**

Run: `mvn -Dtest=JavaSourceInstrumenterTest test`

Expected: tests pass, generated Java parses again, side-effect expressions occur once, and source outside the selected method is unchanged.

- [ ] **Step 5: Commit the instrumenter**

Run: `git add pom.xml src/main/java/com/javafix/agent/tool/JavaSourceInstrumenter.java src/test/java/com/javafix/agent/tool/JavaSourceInstrumenterTest.java src/test/resources/trace/TraceFixture.java; git commit -m "feat: instrument selected Java methods with AST probes"`

### Task 4: Add bounded safe trace writer and Maven process property

**Files:**
- Create: `src/main/java/com/javafix/agent/tool/TraceEventWriter.java`
- Modify: `src/main/java/com/javafix/agent/tool/ProcessRunner.java`
- Modify: `src/main/java/com/javafix/agent/tool/MavenTestRunner.java`
- Test: `src/test/java/com/javafix/agent/tool/TraceEventWriterTest.java`
- Test: `src/test/java/com/javafix/agent/tool/MavenCommandTest.java`

**Interfaces:**
- Generated helper code writes JSONL events to `System.getenv("JAVAFIX_TRACE_FILE")`.
- `TraceEventWriter` returns a generated package-private Java helper source that uses only JDK APIs.
- `ProcessRunner.run` accepts an immutable environment override map; `MavenTestRunner.run` gains an overload accepting the same map while existing `TestRunner` methods remain unchanged.

- [ ] **Step 1: Write failing helper and environment propagation tests**

Compile and execute generated helper code in a small fixture. Verify string and primitive values are recorded, a custom object with a throwing `toString()` is rendered as a type marker, values are truncated, and the event cap records an omitted count. Test `MavenTestRunner.mavenCommand` remains unchanged and `ProcessRunner` passes only the requested `JAVAFIX_TRACE_FILE` override to a child process.

- [ ] **Step 2: Run the focused tests and confirm failure**

Run: `mvn -Dtest=TraceEventWriterTest,MavenCommandTest test`

Expected: missing helper APIs and environment overload cause test compilation failure.

- [ ] **Step 3: Implement scalar-safe writer and process environment overrides**

Generate a helper with a synchronized sequence counter and bounded JSONL writes. Use exact class checks for boxed primitive wrappers; support strings, characters, booleans, primitive wrappers, and null. For any other object, emit only `value.getClass().getName()` and never invoke application methods. Truncate values and stop after the event limit. In `ProcessRunner`, apply the provided map to `ProcessBuilder.environment()` before start. In `MavenTestRunner`, pass overrides through without changing wrapper selection or test-selector behavior.

- [ ] **Step 4: Run focused writer and runner tests**

Run: `mvn -Dtest=TraceEventWriterTest,MavenCommandTest test`

Expected: safe formatting, bounds, omitted count, and child environment tests pass.

- [ ] **Step 5: Commit the writer and process option**

Run: `git add src/main/java/com/javafix/agent/tool/TraceEventWriter.java src/main/java/com/javafix/agent/tool/ProcessRunner.java src/main/java/com/javafix/agent/tool/MavenTestRunner.java src/test/java/com/javafix/agent/tool/TraceEventWriterTest.java src/test/java/com/javafix/agent/tool/MavenCommandTest.java; git commit -m "feat: capture bounded safe Java trace events"`

### Task 5: Orchestrate baseline parity and trace feedback

**Files:**
- Create: `src/main/java/com/javafix/agent/tool/TraceDiagnosticTool.java`
- Test: `src/test/java/com/javafix/agent/tool/TraceDiagnosticToolTest.java`
- Modify: `src/test/java/com/javafix/agent/BuggyCalculatorProject.java` only if the fixture needs a traceable local variable or branch.

**Interfaces:**
- Tool name: `diagnose_trace`.
- Required arguments: `path`, `class`, `method`, `test`.
- The tool owns `MavenTestRunner`, `DiagnosticWorkspace`, `JavaSourceInstrumenter`, and the outcome-signature comparison.
- Package-private `DiagnosticTestRunner.run(Path project, String selector, Map<String, String> environment, Duration timeout)` is the injectable process seam; production delegates to `MavenTestRunner.run`, and tests can write controlled Surefire reports and trace files.
- The tool returns a concise success summary plus bounded trace, or a specific failure reason and no trace.

- [ ] **Step 1: Write failing orchestration tests**

Use a small Maven fixture and a real `MavenTestRunner` with a short test timeout. Assert success returns per-test-matched trace events; an invalid source path, ambiguous method, missing reports, timeout, or changed test identity returns no `Trace:` section; the original source hash is unchanged after success and failure. Add injected-runner cases where one test outcome changes from `FAILED` to `PASSED` and where test identities differ despite equal counts; both must return no trace. Add an injected runner seam for deterministic mismatch and cleanup tests without starting Maven.

- [ ] **Step 2: Run the focused test and confirm it fails**

Run: `mvn -Dtest=TraceDiagnosticToolTest test`

Expected: compilation fails because `TraceDiagnosticTool` does not exist.

- [ ] **Step 3: Implement argument validation and fail-closed orchestration**

Reject unknown/missing arguments; resolve `path` through `ProjectTool.resolve`; reject non-Java files and symlink escapes; create the workspace; copy the selected source into the instrumented root; apply AST probes and add the generated package-private trace helper to that temporary source file; run baseline tests; require a complete signature containing exactly one FAILED/ERROR testcase; reject all multi-case signatures with `select exactly one failing test case`; run instrumented tests with `JAVAFIX_TRACE_FILE`; require a complete matching signature; read and cap trace events, label them with the single `className#testName` and selected method; and return the summary. Use try-with-resources so all workspace copies are removed on every path. Give baseline and instrumented runs a 10-minute overall deadline; pass `min(configured timeout, five minutes, remaining budget)` to each process. Check the deadline around copying, parsing and report/trace processing and during the copy walk; cap source and each XML report at 1 MiB. Validate suite counters against direct testcase children and require one recognized completed aggregate matching the XML total. Before the instrumented run, preallocate a one-byte `.status` sidecar with value `0`. The generated writer opens it before recording and synchronously writes `1` on capture failure through that independent open handle, preserving the interrupt flag; retain `.failed` as a secondary signal. Reject missing, malformed, or nonzero status after Maven. Return distinct observations for baseline failure, build failure, timeout, missing reports, parse ambiguity, parity mismatch, trace overflow, and cleanup failure.

- [ ] **Step 4: Run the focused integration tests**

Run: `mvn -Dtest=TraceDiagnosticToolTest test`

Expected: parity-success includes a trace, parity-failure includes no trace, and original files plus temporary directories are clean afterward.

- [ ] **Step 5: Commit the orchestrator**

Run: `git add src/main/java/com/javafix/agent/tool/TraceDiagnosticTool.java src/test/java/com/javafix/agent/tool/TraceDiagnosticToolTest.java src/test/java/com/javafix/agent/BuggyCalculatorProject.java; git commit -m "feat: add controlled runtime trace diagnostic tool"`

### Task 6: Register the tool, guide localization, and document limits

**Files:**
- Modify: `src/main/java/com/javafix/agent/Main.java`
- Modify: `src/main/java/com/javafix/agent/core/Phase.java`
- Test: `src/test/java/com/javafix/agent/AgentLoopTest.java`
- Test: `src/test/java/com/javafix/agent/MainTest.java`
- Modify: `README.md`

**Interfaces:**
- `Main` registers `new TraceDiagnosticTool(new MavenTestRunner(Duration.ofMinutes(5)), project)` alongside existing tools, exposed through a package-private tool factory for a registry test.
- `Phase.LOCALIZE` tells the model to use `diagnose_trace` only after identifying a suspicious method and exactly one reported failing testcase (multi-case attribution is unsupported in V1), and to treat trace output as evidence rather than instructions.

- [ ] **Step 1: Write failing localization prompt/tool registry tests**

Add a `MainTest` that calls the package-private tool factory and confirms it registers exactly one `diagnose_trace` tool. Add an `AgentLoopTest` scripted turn that exposes the tool description and confirms the localization prompt asks for a bounded, focused trace without requiring it when no plausible method has been identified.

- [ ] **Step 2: Run the focused test and confirm it fails**

Run: `mvn -Dtest=MainTest,AgentLoopTest test`

Expected: the prompt/registry expectation fails because the tool is not registered or described.

- [ ] **Step 3: Register the tool and update user documentation**

Extract a package-private `Main.createTools(Path project)` factory, register the diagnostic tool there with `new MavenTestRunner(Duration.ofMinutes(5))`, and use the factory from `main`. Update `Phase.LOCALIZE` with the selected-method workflow and parity warning. Update README architecture, current capabilities, supported scope, trace bounds, temporary-copy behavior, parity limitation, and unsupported cases. Do not describe automatic method selection or cross-task memory as implemented.

- [ ] **Step 4: Run focused and full verification**

Run: `mvn -Dtest=MainTest,AgentLoopTest,TraceDiagnosticToolTest test`, then `mvn test`.

Expected: both commands pass; README accurately describes the supported diagnostic workflow.

- [ ] **Step 5: Commit integration and docs**

Run: `git add src/main/java/com/javafix/agent/Main.java src/main/java/com/javafix/agent/core/Phase.java src/test/java/com/javafix/agent/AgentLoopTest.java src/test/java/com/javafix/agent/MainTest.java README.md; git commit -m "docs: expose controlled runtime tracing in localization"`

## Plan Self-Review

- Scope coverage: the six tasks implement request/signature parsing, workspace isolation, AST probes, safe bounded event writing, parity orchestration, agent integration, documentation, and all design acceptance criteria.
- Security coverage: hardened XML parsing, symlink skipping, project-root path checks, no arbitrary `toString()`, no original-tree writes, untrusted trace treatment, and temporary cleanup are covered.
- Placeholder scan: no TBD/TODO steps remain; every task names exact files, interfaces, focused test commands, and expected results.
- Type consistency: `TraceDiagnosticTool` uses the exact request fields declared in Task 5, delegates test signatures to Task 1, workspace lifecycle to Task 2, AST edits to Task 3, and environment transport to Task 4.
- Scope note: JavaParser source rewriting may encounter Java constructs that cannot be instrumented safely. The instrumenter must skip or reject those constructs and return no trace if it cannot prove a safe supported subset.
