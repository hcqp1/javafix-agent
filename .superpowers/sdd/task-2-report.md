# Task 2 report: Disposable diagnostic workspaces

## Result

Implemented `DiagnosticWorkspace`, which owns two isolated copies (`baseline` and `instrumented`) under one temporary directory. It normalizes and validates the input root, walks without following symbolic links, skips `.git`, `target`, `.gradle`, `.idea`, and `node_modules`, copies regular files only, enforces a configurable byte limit (100 MiB by default), and recursively removes the temporary root on close or copy failure. Cleanup failures during copy failure are attached as suppressed exceptions to the primary failure.

## TDD evidence

Added `DiagnosticWorkspaceTest` before production code and ran `mvn -Dtest=DiagnosticWorkspaceTest test`. It failed at test compilation as expected because `DiagnosticWorkspace` did not exist (five unresolved-symbol errors). After implementation, the focused test passed: 3 tests, 0 failures, 0 errors, 1 skipped. Tests cover both copies and unchanged source contents, skipped build/VCS directories, configured size rejection and failure cleanup, and owner cleanup on close.

For the review fixes, added tests for a symlink project root and for a configured temporary base located inside the source tree. The initial focused run failed at compilation because the package-private temp-base overload was not implemented. After implementation, both focused and full suites passed.

Both symbolic-link tests were skipped because this Windows environment did not permit creating symlinks. Link creation is isolated from the behavior assertions so only inability to create links triggers a skip. The external symlink target fixture is deleted in a `finally` block. The implementation rejects a linked project root and uses the default non-following `walkFileTree` behavior, copying only regular files.

## Verification

- `mvn -Dtest=DiagnosticWorkspaceTest test` — passed (5 tests, 0 failures, 0 errors, 2 skipped).
- `mvn test` — passed (85 tests, 0 failures, 0 errors, 4 skipped).
- `git diff --check` — passed before commit.

## Review

The workspace walker skips the generated temp root and its descendants, preventing recursive self-copy when the temp base is inside the source project. Root symlinks are rejected. Both source copies are read-only inputs; all writes target the generated temporary directory. Failed copy construction and normal owner close share the same recursive deletion path. Remaining verification limitation: symlink behavior could not be exercised on this Windows environment.

## Commits

- `2dca795` — `feat: isolate trace diagnostics in temp copies`
- `aea11ef` — `fix: harden diagnostic workspace boundaries`
