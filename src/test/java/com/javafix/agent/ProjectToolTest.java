package com.javafix.agent;

import com.javafix.agent.tool.ReadFileTool;
import com.javafix.agent.tool.WriteFileTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 工具参数的边界测试。
 *
 * <p>工具参数是模型给的，不能无条件信任：一个写错的相对路径就能让 Agent
 * 改到仓库外面去。这里验证越界路径会被拒绝。
 */
class ProjectToolTest {

    @TempDir
    Path project;

    @Test
    void shouldRejectReadOutsideProjectRoot() {

        assertThrows(
                IllegalArgumentException.class,
                () -> new ReadFileTool(project).execute(Map.of("path", "../outside.txt"))
        );
    }

    @Test
    void shouldRejectWriteOutsideProjectRoot() {

        assertThrows(
                IllegalArgumentException.class,
                () -> new WriteFileTool(project).execute(
                        Map.of("path", "../evil.txt", "content", "boom")
                )
        );
    }

    @Test
    void shouldRejectBlankPath() {

        assertThrows(
                IllegalArgumentException.class,
                () -> new ReadFileTool(project).execute(Map.of("path", "   "))
        );
    }
}
