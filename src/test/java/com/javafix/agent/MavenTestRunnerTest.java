package com.javafix.agent;

import com.javafix.agent.core.TestResult;
import com.javafix.agent.tool.MavenTestRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 集成测试：会真的启动 Maven 进程，因此比单元测试慢，
 * 且临时项目第一次构建时需要能访问 Maven 仓库。
 */
class MavenTestRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldDetectFailedMavenTest() throws IOException {

        Path project = BuggyCalculatorProject.create(tempDir);

        TestResult result = new MavenTestRunner().run(project);

        assertFalse(result.isSuccess(), "失败的测试不能被报告成成功");
        assertEquals(1, result.getExitCode(), "Maven 构建失败时的退出码是 1");

        // 刻意断言到「哪个测试失败了」，而不是只断言“Maven 失败了”：
        // 依赖下载失败、pom 写错同样会给出 BUILD FAILURE，但那不是本测试要证明的东西
        assertTrue(
                result.getStdout().contains("Tests run: 1, Failures: 1"),
                "期望看到 surefire 的失败汇总，实际输出：\n" + result.getStdout()
        );

        assertTrue(
                result.getStdout().contains("expected: <5> but was: <-1>"),
                "期望看到 Calculator 的断言失败信息，实际输出：\n" + result.getStdout()
        );
    }

    @Test
    void shouldGiveUpWhenMavenTakesTooLong() throws IOException {

        Path project = BuggyCalculatorProject.create(tempDir);

        // 400ms 远小于任何一次真实的 mvn test，必然走到超时分支
        MavenTestRunner runner = new MavenTestRunner(Duration.ofMillis(400));

        TestResult result = runner.run(project);

        assertFalse(result.isSuccess());
        assertEquals(TestResult.EXIT_TIMEOUT, result.getExitCode());
        assertTrue(
                result.getStderr().contains("timed out"),
                "超时要给出可识别的原因，实际：" + result.getStderr()
        );
        assertNotNull(result.getStdout(), "即使超时，也要返回已经产生的输出");
    }

    @Test
    void shouldFailFastWhenProjectDirectoryIsMissing() {

        TestResult result = new MavenTestRunner().run(tempDir.resolve("does-not-exist"));

        assertEquals(TestResult.EXIT_START_FAILURE, result.getExitCode());
        assertTrue(
                result.getStderr().contains("not a directory"),
                "路径不存在要直接说清楚，实际：" + result.getStderr()
        );
    }
}
