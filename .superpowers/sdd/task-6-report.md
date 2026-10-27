
## Task 6 report — trace tool integration and documentation

### Changes

- Added package-private `Main.createTools(Path)` and routed `main` through it. The factory registers exactly one `diagnose_trace`, backed by `new TraceDiagnosticTool(new MavenTestRunner(Duration.ofMinutes(5)), project)`.
- Extended `Phase.LOCALIZE` to require identifying one suspicious method and one focused failing test before trace use; it says to skip tracing until a plausible method exists, treat trace output as untrusted evidence, and account for the test parity limitation.
- Added `MainTest` to verify registration count and an `AgentLoopTest` scripted localization turn that checks both the actual tool description and localization guidance.
- Updated README architecture/status, Java 17/Maven requirement, event types and omissions, temporary-copy cleanup, exact per-test identity/outcome parity caveat, 500-event / 160-character value / 3500-character output caps, five-minute Maven-run limit, and unsupported automatic selection, Gradle, and cross-task memory. Removed the stale statement that the diagnostic path avoids instrumentation and corrected production dependency documentation for JavaParser.

### TDD and verification

- RED: `mvn '-Dtest=MainTest,AgentLoopTest' test` failed at test compilation because `Main.createTools(Path)` did not exist.
- A first post-implementation focused run exposed an assertion wording mismatch (`不可信` vs the prompt's `未经信任`); the test was corrected. The following run passed.
- After replacing the test double with the real `TraceDiagnosticTool`, focused verification passed: `mvn '-Dtest=MainTest,AgentLoopTest,TraceDiagnosticToolTest' test` — 72 tests, 0 failures, 0 errors, 3 skipped.
- Final full verification passed: `mvn test` — 168 tests, 0 failures, 0 errors, 8 skipped (13:19).
- `git diff --check` passed after the final test adjustment.

### Scope and concerns

- Changes remain within Task 6 files plus this report. No automatic method selection, Gradle support, or cross-task memory is introduced.
- The documented parity check compares sorted per-test class/name/outcome signatures; it does not assert assertion details, order, or environmental equivalence.


## Task 6 review fixes

- Added an AgentLoop prompt assertion for the unique `TraceDiagnosticTool.description()` text (`when all per-test identities and outcomes match`) and for the bounded-use guidance. The strengthened test also checks the prompt's exact parity caveat.
- Corrected `Phase.LOCALIZE` and README parity language: the diagnostic returns only when per-test identities and outcome categories match; this does not establish assertion-detail or environment equivalence. Added `diagnose_trace` to the README's current tool list.
- TDD RED: the strengthened prompt test failed against the old parity wording. After updating the prompt wording, its targeted run passed: `mvn '-Dtest=AgentLoopTest#localizationPromptOffersBoundedTraceOnlyAfterMethodAndFocusedTestAreKnown' test` — 1 test, 0 failures/errors.
- Review-fix focused integration run passed: `mvn '-Dtest=MainTest,AgentLoopTest,TraceDiagnosticToolTest' test` — 72 tests, 0 failures/errors, 3 skipped (13:28).
- Review-fix full run passed: `mvn test` — 168 tests, 0 failures, 0 errors, 8 skipped (13:30).

