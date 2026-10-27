# Task 4 report: bounded safe trace writer and Maven environment forwarding

## Scope and starting state

Resumed on base `0752693`. Preserved and completed the interrupted implementer's modified `MavenCommandTest.java` and untracked `TraceEventWriterTest.java`; no reset or unrelated edits were made. Production scope is exactly the new writer, `ProcessRunner`, and `MavenTestRunner`, with the two named test classes.

Before the RED run, corrected the partial Maven test to use existing `TestResult.getExitCode()` / `getStdout()` getters. Corrected fixture construction so embedded quote/backslash/control characters form valid Java source, and replaced the Java 11 `String.repeat` fixture call with a long literal because generated fixture compilation deliberately uses `--release 8`.

## TDD evidence

Ran `mvn -Dtest=TraceEventWriterTest,MavenCommandTest test` before implementing production APIs. Maven failed at test compilation with **8 errors**, all referring to the absent `TraceEventWriter` or missing `ProcessRunner.run(..., Map)` / `MavenTestRunner.run(..., Map)` overloads. The log is `.superpowers/sdd/task-4-red.log` (ignored build evidence). No getter or fixture syntax error remained in that RED output.

After implementing the requested APIs, the same focused command completed with **8 tests, 0 failures, 0 errors, 0 skips**, BUILD SUCCESS. Focused log: `.superpowers/sdd/task-4-focused.log`. The fixture compiler emits the installed JDK's expected obsolete-Java-8-option warnings; generated code compiles and executes successfully.

## Contracts and implementation

- Public `TraceEventWriter.helperSource(String className, String source, String method, int eventLimit, int valueLimit)` returns a self-contained package-private final Java helper. It validates a simple non-keyword identifier and positive bounds. Metadata is escaped as Java literals; generated source uses only Java 8 JDK APIs and no imports or project dependencies.
- The generated helper implements the Task 3 contract, `static synchronized void record(String kind, int line, String variableName, Object value)`. It reads only `JAVAFIX_TRACE_FILE`, appends UTF-8 JSONL, and does nothing if the variable is absent/empty.
- Events carry `sequence`, `source`, `method`, `kind`, `line`, `variable`, `value`, and `truncated`. Null stays JSON null. Supported scalar values become JSON strings. An exact `String.class` check and exact checks for all eight boxed primitive wrappers prevent application-defined `Number` subclasses from reaching conversion methods. Every other value is represented as `<type:fully.qualified.Name>` using only its final `getClass()` method and JDK class metadata. No arbitrary application `toString()`, getter, array traversal, or object traversal occurs.
- Values are truncated before JSON escaping, with a `truncated` flag. JSON escaping handles quotes, backslashes, control characters, and surrogate code units. The helper retains only counters and the destination path, never an event list.
- Only the earliest `eventLimit` events are written. Later calls increment a saturating long omission counter without formatting their values or writing more records. One synchronized shutdown hook appends an `overflow` event with the final `omitted` count. Normal execution therefore produces at most `eventLimit + 1` JSONL records and bounded trace-event value storage.
- Initialization, record writes, and summary writes fail open: failure disables the helper rather than throwing into application execution. Tests include an unwritable destination (a directory) and a custom `Number` whose conversion methods and `toString()` throw.
- `ProcessRunner` retains its existing overload and adds an overload accepting `Map<String,String>`. It copies overrides with `Map.copyOf`, then applies them to the child builder's inherited environment before process start. The parent environment and caller's immutable map are untouched.
- `MavenTestRunner` adds `run(Path, String, Map<String,String>)`; existing `TestRunner` methods delegate with an empty map. Wrapper choice, selector arguments, log redirection, timeout handling, process-tree termination, and result semantics retain their existing implementation.

## Self-review

Reviewed all `ProcessRunner.run` callers: `ShellTool` and `WorkspaceStatus` continue using the original overload. Reviewed `MavenTestRunner` callers and the `TestRunner` interface; no signature changes or caller changes are needed. Reviewed exact scalar-class gating, JSON/Java escaping, synchronization, bounds, omission counting, exception isolation, and existing Maven command construction. The immutable-map child test verifies the requested variable override and inherited PATH, and checks the parent environment/map. The fake-wrapper test verifies actual Maven overload forwarding and unchanged selector arguments.

## Limits and integration notes

The omitted-count summary is emitted at normal JVM shutdown. A forcibly terminated JVM, `Runtime.halt`, or a fatal VM failure can lose that summary, while already appended regular events remain available. Concurrent JVMs sharing one trace file are not coordinated by the per-helper synchronization; later orchestration should provide a fresh destination per diagnostic run and must treat timeouts/incomplete traces conservatively. Metadata (`source`, `method`, probe kind, variable name) is fixed by instrumentation and is not truncated; the configured bound applies to observed values. Full outcome-parity checks and orchestration remain later tasks, as requested.

## Final verification

- `mvn -Dtest=TraceEventWriterTest,MavenCommandTest test`: BUILD SUCCESS, **8 tests, 0 failures, 0 errors, 0 skips**.
- `mvn test`: BUILD SUCCESS, **112 tests, 0 failures, 0 errors, 5 existing skips**, completed in **2:55**. Existing skips are the Windows symlink availability checks (3) and absent POSIX file-attribute checks (2). Full log: `.superpowers/sdd/task-4-full.log`.
- `git diff --check`: passed before staging.

No outstanding implementation issue was found in the requested scope. No additional dependencies or changes to unrelated production paths were introduced.
