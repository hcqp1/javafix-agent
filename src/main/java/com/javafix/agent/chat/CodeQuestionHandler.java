package com.javafix.agent.chat;

import com.javafix.agent.core.Action;
import com.javafix.agent.llm.LlmClient;
import com.javafix.agent.tool.Tool;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Small read-only tool loop for repository questions; it cannot edit files or run commands. */
public final class CodeQuestionHandler {

    private static final int MAX_STEPS = 6;
    private static final Set<String> ALLOWED_TOOLS = Set.of("read_file", "search_code");
    private static final String ACTION_FORMAT = """
            每次只输出一个动作，格式如下：
            THOUGHT: 简短说明
            TOOL: read_file 或 search_code
            参数名: 参数值
            需要回答时输出：
            FINAL: 答案
            """;

    private final LlmClient client;
    private final List<Tool> readOnlyTools;
    private final String projectPath;

    public CodeQuestionHandler(LlmClient client, List<Tool> tools, String projectPath) {
        this.client = client;
        this.readOnlyTools = tools.stream().filter(tool -> ALLOWED_TOOLS.contains(tool.name())).toList();
        this.projectPath = projectPath;
    }

    public String answer(String question, List<String> taskHistory) {
        StringBuilder context = new StringBuilder();
        if (taskHistory != null && !taskHistory.isEmpty()) {
            context.append("当前任务此前对话：\n");
            taskHistory.forEach(item -> context.append("- ").append(item).append('\n'));
        }
        String observation = "尚未读取项目文件。可调用只读工具检索证据。";

        for (int step = 0; step < MAX_STEPS; step++) {
            String prompt = buildPrompt(question, context.toString(), observation);
            Action action;
            try {
                action = Action.parse(client.complete(prompt));
            } catch (RuntimeException e) {
                observation = "动作格式无法解析：" + e.getMessage() + "。请按要求输出 TOOL 或 FINAL。";
                continue;
            }
            if (action.isFinal()) return action.finalAnswer();
            Tool tool = readOnlyTools.stream().filter(candidate -> candidate.name().equals(action.toolName()))
                    .findFirst().orElse(null);
            if (tool == null) {
                observation = "拒绝调用工具：" + action.toolName() + "。只允许 read_file 和 search_code。";
                continue;
            }
            try {
                observation = tool.execute(normalizeArguments(action.arguments()));
                if (observation.length() > 7000) observation = observation.substring(0, 7000) + "\n（结果已截断）";
            } catch (RuntimeException e) {
                observation = "只读工具执行失败：" + e.getMessage();
            }
        }
        return "只读检索达到步数上限，尚未形成可靠结论。最后一次观察：" + observation;
    }

    private String buildPrompt(String question, String taskHistory, String observation) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是 JavaFixAgent 的只读代码问答模式。当前项目路径：")
                .append(projectPath).append("\n你只能检查代码并解释，绝不能修改文件、运行命令或测试。\n");
        if (!taskHistory.isBlank()) prompt.append('\n').append(taskHistory);
        prompt.append("\n用户问题：\n").append(question).append("\n\n可用只读工具：\n");
        readOnlyTools.forEach(tool -> prompt.append("- ").append(tool.name()).append("：")
                .append(tool.description()).append('\n'));
        prompt.append("\n最近一次工具观察：\n").append(observation).append("\n\n").append(ACTION_FORMAT);
        return prompt.toString();
    }

    private static Map<String, String> normalizeArguments(Map<String, String> arguments) {
        java.util.HashMap<String, String> normalized = new java.util.HashMap<>();
        arguments.forEach((key, value) -> normalized.put(key.toLowerCase(java.util.Locale.ROOT), value));
        return normalized;
    }
}
