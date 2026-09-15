package com.javafix.agent.core;

import com.javafix.agent.llm.LlmClient;
import com.javafix.agent.tool.Tool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Agent 主循环，按「复现 -> 定位 -> 修复 -> 验证」四个阶段编排。
 *
 * <p>输入是用户报告的现象，Agent 自己复现、自己定位、自己修、自己验证。
 * 阶段的推进由两条路触发：模型输出 FINAL 表示"这个阶段我做完了"，
 * 或者代码识别到可判定的出口条件（比如复现阶段看到了失败测试）。
 *
 * <p>步数预算是分层的：每个阶段有自己的预算，总和是整条任务的兜底预算。
 * 预算还会写进每一步的提示词——<b>预算只有在被看见的时候才会影响行为</b>；
 * 之前只给一个总数、模型完全不知道还剩多少，结果是 16 步全耗在第一个阶段的探索上。
 */
public class AgentLoop {

    /** 整条任务的兜底预算：各阶段预算之和。 */
    private static final int DEFAULT_MAX_STEPS =
            Arrays.stream(Phase.values()).mapToInt(Phase::stepBudget).sum();

    /**
     * 单条观察结果回灌给模型时的长度上限。
     *
     * <p>一次 read_file 就可能带回几百行，而这些内容会被完整写进轨迹、
     * 并在之后的每一步重新发一遍。
     */
    private static final int MAX_OBSERVATION_CHARS = 4000;

    /** 复现阶段连续这么多步还没有产出，就开始催。 */
    private static final int STALL_THRESHOLD = 3;

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
            分隔符请用英文半角冒号，不要用中文输入法打出来的全角冒号。
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
     * 运行 Agent，直到验证通过或预算用尽。
     *
     * @param symptom 用户报告的现象，例如"下单偶尔少一条记录"，而不是已经定位好的位置
     * @return 验证通过时是最终答复；预算用尽时是一份状态报告
     */
    public String run(String symptom) {

        Phase phase = Phase.REPRODUCE;
        String lastSummary = null;
        int phaseSteps = 0;
        int unproductiveSteps = 0;

        for (int step = 1; step <= maxSteps; step++) {

            String response = llmClient.complete(
                    buildPrompt(symptom, phase, lastSummary, step, phaseSteps, buildNudge(phase, phaseSteps, unproductiveSteps))
            );

            Action action;
            try {
                action = Action.parse(response);
            } catch (RuntimeException e) {
                record(phase, step, "(无法解析的回复)\n" + response, "解析失败：" + e.getMessage());
                phaseSteps++;
                continue;
            }

            if (action.isFinal()) {
                record(phase, step, "阶段结论：" + action.finalAnswer(), "");
                lastSummary = action.finalAnswer();

                if (phase == Phase.VERIFY) {
                    return lastSummary;
                }

                phase = phase.next();
                phaseSteps = 0;
                unproductiveSteps = 0;
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
                phaseSteps++;
                unproductiveSteps++;
                continue;
            }

            String observation = execute(action);
            record(phase, step, describe(action), observation);
            phaseSteps++;

            // "产出"指的是写文件或跑测试；只是搜索、读文件不算——那样可以永远探索下去
            boolean produced = "write_file".equals(action.toolName())
                    || "run_tests".equals(action.toolName());
            unproductiveSteps = produced ? 0 : unproductiveSteps + 1;

            if (phase == Phase.REPRODUCE && FAILING_TEST.matcher(observation).find()) {
                lastSummary = "已复现一条失败测试";
                phase = Phase.LOCALIZE;
                phaseSteps = 0;
                unproductiveSteps = 0;
                continue;
            }

            if (phase == Phase.VERIFY && PASSING_TEST.matcher(observation).find()) {
                return lastSummary == null ? "验证通过" : lastSummary;
            }

            if (phaseSteps >= phase.stepBudget()) {
                record(
                        phase,
                        step,
                        "（本阶段预算用完）",
                        "「" + phase.label() + "」最多 " + phase.stepBudget() + " 步，已用完，转入下一阶段。"
                );
                lastSummary = "「" + phase.label() + "」阶段目标未达成，步数预算已用完";
                phase = phase.next();
                phaseSteps = 0;
                unproductiveSteps = 0;
            }
        }

        return report(symptom, phase, lastSummary);
    }

    /** 到预算上限时给一份状态报告，而不是抛异常——白跑十几步什么都不留是最差的结果。 */
    private String report(String symptom, Phase phase, String lastSummary) {

        StringBuilder report = new StringBuilder();
        report.append("没能完成这个任务。以下是停下来时的状态。\n\n");
        report.append("任务：").append(symptom).append('\n');
        report.append("停在阶段：").append(phase.label()).append('\n');
        report.append("总步数预算 ").append(maxSteps).append(" 步已用尽\n");

        if (lastSummary != null) {
            report.append("上一阶段的结论：").append(lastSummary).append('\n');
        }

        report.append("\n最后几步在做什么：\n");
        int from = Math.max(0, transcript.size() - 3);
        for (int i = from; i < transcript.size(); i++) {
            report.append("- ").append(brief(transcript.get(i))).append('\n');
        }

        report.append("\n建议：根据上面的轨迹判断是线索不足、还是工具或环境的问题，再决定是否重跑。\n");
        return report.toString();
    }

    private static String brief(String entry) {

        String[] lines = entry.split("\n");
        StringBuilder brief = new StringBuilder(lines.length > 0 ? lines[0] : entry);

        for (int i = 1; i < lines.length; i++) {
            if (lines[i].startsWith("调用了工具：") || lines[i].startsWith("阶段结论：")) {
                brief.append("  ").append(lines[i]);
                break;
            }
        }

        return brief.toString();
    }

    /** 该催的时候催一句：快用完预算了，或者连续好几步只探索没产出。 */
    private static String buildNudge(Phase phase, int phaseSteps, int unproductiveSteps) {

        if (phase != Phase.REPRODUCE) {
            return null;
        }

        if (unproductiveSteps >= STALL_THRESHOLD) {
            return "你已经连续 " + unproductiveSteps + " 步只做探索、没有产出。下一步必须二选一："
                    + "写出复现测试（write_file 到 src/test 下），或者运行测试（run_tests）。";
        }

        if (phaseSteps >= phase.stepBudget() - 1) {
            return "本阶段只剩最后一步：请在这一步内拿出能失败的复现测试，"
                    + "否则将带着「未复现」进入下一阶段。";
        }

        return null;
    }

    private String buildPrompt(
            String symptom,
            Phase phase,
            String lastSummary,
            int step,
            int phaseSteps,
            String nudge) {

        StringBuilder prompt = new StringBuilder();

        prompt.append("你是一个 Java 缺陷修复助手，按阶段工作。当前阶段：")
                .append(phase.label())
                .append("\n\n");
        prompt.append(phase.instruction()).append("\n");

        prompt.append("\n预算：整个任务共 ").append(maxSteps).append(" 步，已用 ").append(step - 1)
                .append(" 步；当前阶段最多 ").append(phase.stepBudget())
                .append(" 步，已用 ").append(phaseSteps).append(" 步。\n");

        if (nudge != null) {
            prompt.append("\n注意：").append(nudge).append('\n');
        }

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
     * "看起来能直接照抄的形状"当成模板模仿。协议只在 {@code ACTION_FORMAT} 那一处说明。
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
