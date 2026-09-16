package com.javafix.agent;

import com.javafix.agent.tool.ReplaceInFileTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 局部替换工具。
 *
 * <p>它存在的原因：write_file 只能整文件覆盖，而真实仓库的文件动辄三万多字符、
 * 观察结果又被截断——模型读不全就永远不敢写，真实运行里卡死在这上面。
 */
class ReplaceInFileToolTest {

    @TempDir
    Path project;

    @Test
    void shouldReplaceAUniqueSnippet() throws IOException {

        Path file = writeSource();

        String result = new ReplaceInFileTool(project).execute(Map.of(
                "path", "src/main/java/com/example/Calculator.java",
                "find", "return a - b;",
                "replace", "return a + b;"
        ));

        assertTrue(result.contains("已替换"), result);
        assertTrue(Files.readString(file).contains("return a + b;"), "文件内容应该被改掉");
    }

    @Test
    void shouldRefuseWhenTheSnippetIsNotFound() throws IOException {

        writeSource();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
                new ReplaceInFileTool(project).execute(Map.of(
                        "path", "src/main/java/com/example/Calculator.java",
                        "find", "return a * b;",
                        "replace", "return a + b;"
                )));

        assertTrue(failure.getMessage().contains("找不到"), failure.getMessage());
    }

    @Test
    void shouldRefuseWhenTheSnippetIsAmbiguous() throws IOException {

        Path file = project.resolve("Sample.java");
        Files.writeString(file, "int x;\nint x;\n");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
                new ReplaceInFileTool(project).execute(Map.of(
                        "path", "Sample.java",
                        "find", "int x;",
                        "replace", "int y;"
                )));

        assertTrue(failure.getMessage().contains("2 次"), "多处命中要说清楚，让模型补上下文：" + failure.getMessage());
    }

    @Test
    void shouldRejectUnknownArguments() {

        assertThrows(IllegalArgumentException.class, () ->
                new ReplaceInFileTool(project).execute(Map.of("path", "a.txt", "oldText", "x")));
    }

    private Path writeSource() throws IOException {

        Path file = project.resolve("src/main/java/com/example/Calculator.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                package com.example;

                public class Calculator {

                    public int add(int a, int b) {
                        return a - b;
                    }
                }
                """);

        return file;
    }
}
