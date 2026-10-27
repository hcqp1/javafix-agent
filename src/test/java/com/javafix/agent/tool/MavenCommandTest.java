package com.javafix.agent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.time.Duration;

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

    @Test
    void passesEnvironmentToChildWithoutChangingParentOrImmutableOverrides() throws Exception {
        String before = System.getenv("JAVAFIX_TRACE_FILE");
        Map<String, String> overrides = Map.of("JAVAFIX_TRACE_FILE", "trace-path");
        String script = isWindows() ? "echo %JAVAFIX_TRACE_FILE% & echo %PATH%" : "printf '%s\\n%s' \"$JAVAFIX_TRACE_FILE\" \"$PATH\"";
        ProcessRunner.Result result = ProcessRunner.run(ProcessRunner.shellCommand(script), project,
                project.resolve("child.log"), Duration.ofSeconds(10), overrides);
        assertEquals(0, result.exitCode());
        assertTrue(result.output().startsWith("trace-path"));
        assertTrue(result.output().contains(System.getenv("PATH")));
        assertEquals(before, System.getenv("JAVAFIX_TRACE_FILE"));
        assertEquals(Map.of("JAVAFIX_TRACE_FILE", "trace-path"), overrides);
    }

    @Test
    void mavenOverloadPreservesSelectorAndPropagatesEnvironment() throws Exception {
        Path wrapper = project.resolve(wrapperName());
        Files.writeString(wrapper, isWindows()
                ? "@echo off\r\necho %JAVAFIX_TRACE_FILE%\r\necho %*\r\n"
                : "#!/bin/sh\nprintf '%s\\n' \"$JAVAFIX_TRACE_FILE\" \"$*\"\n");
        if (!isWindows()) assertTrue(wrapper.toFile().setExecutable(true));
        List<String> command = MavenTestRunner.mavenCommand(project, "FixtureTest#fails");
        assertEquals(List.of("-Dtest=FixtureTest#fails", "-DfailIfNoTests=false", "test"),
                command.subList(command.size() - 3, command.size()));
        var result = new MavenTestRunner(Duration.ofSeconds(10)).run(project, "FixtureTest#fails",
                Map.of("JAVAFIX_TRACE_FILE", "trace-path"));
        assertEquals(0, result.getExitCode());
        assertTrue(result.getStdout().contains("trace-path"));
        assertTrue(result.getStdout().contains("-Dtest=FixtureTest#fails -DfailIfNoTests=false test"));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
