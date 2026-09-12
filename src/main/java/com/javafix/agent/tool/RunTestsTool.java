package com.javafix.agent.tool;

import com.javafix.agent.core.TestResult;
import com.javafix.agent.core.TestRunner;

import java.nio.file.Path;
import java.util.Map;

/**
 * 把 {@link TestRunner} 包装成一个工具，让 Agent 能自己触发测试。
 *
 * <p>测试结果是这个项目里最重要的反馈信号：它决定 Agent 下一步改哪里。
 *
 * <p>V0 先把完整构建输出原样交给模型。等接了真模型、能观察到真实任务里的输出之后，
 * 再在这里做两件还没定的事：把日志提炼成摘要（完整日志已经落盘到 target 下），
 * 以及区分「编译失败」和「测试失败」——这两种情况的修复策略完全不同。
 */
public class RunTestsTool extends ProjectTool {

    private final TestRunner testRunner;

    public RunTestsTool(TestRunner testRunner, Path root) {
        super(root);
        this.testRunner = testRunner;
    }

    @Override
    public String name() {
        return "run_tests";
    }

    @Override
    public String description() {
        return "在仓库根目录执行 mvn test，返回构建与测试的输出。参数：无。";
    }

    @Override
    public String execute(Map<String, String> arguments) {

        TestResult result = testRunner.run(root);

        StringBuilder observation = new StringBuilder();

        observation.append(result.isSuccess() ? "测试通过。" : "测试未通过。");
        observation.append("（退出码 ").append(result.getExitCode()).append("）\n");
        observation.append(result.getStdout());

        if (result.getStderr() != null && !result.getStderr().isBlank()) {
            observation.append("\n").append(result.getStderr());
        }

        return observation.toString();
    }
}
