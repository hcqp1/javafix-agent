package com.javafix.agent.core;

/**
 * 一次测试执行的结果。
 *
 * <p>退出码约定：非负值是构建工具自己给出的退出码（0 表示成功），
 * 负值由 JavaFix 自己定义，用来表示「压根没拿到测试结果」的情况。
 */
public class TestResult {

    /** 未能拿到测试结果：子进程启动失败（路径不存在、Maven 未安装等）或执行被中断。 */
    public static final int EXIT_START_FAILURE = -1;

    /** 执行超时：子进程已被强制终止。 */
    public static final int EXIT_TIMEOUT = -2;

    private final int exitCode;
    private final String stdout;
    private final String stderr;

    public TestResult(int exitCode, String stdout, String stderr) {
        this.exitCode = exitCode;
        this.stdout = stdout;
        this.stderr = stderr;
    }

    public int getExitCode() {
        return exitCode;
    }

    public String getStdout() {
        return stdout;
    }

    public String getStderr() {
        return stderr;
    }

    public boolean isSuccess() {
        return exitCode == 0;
    }
}
