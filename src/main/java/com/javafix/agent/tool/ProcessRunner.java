package com.javafix.agent.tool;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 执行外部命令的公共逻辑：输出落盘、限时等待、超时终止整棵进程树。
 *
 * <p>抽出来是因为 {@link MavenTestRunner} 和 {@link ShellTool} 需要的是同一件事。
 * 这类「进程处理」的代码看着简单，其实坑都在细节上（管道死锁、进程树、字符集），
 * 复制一份就意味着以后有两个地方要同时修对——所以宁可先抽出来，也不要先复制。
 */
final class ProcessRunner {

    /** 强制终止之后，等待主进程退出的时间。 */
    private static final Duration TERMINATION_GRACE = Duration.ofSeconds(5);

    private ProcessRunner() {
    }

    /**
     * 一次命令执行的结果。
     *
     * @param exitCode 命令的退出码；{@code timedOut} 为 true 时没有意义
     * @param timedOut 是否因为超时被强制终止
     * @param output   已经产生的输出（超时也保留）
     */
    record Result(int exitCode, boolean timedOut, String output) {
    }

    /**
     * 在指定目录执行命令，输出重定向到日志文件并限时等待。
     *
     * <p>输出走文件而不是管道，是为了避开管道缓冲区写满导致的死锁——
     * 一旦走了管道，就必须一边读一边消费，而阻塞读取会让超时根本没机会生效。
     *
     * @throws IOException          命令无法启动
     * @throws InterruptedException 等待期间线程被中断（子进程仍然会被终止）
     */
    static Result run(List<String> command, Path workingDirectory, Path logFile, Duration timeout)
            throws IOException, InterruptedException {

        ProcessBuilder builder = new ProcessBuilder(command);

        builder.directory(workingDirectory.toFile());

        // 错误信息通常也在 stderr 上，统一收进同一个日志
        builder.redirectErrorStream(true);

        if (logFile.getParent() != null) {
            Files.createDirectories(logFile.getParent());
        }

        builder.redirectOutput(logFile.toFile());

        Process process = builder.start();

        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                terminate(process);
                return new Result(-1, true, readLog(logFile));
            }

            return new Result(process.exitValue(), false, readLog(logFile));

        } catch (InterruptedException e) {
            // 被中断时子进程还在跑，不能就这么丢下它
            terminate(process);
            throw e;
        }
    }

    /** 把命令交给当前平台的 shell 执行。 */
    static List<String> shellCommand(String command) {
        return isWindows()
                ? List.of("cmd.exe", "/c", command)
                : List.of("/bin/sh", "-c", command);
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * 读取子进程留下的日志。
     *
     * <p>字符集显式指定为系统本地编码：子进程写出来的就是它，
     * 而 JVM 默认字符集是 UTF-8，不指定会把中文读成乱码。
     */
    static String readLog(Path logFile) {

        try {
            if (Files.exists(logFile)) {
                return Files.readString(logFile, consoleCharset());
            }
        } catch (IOException e) {
            return "failed to read log " + logFile + ": " + e;
        }

        return "";
    }

    private static Charset consoleCharset() {
        return Charset.forName(
                System.getProperty("native.encoding", Charset.defaultCharset().name())
        );
    }

    /**
     * 终止整棵进程树：先杀子孙，再杀作为外壳的父进程。
     *
     * <p>只终止直接子进程是不够的——比如 {@code cmd /c mvn.cmd}，
     * cmd 只是外壳，Maven 和它 fork 出的测试 JVM 都是它的子孙，会变成孤儿继续跑。
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
}
