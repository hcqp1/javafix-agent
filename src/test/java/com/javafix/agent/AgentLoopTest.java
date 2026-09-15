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
 * 端到端测试：用预先写好动作序列的假模型驱动四阶段工作流。
 */
class AgentLoopTest {

    @TempDir
    Path project;

    @Test
    void shouldRunThroughAllPhasesAndFixTheBug() throws IOException {

        BuggyCalculatorProject.create(project);

        ScriptedLlmClient llm = new ScriptedLlmClient(
                // 复现：直接跑测试，发现已有一条失败测试，代码据此进入定位
                "TOOL: run_tests\n",
                // 定位：读源码
                "TOOL: read_file\nPATH: src/main/java/com/example/Calculator.java\n",
                // 定位完成，进入修复
                "FINAL: 缺陷在 Calculator.add，把加法写成了减法\n",
                // 修复：改写源码
                """
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
                // 修复完成，进入验证
                "FINAL: 把 Calculator.add 的减法改成了加法\n",
                // 验证：再跑一次，通过后结束
                "TOOL: run_tests\n"
        );

        List<Tool> tools = List.of(
                new ReadFileTool(project),
                new SearchCodeTool(project),
                new WriteFileTool(project),
                new RunTestsTool(new MavenTestRunner(), project)
        );

        AgentLoop loop = new AgentLoop(llm, tools, 8);

        String answer = loop.run("Calculator.add(2, 3) 返回 -1，但期望是 5");

        assertTrue(answer.contains("加法"), "最终答复应该说明改了什么，实际：" + answer);
        assertEquals(6, llm.prompts().size(), "六步脚本应该正好用掉六次模型调用");

        String source = Files.readString(
                project.resolve("src/main/java/com/example/Calculator.java"),
                StandardCharsets.UTF_8
        );
        assertTrue(source.contains("return a + b;"), "文件应该真的被改了，实际内容：\n" + source);

        String trace = String.join("\n", loop.transcript());
        for (String phase : List.of("复现", "定位", "修复", "验证")) {
            assertTrue(trace.contains("[" + phase + "]"), "轨迹里应该走过 " + phase + " 阶段，实际：\n" + trace);
        }
        assertTrue(trace.contains("BUILD SUCCESS"), "最终验证应该跑通，运行轨迹：\n" + trace);

        // 轨迹里不能出现协议语法——模型会把它当模板照抄，前两次翻车都是这个原因
        assertFalse(trace.contains("TOOL:"), "轨迹不该泄露协议形状：\n" + trace);
        assertFalse(trace.contains("ARGS:"), "轨迹不该泄露协议形状：\n" + trace);
    }

    @Test
    void shouldRefuseToWriteMainSourceDuringReproduce() throws IOException {

        BuggyCalculatorProject.create(project);

        ScriptedLlmClient llm = new ScriptedLlmClient(
                "TOOL: write_file\nPATH: src/main/java/com/example/Calculator.java\nCONTENT:\npackage com.example; public class Calculator {}\n"
        );

        AgentLoop loop = new AgentLoop(
                llm,
                List.of(new WriteFileTool(project), new RunTestsTool(new MavenTestRunner(), project)),
                3
        );

        // 脚本只有一条，下一步会因脚本耗尽而抛异常；这里只关心那条写操作被拦住
        assertThrows(RuntimeException.class, () -> loop.run("随便一个现象"));

        String trace = String.join("\n", loop.transcript());
        assertTrue(trace.contains("禁止修改 src/main"), "复现阶段的生产代码写操作要被拦截，实际：\n" + trace);

        String source = Files.readString(
                project.resolve("src/main/java/com/example/Calculator.java"),
                StandardCharsets.UTF_8
        );
        assertTrue(source.contains("return a - b;"), "生产代码不该被修改，实际内容：\n" + source);
    }

    @Test
    void shouldReportStatusInsteadOfThrowingWhenTheBudgetRunsOut() {

        ScriptedLlmClient llm = new ScriptedLlmClient(
                "TOOL: search_code\nQUERY: nothing\n",
                "TOOL: search_code\nQUERY: nothing\n",
                "TOOL: search_code\nQUERY: nothing\n"
        );

        AgentLoop loop = new AgentLoop(llm, List.of(new SearchCodeTool(project)), 3);

        String answer = loop.run("随便一个现象");

        assertTrue(answer.contains("没能完成"), "预算用尽时要给状态报告，实际：" + answer);
        assertTrue(answer.contains("复现"), "报告里要说明停在哪个阶段，实际：" + answer);
        assertTrue(answer.contains("总步数预算"), answer);
    }
}
