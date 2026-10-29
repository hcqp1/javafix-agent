# JavaFixAgent Roadmap Design

## Goal

Complete the capabilities already listed as planned in the repository README, while first correcting the test-result classification defect found during the RocketMQ trial. Deliver the work in reviewable stages with a separate verification result for each stage.

## Current Context

JavaFixAgent is a Java 17 command-line agent for Maven repositories. Its core workflow is a four-stage state machine: reproduce, localize, repair, and verify. It already has OpenAI-compatible model access, file/search/edit/test/shell tools, persisted task-scoped conversations, and bounded Java runtime trace diagnosis. The current reliability issue is that `RunTestsTool` treats any JaCoCo `Unsupported class file major version` message as an environment failure even when Surefire has already reported a real failing test. The observed RocketMQ test failed because actual behavior threw `NullPointerException` where `MQClientException` was expected.

## Scope

This roadmap includes:

1. Correct test-result classification when test reports and toolchain warnings coexist.
2. A reproducible Java bug benchmark and baseline metrics for repair success, top-k localization, and cost.
3. Java symbol-level lookup for classes, methods, and references.
4. Stage-aware context selection and retrieval, measured against the benchmark.
5. Gradle Wrapper test execution alongside existing Maven support.
6. Documentation and end-to-end acceptance covering the shipped capabilities.

The scope is limited to the features explicitly listed in the current README and the concrete classification defect found during RocketMQ validation. It does not add a GUI, multi-agent orchestration, automatic Git branch management, cross-task experience memory, or automatic method selection for runtime diagnosis.

## Delivery Order

### Stage 1: Test-result classification reliability

Parse test evidence independently from environment signatures. A completed Surefire report containing failed test cases and assertion/exception details takes precedence over incidental instrumentation warnings. Classify a run as an environment/build failure when no test conclusion was produced, such as dependency resolution failure, test startup failure, or timeout. Keep timeout and start-failure statuses distinct. Add regression cases for both a mixed JaCoCo-warning plus real test failure and a genuine pre-test environment failure.

### Stage 2: Benchmark and baseline

Create a small, version-pinned Maven bug dataset. Each case records the repository, baseline revision, bug description, focused test command, expected failing signature, and known-fix revision or expected patch. Provide a repeatable runner that records repair success, top-k file localization, model/tool cost where available, elapsed time, and environment failures separately. Store results in a machine-readable format and document that benchmark results are evidence for this dataset, not a guarantee for arbitrary repositories.

### Stage 3: Java symbol lookup

Add a bounded symbol search tool using the existing Java parsing dependency. It should locate declarations and references by qualified type and method name, respect repository-relative scope, cap results, and report parse failures without hiding text-search results. Integrate it into localization prompts and retain existing text search as a fallback.

### Stage 4: Context engineering

Build phase-specific context from the task history and tool observations: retain the current issue, recent relevant failures, implicated files/symbols, and prior repair attempts; omit unrelated large observations. Add retrieval over repository-local symbols and prior observations rather than introducing a remote vector database. Preserve task isolation so one bug's transcript is not injected into another bug. Use the benchmark to compare baseline and changed repair/localization/cost metrics.

### Stage 5: Gradle support

Extend the test-runner abstraction with Gradle Wrapper detection and focused test selection, preferring `gradlew`/`gradlew.bat` when present. Preserve Maven behavior and its Wrapper preference. Normalize outcomes into the existing success, test-failure, environment/build-failure, timeout, and start-failure model. Add fixture-based tests and at least one pinned Gradle sample to the benchmark.

### Stage 6: End-to-end acceptance and documentation

Run the JavaFixAgent test suite, the complete pinned benchmark, and one Maven plus one Gradle repair case. Update README capability/status/limitations and provide commands to reproduce benchmark results. Each stage is pushed as a reviewable branch/PR; no stage is considered done until its verification evidence and documentation are present.

## Architecture and Data Flow

- Keep the existing `AgentLoop` and `TestRunner` abstractions; avoid adding an orchestration framework.
- Introduce benchmark metadata/results as repository-owned files and a deterministic runner, not production runtime state.
- Implement symbol lookup as a repository-bounded tool that returns compact evidence to the current AgentLoop.
- Keep context assembly in a dedicated component with explicit stage and size limits.
- Add a Gradle runner behind the existing test execution contract; select the runner from the repository's Wrapper/build files.
- Keep configuration, API keys, task journals, and generated logs outside the repository unless a benchmark result is intentionally exported.

## Verification

- Stage 1: regression tests demonstrate correct classification for mixed test failure plus JaCoCo warning and for environment failure before tests.
- Stage 2: repeated benchmark runs produce stable case identity and machine-readable outcome records.
- Stage 3: fixture tests cover declaration/reference lookup, overloads, parse errors, scope, and result bounds.
- Stage 4: tests cover phase separation, context bounds, task isolation, and retrieval ranking; benchmark reports compare against baseline.
- Stage 5: tests cover Maven and Gradle Wrapper selection, focused test names, output classification, timeouts, and missing-wrapper errors.
- Final acceptance: JavaFixAgent unit suite passes, pinned benchmark completes or records explicit per-case environment failures, and README matches shipped behavior.

## Risks and Constraints

- Benchmark cases can fail for external dependency or JDK reasons; report those separately rather than counting them as repair failures.
- Java parsing and references may be incomplete for generated sources, unresolved dependencies, or unusual language constructs; retain text search as a fallback.
- Context changes can improve one metric while worsening another; evaluate repair rate, localization, and cost together.
- Gradle command semantics differ by project and platform; Wrapper-based fixtures must cover Windows and Unix command construction.
- The current user's RocketMQ case uses an old JaCoCo plugin with Java 17-generated Mockito classes. The Stage 1 fix must preserve the real Surefire assertion failure while surfacing the JaCoCo warning as secondary diagnostic evidence.
