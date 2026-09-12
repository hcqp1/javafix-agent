package com.javafix.agent;

import com.javafix.agent.tool.ShellTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ShellToolTest {

    @TempDir
    Path project;

    @Test
    void shouldRunCommandInProjectRoot() {

        String output = new ShellTool(project).execute(Map.of("command", "echo javafix"));

        assertTrue(output.contains("javafix"), "命令输出应该被带回，实际：" + output);
        assertTrue(output.contains("退出码 0"), "正常结束应该报告退出码，实际：" + output);
    }

    @Test
    void shouldRejectEmptyCommand() {

        assertThrows(
                IllegalArgumentException.class,
                () -> new ShellTool(project).execute(Map.of())
        );
    }

    @Test
    void shouldReportNonZeroExitCodeInsteadOfThrowing() {

        // 命令跑失败不是「工具坏了」，退出码要如实带回给模型，让它自己决定下一步
        String output = new ShellTool(project).execute(Map.of("command", "exit 3"));

        assertTrue(output.contains("退出码 3"), "非零退出码也要如实返回，实际：" + output);
    }
}
