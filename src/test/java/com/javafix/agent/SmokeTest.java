package com.javafix.agent;

import com.javafix.agent.tool.ReadFileTool;
import com.javafix.agent.tool.SearchCodeTool;
import com.javafix.agent.tool.ShellTool;
import com.javafix.agent.tool.Tool;
import com.javafix.agent.tool.WriteFileTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 工程骨架冒烟测试：确认 Maven + JUnit 5 链路正常，且工具骨架契约成立。
 */
class SmokeTest {

    @Test
    void mavenAndJUnit5AreWiredUp() {
        assertEquals(4, 2 + 2);
    }

    @Test
    void everyToolExposesNameAndDescription() {
        List<Tool> tools = List.of(
                new ReadFileTool(),
                new SearchCodeTool(),
                new WriteFileTool(),
                new ShellTool()
        );

        for (Tool tool : tools) {
            assertFalse(tool.name().isBlank(), "tool name must not be blank");
            assertFalse(tool.description().isBlank(), "tool description must not be blank");
        }
    }
}
