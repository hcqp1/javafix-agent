package com.javafix.agent.core;

import com.javafix.agent.llm.LlmClient;
import com.javafix.agent.tool.Tool;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 主循环：编排「思考 -> 调用工具 -> 观察结果」的迭代过程。
 *
 * <p>每一步做四件事：把任务、可用工具和历史组装成 prompt；调用模型拿到动作；
 * 把动作分发到对应的工具；把工具输出作为观察结果回灌，进入下一轮。
 *
 * <p>一条重要的设计原则：<b>模型和工具出问题不能中断循环</b>。
 * 模型输出格式不对、工具抛异常，都要变成一条观察结果反馈给它，让它自己纠正；
 * 只有到达最大步数才真正停下。
 */
public class AgentLoop {

    /** 默认的最大迭代步数，防止模型在同一个坑里反复打转。 */
    private static final int DEFAULT_MAX_STEPS = 12;

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

    /** 运行轨迹：每一步的动作与观察结果，用于复盘、调试和后续的评测记录。 */
    public List<String> transcript() {
        return List.copyOf(transcript);
    }

    /**
     * 运行 Agent，直到模型宣布完成或达到最大步数。
     *
     * @param task 任务描述，例如一段 Bug 报告
     * @return 模型给出的最终答复
     * @throws IllegalStateException 达到最大步数仍未完成
     */
    public String run(String task) {

        for (int step = 1; step <= maxSteps; step++) {

            String response = llmClient.complete(buildPrompt(task));

            Action action;
            try {
                action = Action.parse(response);
            } catch (RuntimeException e) {
                // 模型没按格式说话，指出来让它重说，而不是就此失败
                record(step, "(无法解析的回复)\n" + response, "解析失败：" + e.getMessage());
                continue;
            }

            if (action.isFinal()) {
                record(step, "FINAL: " + action.finalAnswer(), "");
                return action.finalAnswer();
            }

            String observation = execute(action);
            record(step, describe(action), observation);
        }

        throw new IllegalStateException("达到最大步数 " + maxSteps + " 仍未完成任务：" + task);
    }

    /**
     * 分发动作到工具。
     *
     * <p>模型编出不存在的工具名，或者工具自身抛异常，都只作为观察结果返回——
     * 对 Agent 来说「这一步做错了」和「这一步没做成」是同一种信息。
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

    private String buildPrompt(String task) {

        StringBuilder prompt = new StringBuilder();

        prompt.append("你是一个 Java 缺陷修复助手，通过调用工具来定位并修复代码中的 Bug。\n\n");
        prompt.append("任务：\n").append(task).append("\n\n");

        prompt.append("可用工具：\n");
        for (Tool tool : tools) {
            prompt.append("- ").append(tool.name()).append("：").append(tool.description()).append('\n');
        }

        // 这里刻意不给出可以直接照抄的示例行：早先的版本用「参数名: 参数值」当模板，
        // 结果模型把这一行也当成内容写进了参数里，白白浪费一次调用。
        prompt.append("\n请严格按下面的格式回复，每次只输出一个动作。\n");
        prompt.append("要调用工具时，回复由若干行组成：第一行以 THOUGHT 加冒号开头，写你这一步的思路；"
                + "第二行以 TOOL 加冒号开头，写工具名；之后每个参数占一行，"
                + "行首是参数名（全大写英文，例如 PATH、CONTENT）加冒号，后面跟参数值。\n");
        prompt.append("参数值可以有多行（比如一整个文件的内容）：从参数名那一行的下一行开始写，"
                + "一直写到下一个参数名为止。\n");
        prompt.append("任务完成时不要输出 TOOL 行，改为两行：第一行以 THOUGHT 加冒号写结论，"
                + "第二行以 FINAL 加冒号写给用户看的最终答复。\n");

        if (!transcript.isEmpty()) {
            prompt.append("\n到目前为止的进展：\n");
            transcript.forEach(entry -> prompt.append(entry).append('\n'));
        }

        return prompt.toString();
    }

    private String describe(Action action) {
        return "THOUGHT: " + action.thought()
                + "\nTOOL: " + action.toolName()
                + "\nARGS: " + action.arguments();
    }

    private String toolNames() {
        return tools.stream().map(Tool::name).toList().toString();
    }

    private void record(int step, String action, String observation) {
        transcript.add("步骤 " + step + "\n" + action + "\n观察：" + observation);
    }
}
