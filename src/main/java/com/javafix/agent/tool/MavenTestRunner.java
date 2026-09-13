package com.javafix.agent.tool;

import com.javafix.agent.core.TestResult;
import com.javafix.agent.core.TestRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 在目标项目根目录执行 {@code mvn test}，把测试结果带回给 Agent。
 *
 * <p>执行与超时、进程树、字符集这些细节都交给 {@link ProcessRunner}；
 * 这个类只负责两件自己的事：把结果翻译成 {@link TestResult} 的退出码语义，
 * 以及把日志留在被测项目的 {@code target} 下方便事后回看。
 */
public class MavenTestRunner implements TestRunner {

    /**
     * 单次 {@code mvn test} 默认允许占用的最长时间。
     *
     * <p>15 分钟这个值是拿真实仓库换来的：在一份普通规模的真实项目上做一次全量构建要六到十几分钟
     * （首次还要下载依赖、编译上千个测试类）。原来 5 分钟的默认值会让真实仓库样本几乎必然
     * 被误判成超时——**那种失败和被测的 Bug 毫无关系，却会污染整份评测结果**，
     * 正是我们最不想要的假失败。小项目可以自己传更短的超时。
     */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(15);

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
        return run(projectPath, null);
    }

    @Override
    public TestResult run(Path projectPath, String testSelector) {

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
                    ProcessRunner.run(mavenCommand(projectPath, testSelector), projectPath, log, timeout);

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
        return mavenCommand(projectPath, null);
    }

    /**
     * 同上，但可以只跑一个测试类。
     *
     * <p>把 -Dtest 交给 Agent 自己指定，而不是写死在工具里：它才知道自己正在验证什么。
     */
    static List<String> mavenCommand(Path projectPath, String testSelector) {

        List<String> command = new ArrayList<>();

        if (ProcessRunner.isWindows()) {
            command.add("cmd.exe");
            command.add("/c");
            String script = Files.isRegularFile(projectPath.resolve("mvnw.cmd")) ? "mvnw.cmd" : "mvn.cmd";
            command.add(script);
        } else {
            command.add(Files.isRegularFile(projectPath.resolve("mvnw")) ? "./mvnw" : "mvn");
        }

        if (testSelector != null && !testSelector.isBlank()) {
            command.add("-Dtest=" + testSelector);
            command.add("-DfailIfNoTests=false");
        }

        command.add("test");
        return command;
    }
}
