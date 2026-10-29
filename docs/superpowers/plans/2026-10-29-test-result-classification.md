# Test Result Classification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When Surefire reports a real failed test, classify the run as a test failure even if the same Maven log contains a non-fatal JaCoCo/toolchain compatibility warning.

**Architecture:** Keep `RunTestsTool` as the result-classification boundary. Determine timeout and process-start failures first, then recognize a completed Surefire failure summary before considering environment signatures; continue classifying pre-test failures as inconclusive environment/build failures. Preserve the existing bounded diagnostic summary and Maven execution behavior.

**Tech Stack:** Java 17, Maven, JUnit 5.

## Global Constraints

- Keep the four-phase AgentLoop and current TestRunner/TestResult contracts unchanged.
- A real test-failure summary is evidence that tests ran; incidental JaCoCo output must not override it.
- A build/toolchain failure without a failed Surefire test summary remains inconclusive, never Bug reproduction evidence.
- Do not change RocketMQ source as part of this JavaFixAgent fix.
- Keep all paths, logs, and commands repository-relative or fixture-based in automated tests.

---

### Task 1: Fix mixed Surefire failure and environment warning classification

**Files:**
- Modify: `src/test/java/com/javafix/agent/RunTestsToolTest.java`
- Modify: `src/main/java/com/javafix/agent/tool/RunTestsTool.java`
- Modify: `README.md`

**Interfaces:**
- Consumes: `TestRunner.run(Path, String)` and `TestResult` output/exit-code fields.
- Produces: `run_tests` observations whose opening classification reflects the strongest completed test evidence, while the existing summary continues to show failure cases and relevant stack frames.

- [x] **Step 1: Add a regression test for the RocketMQ mixed log**

Add this test to `RunTestsToolTest` before changing production code:

```java
@Test
void shouldPrioritizeSurefireFailureOverJaCoCoCompatibilityWarning() {
    String log = """
            [WARNING] Error while instrumenting Mockito mock
            java.lang.IllegalArgumentException: Unsupported class file major version 61
            [ERROR] org.apache.rocketmq.client.impl.consumer.PullAPIWrapperTest.testPullKernelImplWithMissingFilterServerTableForClassFilter <<< FAILURE!
            java.lang.AssertionError: unexpected exception type thrown; expected:<org.apache.rocketmq.client.exception.MQClientException> but was:<java.lang.NullPointerException>
                at org.apache.rocketmq.client.impl.consumer.PullAPIWrapperTest.assertFindFilterServerFailed(PullAPIWrapperTest.java:200)
            [ERROR] Tests run: 2, Failures: 1, Errors: 0, Skipped: 1
            [ERROR] BUILD FAILURE
            """;
    RunTestsTool tool = new RunTestsTool(path -> new TestResult(1, log, ""), project);

    String observation = tool.execute(Map.of("test", "PullAPIWrapperTest"));

    assertTrue(observation.startsWith("测试未通过"), observation);
    assertFalse(observation.startsWith("测试环境失败"), observation);
    assertTrue(observation.contains("Failures: 1"), observation);
    assertTrue(observation.contains("NullPointerException"), observation);
}
```

- [x] **Step 2: Run the focused test and confirm it fails for the classification reason**

Run: `mvn "-Dtest=RunTestsToolTest#shouldPrioritizeSurefireFailureOverJaCoCoCompatibilityWarning" test`

Expected: the test fails because the observation begins with `测试环境失败` even though it includes a Surefire failure report.

- [x] **Step 3: Prioritize completed failing-test evidence in `RunTestsTool`**

Add this pattern and helper next to the other summary patterns in `RunTestsTool`:

```java
private static final Pattern FAILED_TEST_SUMMARY = Pattern.compile(
        "Tests run: [1-9][0-9]*, Failures: [1-9][0-9]*"
                + "|Tests run: [1-9][0-9]*, Failures: [0-9]+, Errors: [1-9][0-9]*");

private static boolean hasFailedTestSummary(String log) {
    return log.lines().anyMatch(line -> FAILED_TEST_SUMMARY.matcher(line).find());
}
```

In `execute`, compute `boolean hasFailedTest = hasFailedTestSummary(log);` and insert the failed-test branch immediately after the startup-failure branch and before the environment-signature branch:

```java
} else if (hasFailedTest) {
    observation.append("测试未通过。");
} else if (ENVIRONMENT_FAILURE.matcher(log).find()) {
```

Retain the current success, timeout, startup-failure, and build-failure-without-report behavior. Do not treat a compatibility signature alone as a completed test result.

- [x] **Step 4: Run the focused regression and existing classifier tests**

Run: `mvn "-Dtest=RunTestsToolTest" test`

Expected: all `RunTestsToolTest` tests pass, including the existing pure-environment-failure and build-failure-without-Surefire-report cases.

- [x] **Step 5: Clarify README behavior**

Update the `run_tests` feature description to say that explicit failed test summaries take precedence over incidental toolchain warnings, while environment failures with no completed test result remain inconclusive.

- [x] **Step 6: Run the complete JavaFixAgent suite and inspect the patch**

Run: `mvn test`

Expected: `BUILD SUCCESS`, 0 failures, 0 errors. Then run `git diff --check` and inspect `git diff` to ensure only the classifier, its regression tests, and the README description changed.

- [ ] **Step 7: Commit and push the first implementation stage**

Run:

```powershell
git add README.md src/main/java/com/javafix/agent/tool/RunTestsTool.java src/test/java/com/javafix/agent/RunTestsToolTest.java
git commit -m "fix: prioritize Surefire failures over environment warnings"
git push -u origin feat/javafix-agent-roadmap
```

Expected: the stage commit is pushed to the existing roadmap branch for review. Do not merge it automatically.

## Self-Review

- The test exercises the exact observed combination: JaCoCo compatibility warning plus a failed RocketMQ assertion.
- Existing tests continue to cover environment failure before test execution, process startup failure, timeout, pure test failure, and output bounds.
- The production change stays within classification; it does not weaken Maven/JDK environment diagnostics or modify the sample repository.
- The task is a focused first stage; benchmark, symbol lookup, context engineering, and Gradle each need their own plan before implementation.
