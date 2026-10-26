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

## Review follow-up: helper shadowing and case-insensitive generated paths

The review identified two concrete gaps: a method parameter/local/member could obscure the helper type reference and cause generated code to fail compilation, and uppercase/mixed-case generated directory names could bypass the path check on Windows.

Added four regression tests before changing production code. The focused run failed with **18 tests, 4 failures, 0 errors/skips** because the expected checked exceptions were not thrown. The tests cover parameter/local conflicts, lexical member/type conflicts, qualified helper prefixes, and uppercase/mixed-case generated paths.

The transformer now validates the helper's **leading identifier** against known declaration names throughout the selected method (conservatively including nested scopes), enclosing type names/fields/member types/type parameters/record components/enum constants, enclosing lexical contexts, compilation-unit type names, and explicit imports. Known conflicts fail with `InstrumentationException` identifying the declaration line and suggesting another generated helper name. This is a syntactic fail-closed check, not a symbol resolver. A declaration anywhere in the selected method or enclosing lexical context can therefore cause a conservative rejection even when it is not visible at a particular probe site.

Qualification is not a universal escape from Java name shadowing: in `support.TraceSink.record(...)`, a variable/type named `support` can still obscure the reference. The transformer preserves the supplied qualified name and checks its first identifier rather than automatically inventing a package. A regression compiles the generated Java using `--release 17` with a real `support.TraceSink` helper and a parameter named `TraceSink`, proving that shadowing only the later qualifier segment is harmless. The same test rejects a parameter named `support`. Additional assertions cover enclosing fields, local/member types, class/method type parameters, record components, enum constants, top-level types, explicit imports, and catch parameters.

Generated-source path components are now lowercased using `Locale.ROOT` before comparison on every platform. Both `TARGET/GENERATED-SOURCES` and mixed-case `GeNeRaTeD-TeSt-SoUrCeS` are rejected before parsing/inserting probes.

The initial review-fix verification passed **18 focused tests** and the full suite passed **104 tests, 0 failures/errors, 5 existing skips**. Further lexical-scope self-review added a regression for selecting a local-class method in an enclosing constructor, initializer, or lambda; the focused run failed with **19 tests, 1 failure** because the constructor parameter conflict was not rejected. Generalizing ancestor scanning to surrounding lexical contexts fixed the same root cause without adding symbol resolution. Final focused verification passed **19 tests, 0 failures, 0 errors, 0 skips**. Final full-suite `mvn test` completed successfully with **105 tests, 0 failures, 0 errors, 5 existing environment skips**. `git diff --check` also passed before the follow-up commit.

Self-review checked that validation runs before any probe insertion, conflicting names produce checked diagnostics, qualified references remain unchanged, both generated-path patterns use locale-independent case folding, and changes remain limited to the instrumenter and focused tests. External inherited members cannot be resolved by this single-source JavaParser Core transformer; later orchestration must generate a fresh unique helper identifier per run to mitigate those conflicts, while these syntactic checks ensure known conflicts are never accepted merely on the assumption of randomness. No symbol-resolution dependency or blanket inheritance rejection was introduced.
