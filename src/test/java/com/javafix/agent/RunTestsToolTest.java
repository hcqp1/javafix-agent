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

        assertTrue(observation.contains("CalculatorTest.java:"), observation);
    }
}
