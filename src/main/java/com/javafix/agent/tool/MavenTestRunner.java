package com.javafix.agent.tool;

import com.javafix.agent.core.TestResult;
import com.javafix.agent.core.TestRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * 在目标项目根目录执行 {@code mvn test}，把测试结果带回给 Agent。
 *
 * <p>执行与超时、进程树、字符集这些细节都交给 {@link ProcessRunner}；
 * 这个类只负责两件自己的事：把结果翻译成 {@link TestResult} 的退出码语义，
 * 以及把日志留在被测项目的 {@code target} 下方便事后回看。
 */
public class MavenTestRunner implements TestRunner {

    /** 单次 {@code mvn test} 默认允许占用的最长时间。 */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    /** 日志相对目标项目根目录的位置，超时或失败之后仍可回看。 */
    private static final String LOG_FILE = "target/javafix-mvn-test.log";

    private final Duration timeout;

    public MavenTestRunner() {
        this(DEFAULT_TIMEOUT);
    }

    /** 允许指定超时，测试里用它来验证超时分支。 */
    public MavenTestRunner(Duration timeout) {
        this.timeout = timeout;
    }

    @Override
    public TestResult run(Path projectPath) {

        if (!Files.isDirectory(projectPath)) {
            return new TestResult(
                    TestResult.EXIT_START_FAILURE,
                    "",
                    "project path is not a directory: " + projectPath
            );
        }

        Path log = projectPath.resolve(LOG_FILE);

        try {
            ProcessRunner.Result result =
                    ProcessRunner.run(mavenCommand(), projectPath, log, timeout);

            if (result.timedOut()) {
                return new TestResult(
                        TestResult.EXIT_TIMEOUT,
                        result.output(),
                        "mvn test timed out after " + timeout.toMillis()
                                + " ms and was terminated: " + projectPath
                );
            }

            return new TestResult(result.exitCode(), result.output(), "");

        } catch (IOException e) {
            return new TestResult(
                    TestResult.EXIT_START_FAILURE,
                    ProcessRunner.readLog(log),
                    e.toString()
            );

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new TestResult(
                    TestResult.EXIT_START_FAILURE,
                    ProcessRunner.readLog(log),
                    "Maven execution interrupted: " + e
            );
        }
    }

    /**
     * Windows 上 {@code mvn.cmd} 是批处理脚本，必须借 cmd 执行；
     * 其他平台直接调用 {@code mvn} 即可。
     */
    private static List<String> mavenCommand() {
        return ProcessRunner.isWindows()
                ? List.of("cmd.exe", "/c", "mvn.cmd", "test")
                : List.of("mvn", "test");
    }
}
