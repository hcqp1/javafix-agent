package com.javafix.agent;

import com.javafix.agent.tool.MavenTestRunner;
import com.javafix.agent.tool.RunTestsTool;
import com.javafix.agent.core.TestResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跑测试的工具把「超时」和「跑完了但失败」分开报告。
 *
 * <p>这条区分很要紧：超时的运行会带回一段"目前看起来都在通过"的日志，
 * 如果不单独标记，一次没跑完的测试可能被当成"验证通过"。
 */
class RunTestsToolTest {

    @TempDir
    Path project;

    @Test
    void shouldReportTimeoutsAsTimeouts() throws IOException {

        BuggyCalculatorProject.create(project);

        RunTestsTool tool = new RunTestsTool(new MavenTestRunner(Duration.ofMillis(1)), project);

        String observation = tool.execute(Map.of());

        assertTrue(observation.startsWith("测试超时"), "超时必须单独标记，实际：" + observation);
    }

    @Test
    void shouldReportProcessStartupFailureAsInconclusive() {
        RunTestsTool tool = new RunTestsTool(path -> new TestResult(
                TestResult.EXIT_START_FAILURE, "", "无法启动 Maven"), project);

        String observation = tool.execute(Map.of());

        assertTrue(observation.startsWith("测试未启动"), observation);
    }

    @Test
    void shouldClassifyUnsupportedSecurityManagerAsEnvironmentFailure() {
        RunTestsTool tool = new RunTestsTool(path -> new TestResult(1,
                "[java] java.lang.UnsupportedOperationException: Setting a Security Manager is not supported\n"
                        + "[ERROR] BUILD FAILURE", ""), project);

        String observation = tool.execute(Map.of());

        assertTrue(observation.startsWith("测试环境失败"), observation);
        assertFalse(observation.startsWith("测试未通过"), observation);
    }

    @Test
    void shouldClassifyBuildFailureWithoutSurefireResultsAsInconclusive() {
        RunTestsTool tool = new RunTestsTool(path -> new TestResult(1,
                "[ERROR] COMPILATION ERROR\n[ERROR] BUILD FAILURE", ""), project);

        String observation = tool.execute(Map.of());

        assertTrue(observation.startsWith("构建失败，未得到测试结论"), observation);
    }

    @Test
    void shouldKeepSurefireAssertionFailureAsTestFailure() {
        RunTestsTool tool = new RunTestsTool(path -> new TestResult(1,
                "[ERROR] ExampleTest.case <<< FAILURE!\n"
                        + "[ERROR] Tests run: 1, Failures: 1, Errors: 0\n[ERROR] BUILD FAILURE", ""), project);

        String observation = tool.execute(Map.of());

        assertTrue(observation.startsWith("测试未通过"), observation);
        assertFalse(observation.startsWith("测试环境失败"), observation);
    }

    @Test
    void shouldPrioritizeFailureAndUsefulStackFramesOverMavenNoise() {
        StringBuilder log = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            log.append("[ERROR] plugin noise ").append(i).append('\n');
        }
        log.append("[ERROR] com.example.CalculatorTest.testAdd <<< FAILURE!\n")
                .append("org.opentest4j.AssertionFailedError: expected: <5> but was: <-1>\n")
                .append("    at org.junit.jupiter.api.AssertEquals.assertEquals(AssertEquals.java:50)\n")
                .append("    at com.example.Calculator.add(Calculator.java:12)\n")
                .append("    at com.example.CalculatorTest.testAdd(CalculatorTest.java:23)\n")
                .append("[ERROR] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0\n")
                .append("[ERROR] BUILD FAILURE\n");
        RunTestsTool tool = new RunTestsTool(path -> new TestResult(1, log.toString(), ""), project);

        String observation = tool.execute(Map.of());

