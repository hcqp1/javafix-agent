package com.javafix.agent.tool;

import com.javafix.agent.core.TestResult;
import com.javafix.agent.core.TestRunner;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 在目标项目根目录执行 {@code mvn test}，把测试结果带回给 Agent。
 *
 * <p>几个不那么显然的设计点：
 *
 * <ul>
 *   <li><b>输出落盘而不是走管道。</b>管道会被写满，必须一边读一边消费；
 *       而读取本身是阻塞的，一旦 mvn 卡住，线程会卡在读上，连超时都没机会触发。
 *       直接重定向到文件既绕开了死锁，又顺手留下了完整日志。</li>
 *
 *   <li><b>超时要杀整棵进程树。</b>命令是 {@code cmd.exe /c mvn.cmd}，cmd 只是外壳：
 *       真正的 Maven 进程、以及它 fork 出的 surefire 测试 JVM 都是它的子进程。
 *       只杀直接子进程，会留下继续占着 CPU 和端口的孤儿进程。</li>
 *
 *   <li><b>字符集必须显式指定。</b>Maven 是 Java 进程，输出用的是系统本地编码
 *       （中文 Windows 上是 GBK），而 JVM 默认字符集是 UTF-8，不指定就会把中文读成乱码。</li>
 *
 *   <li><b>结果要能区分病因。</b>测试失败给构建工具的退出码，启动失败与超时给
 *       {@link TestResult#EXIT_START_FAILURE} / {@link TestResult#EXIT_TIMEOUT}。</li>
 * </ul>
 */
public class MavenTestRunner implements TestRunner {

    /** 单次 {@code mvn test} 默认允许占用的最长时间。 */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    /** 强制终止之后，等待主进程退出的时间。 */
    private static final Duration TERMINATION_GRACE = Duration.ofSeconds(5);

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

        ProcessBuilder processBuilder =
                new ProcessBuilder(
                        "cmd.exe",
                        "/c",
                        "mvn.cmd",
                        "test"
                );

        // 指定在哪个项目目录执行 mvn test
        processBuilder.directory(projectPath.toFile());

        // Maven 的错误信息基本都走 stderr，这里统一收进日志文件
        processBuilder.redirectErrorStream(true);

        Process process = null;

        try {
            Files.createDirectories(log.getParent());

            // 关键：输出直接写文件，不走管道
            processBuilder.redirectOutput(log.toFile());

            // 1. 启动 Maven 进程
            process = processBuilder.start();

            // 2. 限时等待，超时就把整棵进程树收掉
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {

                terminate(process);

                return new TestResult(
                        TestResult.EXIT_TIMEOUT,
                        readLog(log),
                        "mvn test timed out after " + timeout.toMillis()
                                + " ms and was terminated: " + projectPath
                );
            }

            // 3. 返回测试结果（包含已经写进日志的全部输出）
            return new TestResult(
                    process.exitValue(),
                    readLog(log),
                    ""
            );

        } catch (IOException e) {

            return new TestResult(
                    TestResult.EXIT_START_FAILURE,
                    readLog(log),
                    e.toString()
            );

        } catch (InterruptedException e) {

            // 被中断时子进程还在跑，不能就这么丢下它
            if (process != null) {
                terminate(process);
            }

            Thread.currentThread().interrupt();

            return new TestResult(
                    TestResult.EXIT_START_FAILURE,
                    readLog(log),
                    "Maven execution interrupted: " + e
            );
        }
    }

    /**
     * 终止整棵进程树：先杀子孙进程，再杀 cmd 外壳本身。
     *
     * <p>Windows 上也可以直接用 {@code taskkill /PID <pid> /T /F}，
     * 这里选择 {@link ProcessHandle} 是为了不额外起进程，且逻辑不依赖平台。
     */
    private static void terminate(Process process) {

        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();

        try {
            process.waitFor(TERMINATION_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 读取子进程留下的日志。
     *
     * <p>超时和启动失败时同样返回已产生的输出——只报「失败」而不给任何线索，
     * 会让上层（以及后面的 Agent）无从判断发生了什么。
     */
    private static String readLog(Path log) {

        try {
            if (Files.exists(log)) {
                return Files.readString(log, consoleCharset());
            }
        } catch (IOException e) {
            return "failed to read maven log " + log + ": " + e;
        }

        return "";
    }

    /**
     * 子进程输出使用的字符集。
     *
     * <p>Maven 和我们跑在同一台机器上，它写出来的就是系统本地编码，
     * 所以这里跟随 {@code native.encoding}，而不是 JVM 默认的 UTF-8。
     */
    private static Charset consoleCharset() {
        return Charset.forName(
                System.getProperty("native.encoding", Charset.defaultCharset().name())
        );
    }
}
