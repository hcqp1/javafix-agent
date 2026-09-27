package com.javafix.agent;

import com.javafix.agent.tool.MavenTestRunner;
import com.javafix.agent.tool.RunTestsTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跑测试的工具把「超时」和「跑完了但失败」分开报告。
 *
 * <p>这条区分很要紧：超时的运行会带回一段"目前看起来都在通过"的日志，
 * 如果不单独标记，一次没跑完的测试可能被当成"验证通过"。
 */
class RunTestsToolTest {

    @TempDir
    Path project;

    @Test
    void shouldReportTimeoutsAsTimeouts() throws IOException {

        BuggyCalculatorProject.create(project);

        RunTestsTool tool = new RunTestsTool(new MavenTestRunner(Duration.ofMillis(1)), project);

        String observation = tool.execute(Map.of());

        assertTrue(observation.startsWith("测试超时"), "超时必须单独标记，实际：" + observation);
    }
}
