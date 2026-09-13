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
                    ProcessRunner.run(mavenCommand(projectPath), projectPath, log, timeout);

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
     * 决定用哪条命令跑测试。
     *
     * <p><b>项目自带 Maven Wrapper 时优先用它。</b>真实仓库经常在 pom 里限定 Maven 版本
     * （例如要求 ≥ 3.9.16），拿系统那套 mvn 去跑会因为工具链版本不符而失败——
     * 那种失败和被测的 Bug 毫无关系，却很容易被当成"测试失败"记进评测结果。
     * Wrapper 会把项目要求的那个 Maven 版本拉下来，从根上避免这类误判。
     *
     * <p>Windows 上批处理脚本必须借 cmd 执行，其他平台直接调用即可。
     */
    static List<String> mavenCommand(Path projectPath) {

        if (ProcessRunner.isWindows()) {
            String script = Files.isRegularFile(projectPath.resolve("mvnw.cmd")) ? "mvnw.cmd" : "mvn.cmd";
            return List.of("cmd.exe", "/c", script, "test");
        }

        String script = Files.isRegularFile(projectPath.resolve("mvnw")) ? "./mvnw" : "mvn";
        return List.of(script, "test");
    }
}
