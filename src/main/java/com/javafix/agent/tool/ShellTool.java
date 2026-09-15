package com.javafix.agent.tool;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * 在仓库根目录执行一条 shell 命令。
 *
 * <p>它和 {@link MavenTestRunner} 的边界：这里是通用能力（看 git 状态、列目录、
 * 跑任意命令），而 {@link MavenTestRunner} 的价值在于把测试结果结构化，
 * 能区分「测试失败」「启动失败」「超时」。两者共用同一套进程处理逻辑，
 * 但对外暴露的语义不同。
 *
 * <p>默认超时比构建短得多：shell 命令通常是查看信息这类快操作，
 * 真要跑构建，应该走专门的测试工具。
 */
public class ShellTool extends ProjectTool {

    /** 单条 shell 命令默认允许占用的最长时间。 */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);

    private final Duration timeout;

    public ShellTool(Path root) {
        this(root, DEFAULT_TIMEOUT);
    }

    public ShellTool(Path root, Duration timeout) {
        super(root);
        this.timeout = timeout;
    }

    @Override
    public String name() {
        return "shell";
    }

    @Override
    public String description() {
        // 模型默认会写 Linux 命令，而这里可能是 Windows，所以把平台说清楚
        boolean windows = ProcessRunner.isWindows();
        return "在仓库根目录执行一条 shell 命令并返回输出。参数：command（要执行的命令）。"
                + "当前平台是 " + (windows ? "Windows" : "类 Unix") + "，命令必须符合该平台的语法"
                + (windows ? "（例如 dir、findstr、where，而不是 ls、grep、find、head）" : "")
                + "。只是想查看仓库里的文件时，优先用 search_code 和 read_file，不要绕道 shell。";
    }

    @Override
    public String execute(Map<String, String> arguments) {

        rejectUnknownArguments(arguments, "command");

        String command = arguments.get("command");

        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command is required");
        }

        Path log;
        try {
            // 放系统临时目录：shell 命令的输出不值得污染被测仓库，
            // 而且这类命令的日志事后价值不大，用完即删
            log = Files.createTempFile("javafix-shell-", ".log");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        try {
            ProcessRunner.Result result =
                    ProcessRunner.run(ProcessRunner.shellCommand(command), root, log, timeout);

            if (result.timedOut()) {
                return "命令超过 " + timeout.toMillis() + " ms 未结束，已被终止。已产生的输出：\n"
                        + result.output();
            }

            return "退出码 " + result.exitCode() + "\n" + result.output();

        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("命令执行被中断", e);
        } finally {
            try {
                Files.deleteIfExists(log);
            } catch (IOException ignored) {
                // 临时文件删不掉不值得让整条命令失败
            }
        }
    }
}
