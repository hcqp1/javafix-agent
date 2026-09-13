package com.javafix.agent.core;

import com.javafix.agent.llm.LlmClient;
import com.javafix.agent.tool.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Agent 主循环，按「复现 -> 定位 -> 修复 -> 验证」四个阶段编排。
 *
 * <p>输入从「给了位置的 bug 描述」改成「用户报告的现象」，Agent 自己复现、自己定位、
 * 自己修、自己验证。阶段的推进由两条路触发：模型输出 FINAL 表示"这个阶段我做完了"，
 * 或者代码识别到可判定的出口条件（比如复现阶段看到了失败测试）。
 */
public class AgentLoop {

    private static final int DEFAULT_MAX_STEPS = 16;

    /**
     * 单条观察结果回灌给模型时的长度上限。
     *
     * <p>一次 read_file 就可能带回几百行，而这些内容会被完整写进轨迹、
     * 并在之后的每一步重新发一遍。实测一次真实运行 16 步累计了 36 万输入 token，
     * 到后半程模型已经分不清哪些是当前任务、哪些是历史噪音了。
     */
    private static final int MAX_OBSERVATION_CHARS = 4000;

    /** 一条测试失败，用于判定"复现"阶段完成。 */
    private static final Pattern FAILING_TEST =
            Pattern.compile("Tests run:.*(Failures: [1-9]|Errors: [1-9])");

    /** 一条测试通过，用于判定"验证"阶段完成。 */
    private static final Pattern PASSING_TEST =
            Pattern.compile("Tests run:.*(Failures: 0, Errors: 0)");

    private static final String ACTION_FORMAT = """

            请严格按下面的格式回复，每次只输出一个动作。
            要调用工具时，回复由若干行组成：第一行以 THOUGHT 加冒号开头，写你这一步的思路；
            第二行以 TOOL 加冒号开头，写工具名；之后每个参数占一行，行首是参数名
            （全大写英文，例如 PATH、CONTENT）加冒号，后面跟参数值。
            参数值可以有多行（比如一整个文件的内容）：从参数名那一行的下一行开始写，
            一直写到下一个参数名为止。
            当前阶段完成时，用 FINAL 加冒号输出本阶段的结论。
            """;

    private final LlmClient llmClient;
    private final List<Tool> tools;
    private final int maxSteps;
    private final List<String> transcript = new ArrayList<>();

    public AgentLoop(LlmClient llmClient, List<Tool> tools) {
        this(llmClient, tools, DEFAULT_MAX_STEPS);
    }

    public AgentLoop(LlmClient llmClient, List<Tool> tools, int maxSteps) {
        this.llmClient = llmClient;
        this.tools = List.copyOf(tools);
        this.maxSteps = maxSteps;
    }

    public LlmClient llmClient() {
        return llmClient;
    }

    public List<Tool> tools() {
        return tools;
    }

    /** 运行轨迹：每一步的阶段、动作与观察结果。 */
    public List<String> transcript() {
        return List.copyOf(transcript);
    }

    /**
     * 运行 Agent，直到验证通过或达到最大步数。
     *
     * @param symptom 用户报告的现象，例如"下单偶尔少一条记录"，而不是已经定位好的位置
     * @return 给用户的最终答复
     * @throws IllegalStateException 达到最大步数仍未完成
     */
    public String run(String symptom) {

        Phase phase = Phase.REPRODUCE;
        String lastSummary = null;

        for (int step = 1; step <= maxSteps; step++) {

            String response = llmClient.complete(buildPrompt(symptom, phase, lastSummary));

            Action action;
            try {
                action = Action.parse(response);
            } catch (RuntimeException e) {
                record(phase, step, "(无法解析的回复)\n" + response, "解析失败：" + e.getMessage());
                continue;
            }

            if (action.isFinal()) {
                record(phase, step, "阶段结论：" + action.finalAnswer(), "");
                lastSummary = action.finalAnswer();
                if (phase == Phase.VERIFY) {
                    return lastSummary;
                }
                phase = phase.next();
                continue;
            }

            // 复现阶段禁止改生产代码——这条靠代码强制，而不是靠提示词请求模型配合
            if (phase == Phase.REPRODUCE && isMainSourceWrite(action)) {
                record(
                        phase,
                        step,
                        describe(action),
                        "复现阶段禁止修改 src/main 下的代码；先把现象复现成 src/test 下的一条失败测试。"
                );
                continue;
            }

            String observation = execute(action);
            record(phase, step, describe(action), observation);

            if (phase == Phase.REPRODUCE && FAILING_TEST.matcher(observation).find()) {
                lastSummary = "已复现一条失败测试";
                phase = Phase.LOCALIZE;
            } else if (phase == Phase.VERIFY && PASSING_TEST.matcher(observation).find()) {
                return lastSummary == null ? "验证通过" : lastSummary;
            }
        }

        throw new IllegalStateException("达到最大步数 " + maxSteps + " 仍未完成任务：" + symptom);
    }

