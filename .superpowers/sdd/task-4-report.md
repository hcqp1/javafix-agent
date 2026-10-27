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

## Review follow-up: JDK shadowing and aggregate fork-safe bounds

The review identified two correctness gaps in the initial implementation: unqualified JDK names could resolve to application classes, and per-helper counters plus a shutdown hook could neither enforce aggregate fork bounds nor count calls from later application shutdown hooks. The earlier self-review conclusion and cross-JVM/shutdown-summary limitations above are superseded by this section.

### Reproduction before fixes

Added compiled-fixture regressions before changing production code. `mvn -Dtest=TraceEventWriterTest,MavenCommandTest test` reproduced all four cases:

- Three concurrent JVMs each writing through four threads produced **24 records instead of the aggregate limit of 8**.
- A delayed application shutdown hook produced a reported omitted count of **1 instead of 4**.
- Reading the trace before JVM shutdown found no omission summary (12 calls, limit 1, expected 11 omitted).
- Same-package application classes named `Boolean` and `System` were used by the old helper: application `System.getenv` executed, and the throwing application `Boolean.toString()` disabled capture. The initial assertion was a missing-file read; after making the test assert file existence explicitly, the targeted RED run failed with **1 assertion failure, 0 errors**, showing `application System invoked` in child output.

RED logs: `.superpowers/sdd/task-4-review-red.log` and `.superpowers/sdd/task-4-shadow-red.log`.

### Fix and shared-file protocol

Every JDK type in generated source is fully qualified, including `java.lang.Object`, `String`, all wrapper classes, `Class`, `StringBuilder`, `Throwable`, and `System`, plus all I/O, path, locking, and charset APIs. There is no shutdown hook or runtime thread creation left in the helper. The compiled shadow fixture now also defines same-package `Object`, `String`, all remaining wrapper names, `Class`, `StringBuilder`, `Throwable`, `Runtime`, `Thread`, and `Runnable`; the helper compiles and captures the application Boolean as `<type:Boolean>` without invoking either application override.

A sibling **`<JAVAFIX_TRACE_FILE>.state`** stores exactly three big-endian longs: retained-event count, omitted-call count, and the byte offset immediately after retained events (24 bytes total). Every call acquires a JVM-wide canonical-path interned monitor and then an OS exclusive file lock on the state file. The monitor coordinates helpers/classloaders in one JVM; the file lock coordinates Maven fork JVMs. Counters, trace writes, and state writes all occur inside that lock.

Until the global event cap is reached, the writer appends regular events with globally increasing sequence numbers and updates the end offset. Later calls update the shared omitted counter and overwrite the **single terminal overflow JSONL record** at that offset. The count is immediately visible after each completed record call, including calls made by application shutdown hooks. The number of trace records stays at most `eventLimit + 1` across all helpers and forks. Overflow records grow monotonically with the decimal counter, so rewriting requires no truncation. Dropped values are never formatted. Long omission counts saturate at `Long.MAX_VALUE`.

Unbuffered file handles remain open for the helper JVM's lifetime to avoid reopening files on every hot probe; the OS closes them at process exit. Writer failure disables capture and closes that helper's handles. No trace hook or final flush is required. An unmatched pre-existing nonempty trace/state pair fails open instead of appending to an untracked trace. The diagnostic run must use a fresh trace path and keep the sibling state file until all Maven forks have finished; normal scratch-directory cleanup removes both together. Task 5 can continue consuming the same JSONL overflow event without reading the sidecar.

### Regression coverage and self-review

The fork test now exercises **three concurrent JVMs, four threads per JVM, and two generated helper classes per JVM**. Across 1,200 calls with cap 7 it asserts exactly **8 parseable records**, sequences 1 through 8, and **1,193 omitted calls**, plus a bounded trace file size. Additional regressions prove delayed shutdown-hook calls and live omission reporting. Reviewed all generated JDK references, lock acquisition order, shared state positioning, UTF-8 byte offsets, terminal-record replacement, normal/failing file-handle behavior, and compatibility with the existing scalar-safe tests.

The first lock-protected implementation reopened handles and truncated the trace on every call, making the 1,000-call stress fixture hit its 20-second timeout. Reusing unbuffered handles and eliminating unnecessary truncation reduced the complete writer-test class to about **4.5 seconds**; the unchanged timeout now passes. This was a performance issue in the intermediate fix, not a weakened test bound.

Final expanded focused command: `mvn -Dtest=TraceEventWriterTest,MavenCommandTest test` passed **12 tests, 0 failures, 0 errors, 0 skips**, BUILD SUCCESS. Log: `.superpowers/sdd/task-4-review-focused.log`.

Abrupt process death during an in-progress file operation or a filesystem failure can still leave an incomplete trace; later orchestration must reject malformed/incomplete diagnostic data as unavailable. Completed normal calls, concurrent fork calls, and normal shutdown-hook calls no longer depend on a best-effort shutdown summary or separate per-JVM caps.

Review-fix final full verification: `mvn test` completed with **116 tests, 0 failures, 0 errors, 5 existing environment skips**, BUILD SUCCESS, **2:37** elapsed. The expanded writer class passed all 8 tests in 3.647 seconds. Log: `.superpowers/sdd/task-4-review-full.log`. `git diff --check` passed after the final source formatting change. Changes remain limited to the writer, its focused tests, and this report; no outstanding review finding remains in the requested normal/concurrent/shutdown behavior.
