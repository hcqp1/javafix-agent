package com.javafix.agent.tool;

import com.javafix.agent.core.TestResult;
import com.javafix.agent.core.TestRunner;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

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

    /** 摘要里最多保留多少行。 */
    private static final int MAX_LINES = 40;

    /**
     * 从完整日志里挑出对修复有用的行：测试汇总、构建结果、编译错误、断言信息、异常链。
     * 其余大部分是插件下载与生命周期输出，对定位问题没有帮助。
     */
    private static final Pattern IMPORTANT = Pattern.compile(
            "Tests run:|BUILD SUCCESS|BUILD FAILURE|\\[ERROR\\]|expected:|Caused by:|AssertionFailed|COMPILATION ERROR"
    );

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
        return "在仓库根目录执行 mvn test，返回构建与测试的输出。"
                + "参数：test（可选，只跑指定的测试类，例如 FooTest；真实仓库务必用它把范围收窄，"
                + "否则一次全量测试可能几十分钟）。";
    }

    @Override
    public String execute(Map<String, String> arguments) {

        rejectUnknownArguments(arguments, "test");

        TestResult result = testRunner.run(root, arguments.get("test"));

        StringBuilder observation = new StringBuilder();

        observation.append(result.isSuccess() ? "测试通过。" : "测试未通过。");
        observation.append("（退出码 ").append(result.getExitCode()).append("）\n");
        observation.append(summarize(result.getStdout()));

        if (result.getStderr() != null && !result.getStderr().isBlank()) {
            observation.append("\n").append(result.getStderr());
        }

        return observation.toString();
    }

    /**
     * 把完整日志提炼成摘要。
     *
     * <p>之前这里是把整份 `mvn test` 输出原样交给模型。实测下来，一个只有 8 行的项目，
     * 一次构建输出就有四十多行，大半是插件日志；真实项目的日志动辄几百上千行，
     * 光这一项就能吃掉半个上下文窗口。完整日志仍然落在 target 下，需要时可以自己去看。
     */
    private String summarize(String log) {

        List<String> kept = new ArrayList<>();

        for (String line : log.lines().toList()) {
            if (IMPORTANT.matcher(line).find()) {
                kept.add(line.strip());
                if (kept.size() >= MAX_LINES) {
                    kept.add("（摘要已截断，完整日志见 target/javafix-mvn-test.log）");
                    break;
                }
            }
        }

        if (kept.isEmpty()) {
            // 摘要为空时不能说成"没有输出"，那会让模型误以为构建根本没跑
            return "（日志里没有可提炼的关键行，完整日志见 target/javafix-mvn-test.log）";
        }

        return String.join("\n", kept);
    }
}
