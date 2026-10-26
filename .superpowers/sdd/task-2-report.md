# Task 2 report: Disposable diagnostic workspaces

## Result

Implemented `DiagnosticWorkspace`, which owns two isolated copies (`baseline` and `instrumented`) under one temporary directory. It normalizes and validates the input root, walks without following symbolic links, skips `.git`, `target`, `.gradle`, `.idea`, and `node_modules`, copies regular files only, enforces a configurable byte limit (100 MiB by default), and recursively removes the temporary root on close or copy failure. Cleanup failures during copy failure are attached as suppressed exceptions to the primary failure.

## TDD evidence

Added `DiagnosticWorkspaceTest` before production code and ran `mvn -Dtest=DiagnosticWorkspaceTest test`. It failed at test compilation as expected because `DiagnosticWorkspace` did not exist (five unresolved-symbol errors). After implementation, the focused test passed: 3 tests, 0 failures, 0 errors, 1 skipped. Tests cover both copies and unchanged source contents, skipped build/VCS directories, configured size rejection and failure cleanup, and owner cleanup on close.

The symbolic-link test was skipped because this Windows environment did not permit creating the fixture symlink. The implementation uses the default non-following `walkFileTree` behavior and copies only entries whose attributes report them as regular files.

## Verification

- `mvn -Dtest=DiagnosticWorkspaceTest test` — passed (3 tests, 1 skipped).
- `mvn test` — passed (83 tests, 0 failures, 0 errors, 3 skipped).
- `git diff --check` — passed before commit.

## Review

Changes are limited to the requested component and its tests. Both source copies are read-only inputs; all writes target the generated temporary directory. Failed copy construction and normal owner close share the same recursive deletion path. No concerns beyond the environment-skipped symlink fixture.

## Commit

Pending.