        assertTrue(observation.contains("CalculatorTest.testAdd"), observation);
        assertTrue(observation.contains("Calculator.java:12"), observation);
        assertTrue(observation.contains("CalculatorTest.java:23"), observation);
        assertTrue(observation.contains("Tests run: 2, Failures: 1"), observation);
        assertTrue(observation.indexOf("CalculatorTest.testAdd") < observation.indexOf("plugin noise"), observation);
        assertFalse(observation.contains("AssertEquals.java:50"), observation);
        assertTrue(observation.length() < 4000, "模型反馈应有界：" + observation.length());
    }

    @Test
    void shouldKeepEachFailureWithItsOwnCauseAndStackFrame() {
        String log = """
                [ERROR] com.example.AlphaTest.first <<< FAILURE!
                org.opentest4j.AssertionFailedError: alpha expected 1
                    at com.example.Alpha.check(Alpha.java:11)
                [ERROR] com.example.BetaTest.second <<< ERROR!
                java.lang.IllegalStateException: beta crashed
                    at com.example.Beta.check(Beta.java:22)
                [ERROR] com.example.GammaTest.third <<< FAILURE!
                org.opentest4j.AssertionFailedError: gamma expected 3
                    at com.example.Gamma.check(Gamma.java:33)
                [ERROR] Tests run: 3, Failures: 3, Errors: 0, Skipped: 0
                [ERROR] BUILD FAILURE
                """;
        RunTestsTool tool = new RunTestsTool(path -> new TestResult(1, log, ""), project);

        String observation = tool.execute(Map.of());

        int first = observation.indexOf("失败用例 1/3: com.example.AlphaTest.first");
        int firstCause = observation.indexOf("alpha expected 1");
        int firstFrame = observation.indexOf("Alpha.java:11");
        int second = observation.indexOf("失败用例 2/3: com.example.BetaTest.second");
        int secondCause = observation.indexOf("beta crashed");
        int secondFrame = observation.indexOf("Beta.java:22");
        assertTrue(first >= 0 && first < firstCause && firstCause < firstFrame
                && firstFrame < second && second < secondCause && secondCause < secondFrame, observation);
        assertTrue(observation.contains("另有 1 个失败用例未展示"), observation);
        assertFalse(observation.contains("GammaTest.third"), observation);
        assertTrue(observation.contains("Tests run: 3, Failures: 3"), observation);
        assertTrue(observation.length() < 4000, observation);
    }

    @Test
    void shouldMarkOmittedDiagnosticsWhenFailureCasesAreGrouped() {
        StringBuilder log = new StringBuilder();
        for (int i = 0; i < 9; i++) {
            log.append("[ERROR] plugin noise ").append(i).append('\n');
        }
        log.append("[ERROR] com.example.AlphaTest.first <<< FAILURE!\n")
                .append("org.opentest4j.AssertionFailedError: expected 1\n")
                .append("    at com.example.Alpha.check(Alpha.java:11)\n")
                .append("[ERROR] Tests run: 1, Failures: 1, Errors: 0\n")
                .append("[ERROR] BUILD FAILURE\n");
        RunTestsTool tool = new RunTestsTool(path -> new TestResult(1, log.toString(), ""), project);

        String observation = tool.execute(Map.of());

        assertFalse(observation.contains("plugin noise 8"), observation);
        assertTrue(observation.contains("摘要已截断"), observation);
    }

    @Test
    void shouldKeepSuccessfulTestSummary() {
        RunTestsTool tool = new RunTestsTool(path -> new TestResult(0,
                "Tests run: 3, Failures: 0, Errors: 0, Skipped: 0\nBUILD SUCCESS", ""), project);

        String observation = tool.execute(Map.of());

        assertTrue(observation.contains("Tests run: 3, Failures: 0, Errors: 0"), observation);
        assertTrue(observation.contains("BUILD SUCCESS"), observation);
    }

    @Test
    void shouldKeepPassingSummaryAfterManyDiagnosticLines() {
        StringBuilder log = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            log.append("Caused by: historical diagnostic ").append(i).append('\n');
        }
        log.append("Tests run: 3, Failures: 0, Errors: 0, Skipped: 0\nBUILD SUCCESS\n");
        RunTestsTool tool = new RunTestsTool(path -> new TestResult(0, log.toString(), ""), project);

        String observation = tool.execute(Map.of());

        assertTrue(observation.contains("Tests run: 3, Failures: 0, Errors: 0"), observation);
        assertTrue(observation.contains("BUILD SUCCESS"), observation);
    }

    @Test
    void shouldKeepProjectFrameAfterManyDiagnosticLines() {
        StringBuilder log = new StringBuilder();
        log.append("Tests run: 1, Failures: 1, Errors: 0\n");
        for (int i = 0; i < 50; i++) {
            log.append("Caused by: nested failure ").append(i).append('\n');
        }
        log.append("    at com.example.Calculator.add(Calculator.java:12)\nBUILD FAILURE\n");
        RunTestsTool tool = new RunTestsTool(path -> new TestResult(1, log.toString(), ""), project);

        String observation = tool.execute(Map.of());

        assertTrue(observation.contains("Calculator.java:12"), observation);
        assertTrue(observation.contains("BUILD FAILURE"), observation);
    }

    @Test
    void shouldPreserveStackFrameFromRealSurefireFailure() throws IOException {
        BuggyCalculatorProject.create(project);
        RunTestsTool tool = new RunTestsTool(new MavenTestRunner(), project);

        String observation = tool.execute(Map.of("test", "CalculatorTest"));

        assertTrue(observation.contains("失败用例 1/"), observation);
        assertTrue(observation.contains("CalculatorTest.java:"), observation);
    }
}