    private String buildPrompt(String symptom, Phase phase, String lastSummary) {

        StringBuilder prompt = new StringBuilder();

        prompt.append("你是一个 Java 缺陷修复助手，按阶段工作。当前阶段：")
                .append(phase.label())
                .append("\n\n");
        prompt.append(phase.instruction()).append("\n");

        prompt.append("\n用户报告的现象：\n").append(symptom).append("\n\n");

        prompt.append("可用工具：\n");
        for (Tool tool : tools) {
            prompt.append("- ").append(tool.name()).append("：").append(tool.description()).append('\n');
        }

        prompt.append(ACTION_FORMAT);

        if (lastSummary != null) {
            prompt.append("\n上一阶段的结论：").append(lastSummary).append('\n');
        }

        if (!transcript.isEmpty()) {
            prompt.append("\n到目前为止的进展：\n");
            transcript.forEach(entry -> prompt.append(entry).append('\n'));
        }

        return prompt.toString();
    }

    /**
     * 判断一个 write_file 动作是不是想改生产代码。
     * 复现阶段只允许在 src/test 下写复现测试。
     */
    private static boolean isMainSourceWrite(Action action) {

        if (!"write_file".equals(action.toolName())) {
            return false;
        }

        String path = action.arguments().get("path");
        if (path == null) {
            return false;
        }

        String normalized = path.replace('\\', '/');
        return normalized.equals("src/main") || normalized.startsWith("src/main/");
    }

    /**
     * 分发动作到工具。模型编出不存在的工具名，或者工具自身抛异常，
     * 都只作为观察结果返回——对 Agent 来说"这一步做错了"和"这一步没做成"是同一种信息。
     */
    private String execute(Action action) {

        Tool tool = tools.stream()
                .filter(candidate -> candidate.name().equals(action.toolName()))
                .findFirst()
                .orElse(null);

        if (tool == null) {
            return "没有名为 " + action.toolName() + " 的工具。可用工具：" + toolNames();
        }

        try {
            return tool.execute(action.arguments());
        } catch (RuntimeException e) {
            return "工具 " + action.toolName() + " 执行失败：" + e;
        }
    }

    /**
     * 把一步动作渲染给模型看。
     *
     * <p>刻意不按协议的样子写（不出现 TOOL、参数名这类形状）：模型会把提示词和轨迹里
     * "看起来能直接照抄的形状"当成模板模仿，之前两次翻车都是这么来的。
     * 协议只在 {@code ACTION_FORMAT} 那一处说明，轨迹这里用自然语言描述。
     */
    private String describe(Action action) {
        String parameterNames = action.arguments().isEmpty()
                ? "无"
                : String.join("、", action.arguments().keySet());
        return "思路：" + action.thought()
                + "\n调用了工具：" + action.toolName()
                + "\n参数：" + parameterNames;
    }

    private String toolNames() {
        return tools.stream().map(Tool::name).toList().toString();
    }

    private void record(Phase phase, int step, String action, String observation) {
        transcript.add("[" + phase.label() + "] 步骤 " + step
                + "\n" + action
                + "\n观察：" + trim(observation));
    }

    /** 观察结果超长就截断，并明确告诉模型"这里被截断了，完整内容要重新读"。 */
    private static String trim(String observation) {

        if (observation == null || observation.length() <= MAX_OBSERVATION_CHARS) {
            return observation;
        }

        return observation.substring(0, MAX_OBSERVATION_CHARS)
                + "\n（内容过长已截断，原文共 " + observation.length()
                + " 字符；需要完整内容请重新读取相关部分）";
    }
}
