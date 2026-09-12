package com.javafix.agent;

import com.javafix.agent.core.AgentLoop;
import com.javafix.agent.tool.MavenTestRunner;
import com.javafix.agent.tool.ReadFileTool;
import com.javafix.agent.tool.RunTestsTool;
import com.javafix.agent.tool.SearchCodeTool;
import com.javafix.agent.tool.Tool;
import com.javafix.agent.tool.WriteFileTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端测试：用预先写好动作序列的假模型，驱动整条管道把一个真实的 Bug 修掉。
 *
 * <p>不依赖网络和真模型，但跑的是真的 Maven 构建——它验证的是
 * 「循环 → 工具 → 测试反馈」这条链路真的能跑通，而不是各个部件单独看起来都没问题。
 */
class AgentLoopTest {

    @TempDir
    Path project;

    @Test
    void shouldFixBuggyCalculatorEndToEnd() throws IOException {

        BuggyCalculatorProject.create(project);

        // 一个真实 Agent 大概会走的四步：先看代码、改、验证、汇报
        ScriptedLlmClient llm = new ScriptedLlmClient(
                """
                THOUGHT: 先看看 Calculator 的实现
                TOOL: read_file
                PATH: src/main/java/com/example/Calculator.java
                """,
                """
                THOUGHT: add 被写成了减法，应该是加法
                TOOL: write_file
                PATH: src/main/java/com/example/Calculator.java
                CONTENT:
                package com.example;

                public class Calculator {

                    public int add(int a, int b) {
                        return a + b;
                    }
                }
                """,
                """
                THOUGHT: 跑一遍测试确认修好了
                TOOL: run_tests
                """,
                """
                THOUGHT: 测试通过了，可以收工
                FINAL: 把 Calculator.add 里的减法改成了加法，测试已经变绿。
                """
        );

        List<Tool> tools = List.of(
                new ReadFileTool(project),
                new SearchCodeTool(project),
                new WriteFileTool(project),
                new RunTestsTool(new MavenTestRunner(), project)
        );

        AgentLoop loop = new AgentLoop(llm, tools, 8);

        String answer = loop.run("Calculator.add(2, 3) 返回 -1，但期望是 5。请修复。");

        assertTrue(answer.contains("加法"), "最终答复应该说明改了什么，实际：" + answer);

        assertEquals(4, llm.prompts().size(), "四步脚本应该正好用掉四次模型调用");

        String source = Files.readString(
                project.resolve("src/main/java/com/example/Calculator.java"),
                StandardCharsets.UTF_8
        );
        assertTrue(source.contains("return a + b;"), "文件应该真的被改了，实际内容：\n" + source);

        // 最强的一条断言：循环里真的跑了 mvn test，而且这次构建是成功的
        String trace = String.join("\n", loop.transcript());
        assertTrue(trace.contains("BUILD SUCCESS"), "修复之后测试应该跑通，运行轨迹：\n" + trace);
    }

    @Test
    void shouldFeedToolErrorsBackToTheModelInsteadOfFailing() {

        ScriptedLlmClient llm = new ScriptedLlmClient(
                """
                THOUGHT: 试试一个不存在的工具
                TOOL: no_such_tool
                """,
                """
                THOUGHT: 换个真实存在的工具
                TOOL: search_code
                QUERY: nothing-here
                """,
                """
                THOUGHT: 确认仓库里没有这个内容，收工
                FINAL: 没有找到相关内容。
                """
        );

        AgentLoop loop = new AgentLoop(llm, List.of(new SearchCodeTool(project)), 5);

        String answer = loop.run("看看仓库里有没有 nothing-here");

        assertEquals("没有找到相关内容。", answer);

        String trace = String.join("\n", loop.transcript());
        assertTrue(
                trace.contains("没有名为 no_such_tool 的工具"),
                "未知工具要作为观察结果回灌给模型，实际：\n" + trace
        );
    }
}
