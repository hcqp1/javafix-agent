package com.javafix.agent.tool;

import com.javafix.agent.core.TestResult;
import com.javafix.agent.core.TestRunner;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.stream.Collectors;

public class MavenTestRunner implements TestRunner {

    @Override
    public TestResult run(Path projectPath) {

        ProcessBuilder processBuilder =
                new ProcessBuilder(
                        "cmd.exe",
                        "/c",
                        "mvn.cmd",
                        "test"
                );

        // 指定在哪个项目目录执行 mvn test
        processBuilder.directory(projectPath.toFile());

        // 暂时把 stderr 合并到 stdout
        processBuilder.redirectErrorStream(true);

        try {
            // 1. 启动 Maven 进程
            Process process = processBuilder.start();

            // 2. 读取 Maven 输出
            String output;

            try (BufferedReader reader =
                         new BufferedReader(
                                 new InputStreamReader(process.getInputStream()))) {

                output = reader.lines()
                        .collect(Collectors.joining(System.lineSeparator()));
            }

            // 3. 等待 Maven 执行完成，并拿到退出码
            int exitCode = process.waitFor();

            // 4. 返回测试结果
            return new TestResult(
                    exitCode,
                    output,
                    ""
            );

        } catch (IOException e) {

            return new TestResult(
                    -1,
                    "",
                    e.getMessage()
            );

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();

            return new TestResult(
                    -1,
                    "",
                    "Maven execution interrupted: " + e.getMessage()
            );
        }
    }
}