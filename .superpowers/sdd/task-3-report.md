# Task 3 report: AST method instrumentation

## Scope and contracts

Implemented the requested narrow Java source transformer on base `d06051d`. The change touches `pom.xml`, `JavaSourceInstrumenter.java`, its focused JUnit test, and `trace/TraceFixture.java`; it does not introduce the trace writer or diagnostic orchestration.

Implementation commit: `7289f09` — `feat: instrument selected Java methods with AST probes`.

The controller clarified the cross-task contract before implementation: public `Result(String source, int eventCount, List<String> skippedProbes)`, checked `InstrumentationException`, and helper calls to `record(String kind, int line, String variableName, Object value)`. The result copies its skipped-probe list. Entry and branch events pass an empty variable name and `null`. Event kinds are `METHOD_ENTRY`, `LOCAL_DECLARATION`, `ASSIGNMENT`, `BRANCH_THEN`, and `BRANCH_ELSE`.

JavaParser Core **3.26.3** is the second production dependency. Each parse explicitly uses **JAVA_17** and a tab size of one so AST columns map to original string offsets. The original Jackson dependency comment was adjusted because it is no longer the only production dependency.

## Implementation

- Selects exactly one named type (simple or fully qualified name), then exactly one directly declared method. Missing/ambiguous classes, missing/overloaded methods, methods without bodies, parse failures, invalid helper names, and uncertain source positions produce a checked diagnostic with a specific reason.
- Rejects path components containing `generated-sources` or `generated-test-sources`, and selected methods/classes or their enclosing declarations annotated with a simple annotation name of `Generated`.
- Inserts entry probes after the selected method body's opening brace. Inserts one probe for each initialized local after its entire standalone declaration statement, and for standalone simple-name assignments to visible block locals or method parameters after the original assignment statement.
- Inserts branch probes only after the opening brace of existing `if` then/else blocks. All single-statement branch probes are conservatively skipped, with their kind, original line, and reason returned for later orchestration.
- Uses AST positions to insert new source backwards at statement boundaries. Original source text is retained, including comments, expressions, fields, other methods, tabs, Unicode, and CRLF line endings; the input path is only read.
- Stops at expression and nested type boundaries. Lambda/anonymous/local-class bodies, conditions, embedded assignments, return expressions, and initializer expression internals are left unchanged. Field/array assignments and names whose local binding cannot be established are not probed.

## TDD evidence

1. Added the fixture and initial 12 focused tests before production code. `mvn -Dtest=JavaSourceInstrumenterTest test` failed at test compilation with **10 missing-type/symbol errors for JavaSourceInstrumenter**, as required by the brief. No production implementation existed at that point.
2. Added JavaParser and the implementation. Initial focused run passed **12 tests, 0 failures/errors/skips**. Maven first hit a filesystem permission error downloading the new dependency to its configured external repository; the approved Maven escalation downloaded the pinned dependency successfully.
3. Added explicit generated-source reparse checks plus tab/Unicode/CRLF and multiple-declaration ordering coverage. Focused run passed **13 tests, 0 failures/errors/skips**. The initial full suite completed with **99 tests, 0 failures/errors, 5 existing skips**.
4. Self-review found that traversal could reach statements nested inside switch expressions used as return values or initializers. Added `leavesStatementsInsideReturnAndInitializerExpressionsUnchanged` before fixing the filter. The focused run failed with **expected 2 probe sites but observed 8**. Root cause was ownership traversal crossing expression ancestors. Replacing special-case expression exclusions with a general expression-boundary exclusion fixed that root cause; the final focused run passed **14 tests, 0 failures/errors/skips**.

## Verification

The focused fixture test compiles generated source with the JDK compiler using `--release 17`, loads it, invokes both branch paths, and checks return values, event order, original source lines, variable values, and exactly one condition evaluation per invocation. Generated output for the committed fixture was also printed and inspected directly: seven static probe sites, unchanged condition text, unchanged surrounding fields/methods, and original source lines 5–11.

Final `mvn test` completed successfully: **100 tests, 0 failures, 0 errors, 5 existing environment skips**. The focused instrumentation class contributed **14 tests, 0 failures, 0 errors, 0 skips**. `git diff --check` passed before staging and committing. No compiler warnings remain from the new implementation.

## Self-review and limitations

Reviewed selection scope, generated-source rejection, helper-name validation, original source positions, stable insertion ordering for multiple declarators, local-binding checks, expression/type boundaries, and generated source compilation/runtime behavior. No outstanding implementation concern was found after the expression-boundary regression fix.

This version deliberately does not resolve arbitrary Java symbols: assignments to loop/resource/catch/pattern variables are omitted unless their binding is also established by the supported block-local/parameter rules. Single-statement branches are disclosed rather than wrapped. The safe trace formatter, runtime limits, and outcome-parity guard remain later tasks as requested. The existing full-suite skips concern Windows symlink availability (three workspace tests) and absent POSIX file attributes (two journal tests).
