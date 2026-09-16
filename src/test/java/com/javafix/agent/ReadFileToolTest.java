package com.javafix.agent;

import com.javafix.agent.tool.ReadFileTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 分段读文件。
 *
 * <p>大文件一次读不下（观察结果会截断），必须能按行区间读——否则模型既看不全、
 * 也不敢动手改。
 */
class ReadFileToolTest {

    @TempDir
    Path project;

    @Test
    void shouldReadOnlyTheRequestedLines() throws IOException {

        Files.writeString(project.resolve("a.txt"), "l1\nl2\nl3\nl4\n");

        String result = new ReadFileTool(project)
                .execute(Map.of("path", "a.txt", "start", "2", "end", "3"));

        assertTrue(result.contains("第 2-3 行"), result);
        assertTrue(result.contains("l2") && result.contains("l3"), result);
        assertFalse(result.contains("l4"), "区间之外的内容不该出现：" + result);
    }

    @Test
    void shouldReadTheWholeFileWhenNoRangeIsGiven() throws IOException {

        Files.writeString(project.resolve("a.txt"), "l1\nl2\n");

        String result = new ReadFileTool(project).execute(Map.of("path", "a.txt"));

        assertTrue(result.contains("l1") && result.contains("l2"), result);
    }

    @Test
    void shouldRejectOutOfRangeLines() throws IOException {

        Files.writeString(project.resolve("a.txt"), "l1\nl2\n");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
                new ReadFileTool(project).execute(Map.of("path", "a.txt", "start", "5", "end", "9")));

        assertTrue(failure.getMessage().contains("行号超出范围"), failure.getMessage());
    }
}
