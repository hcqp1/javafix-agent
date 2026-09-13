package com.javafix.agent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 只验证"选哪条命令"，不真的启动 Maven——跑真实 wrapper 会去下载一整套 Maven，
 * 对单元测试来说代价太大。
 */
class MavenCommandTest {

    @TempDir
    Path project;

    @Test
    void shouldPreferProjectWrapperWhenPresent() throws IOException {

        Files.writeString(project.resolve(wrapperName()), "");

        List<String> command = MavenTestRunner.mavenCommand(project);

        assertTrue(
                String.join(" ", command).contains(wrapperName()),
                "项目自带 wrapper 时应该用它，实际：" + command
        );
        assertEquals("test", command.get(command.size() - 1));
    }

    @Test
    void shouldFallBackToSystemMavenWhenThereIsNoWrapper() {

        List<String> command = MavenTestRunner.mavenCommand(project);
        String joined = String.join(" ", command);

        assertFalse(joined.contains("mvnw"), "没有 wrapper 时不该编出一条不存在的命令，实际：" + joined);
        assertEquals("test", command.get(command.size() - 1));
    }

    private static String wrapperName() {
        return isWindows() ? "mvnw.cmd" : "mvnw";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
