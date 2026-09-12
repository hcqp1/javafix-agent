package com.javafix.agent;

import com.javafix.agent.tool.ReadFileTool;
import com.javafix.agent.tool.SearchCodeTool;
import com.javafix.agent.tool.ShellTool;
import com.javafix.agent.tool.Tool;
import com.javafix.agent.tool.WriteFileTool;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工程冒烟测试：确认 Maven + JUnit 5 链路正常，且工具契约成立。
 */
class SmokeTest {

    @Test
    void mavenAndJUnit5AreWiredUp() {
        assertEquals(4, 2 + 2);
    }

    @Test
    void everyToolExposesNameAndDescription() {

        Path root = Path.of(".");

        List<Tool> tools = List.of(
                new ReadFileTool(root),
                new SearchCodeTool(root),
                new WriteFileTool(root),
                new ShellTool()
        );

        Set<String> names = new HashSet<>();

        for (Tool tool : tools) {
            assertFalse(tool.name().isBlank(), "tool name must not be blank");
            assertFalse(tool.description().isBlank(), "tool description must not be blank");
            assertTrue(names.add(tool.name()), "tool name must be unique: " + tool.name());
        }
    }
}
