# Task 1 report: TestOutcomeSignature

## Status
Implemented and committed the immutable per-test Surefire outcome signature.

## Changes
- Added `TestOutcomeSignature.read(Path)` with a typed `ParseException` failure for missing/empty report sets, malformed XML, and reports without testcases.
- Parses XML with JDK DOM and disables DOCTYPE, external general entities, external parameter entities, XInclude, and entity expansion; enables secure processing.
- Captures `(className, testName, outcome)` entries, sorts deterministically, and compares complete entry lists.
- Applies outcome priority `ERROR`, `FAILED`, `SKIPPED`, then `PASSED`.
- Added fixtures for one failed calculator test and a renamed test with the same aggregate failure count.
- Added tests for parsed failure identity, identity-sensitive matching, malformed XML, empty reports, and classification priority.

## TDD evidence
Before production code, `mvn -Dtest=TestOutcomeSignatureTest test` failed during test compilation because `TestOutcomeSignature` did not exist (as expected). After implementation, the focused run passed: 5 tests, 0 failures, 0 errors, 0 skipped.

## Verification
- Focused signature tests: passed (5/5).
- Full `mvn test`: began running but the command returned while `AgentLoopTest` was still in progress, without a Maven summary or exit code. Full-suite completion is therefore unverified.
- `git diff --check`: passed.

## Concerns
The full suite should be rerun to completion; no failure was reported, but the command output was cut off during the existing `AgentLoopTest` run.

## Review fixes (2026-10-26)

### Findings addressed
- Each XML document must have a Surefire `testsuite` or `testsuites` root; `testsuites` must contain a suite, and every report must contain at least one testcase. This rejects invalid roots and valid-plus-empty report sets.
- `UncheckedIOException` raised during lazy `Files.walk` traversal is wrapped as `ParseException` with its original cause.

### TDD evidence
Added tests first for a `<foo>` root and for a valid report mixed with an empty suite. Before the implementation change, the focused run failed exactly those two assertions because no `ParseException` was thrown. A symbolic-link cycle test was also added for traversal failure; this Windows environment disallowed creating the link, so JUnit skipped that specific test.

### Verification after fixes
- `mvn -Dtest=TestOutcomeSignatureTest test`: passed, 8 tests, 0 failures, 0 errors, 1 skipped (symlink-cycle setup unavailable).
- `mvn test`: passed, 80 tests, 0 failures, 0 errors, 3 skipped; BUILD SUCCESS, 2:21 min.
