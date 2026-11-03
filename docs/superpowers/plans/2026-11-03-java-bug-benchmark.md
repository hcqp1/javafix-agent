# Java Bug Benchmark Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a repeatable benchmark that runs pinned Maven bug cases in disposable Git worktrees and records repair, localization, and cost evidence as JSONL.

**Architecture:** Keep benchmark support outside JavaFixAgent's runtime. A Python standard-library runner reads a repository-owned JSON dataset, verifies the baseline failure, runs the existing one-shot CLI against an isolated worktree, reruns the focused test, and appends one structured result. It distinguishes reproduced bugs, successful repairs, failed repairs, and environment failures.

**Tech Stack:** Python 3 standard library, Git, Maven, existing JavaFixAgent CLI and run journal.

## Global Constraints

- Use the regression-test-backed `M2`–`M4` Maven samples in this stage; M1 has no dedicated regression-test fixture and Gradle remains Stage 5.
- Pin each case to its bug revision and known-fix revision and retain source attribution.
- Never run JavaFixAgent against the user's source clone; always use a disposable worktree.
- Keep model credentials in the existing user configuration; never write them to benchmark output.
- Store benchmark output as JSON Lines and separate environment failures from repair failures.

---

### Task 1: Add pinned benchmark metadata

**Files:**
- Create: `benchmarks/dataset.json`
- Modify: `README.md`

**Interfaces:**
- Dataset top-level fields: `version`, `source`, and `cases`.
- Each case fields: `id`, `name`, `repository`, `bug_revision`, `fix_revision`, `description`, `test_args`, `fixture`, `expected_failure`, and `key_files`.

- [x] Add paraphrased, attributed records for MyBatis M2 and RocketMQ M3/M4 with exact revisions and focused test commands.
- [x] Document prerequisites, run commands, disposable worktree behavior, output fields, metric definitions, and sample-specific limitation in README.

### Task 2: Implement benchmark runner and tests

**Files:**
- Create: `benchmarks/run.py`
- Create: `benchmarks/test_run.py`

**Interfaces:**
- CLI: `python benchmarks/run.py --repos-dir PATH [--case ID] [--agent-dir PATH] [--baseline-only] [--output PATH] [--timeout-seconds N]`.
- JSONL output: one record per case with case identity, status, baseline/final test outcomes, top-1/top-3 localization, elapsed time, step count, and token counts when available.
- Statuses: `baseline_reproduced`, `repaired`, `repair_failed`, `environment_failure`, and `baseline_not_reproduced`.

- [x] Write unit tests first for test-output classification, journal parsing, localization ranking, and JSONL record stability.
- [x] Implement repository validation, disposable `git worktree` lifecycle, baseline run, agent invocation, final test run, and cleanup reporting.
- [x] Verify that missing repositories, revisions, Maven/Java, or model configuration become explicit environment results instead of repair failures.

### Task 3: Verify and document actual usage

**Files:**
- Modify: `README.md`

- [x] Run benchmark unit tests and Java project tests.
- [ ] Run one selected sample only if the required local repository and model configuration are available; otherwise document the exact blocked prerequisite without fabricating a result.
- [x] Inspect `git diff --check` and confirm no API key is present in tracked files. The README contains the user-provided sample directory as a command example.

## Self-Review

- Dataset records have a fixed bug revision and official fix revision for scoring references.
- Baseline test failure is checked before the agent starts, so infrastructure failures cannot count as repair failures.
- Final test execution is independent of the Agent's claimed conclusion.
- `top_k` is based on repository-relative source files read or returned during localization; it is reported as unavailable if the journal lacks such evidence.
- Full-run metrics are evidence for this small dataset, not a guarantee for arbitrary repositories.
