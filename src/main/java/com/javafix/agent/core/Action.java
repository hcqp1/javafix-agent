package com.javafix.agent.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模型回复解析出来的一个动作：要么调用某个工具，要么宣布任务完成。
 *
 * <pre>
 * THOUGHT: 先看看 Calculator 的实现
 * TOOL: read_file
 * PATH: src/main/java/com/example/Calculator.java
 * </pre>
 *
 * <p>参数名强制大写（{@code PATH} / {@code CONTENT} / {@code QUERY}），
 * 这样一个参数值是多行文件内容时，不会因为里面出现 {@code key: value} 之类的行
 * （例如 YAML 配置）而被误判成新的参数。参数值从参数名那一行之后延续到下一个参数名。
 *
 * <p>这是 V0 的临时协议。接真模型时大概率要换成模型原生的 tool calling 或 JSON，
 * 届时应把解析逻辑整体替换掉，而不是在上面继续加规则。
 */
public class Action {

    /** 形如 {@code PATH: xxx} 的参数行；参数名必须全大写。 */
    private static final Pattern ARGUMENT_LINE =
            Pattern.compile("^\\s*([A-Z][A-Z_]*):[ \\t]?(.*)$");

    private final String thought;
    private final String toolName;
    private final Map<String, String> arguments;
    private final String finalAnswer;

    private Action(String thought, String toolName, Map<String, String> arguments, String finalAnswer) {
        this.thought = thought;
        this.toolName = toolName;
        this.arguments = Map.copyOf(arguments);
        this.finalAnswer = finalAnswer;
    }

    public String thought() {
        return thought;
    }

    public String toolName() {
        return toolName;
    }

    public Map<String, String> arguments() {
        return arguments;
    }

    public boolean isFinal() {
        return finalAnswer != null;
    }

    public String finalAnswer() {
        return finalAnswer;
    }

    /**
     * 解析模型回复。
     *
     * @throws IllegalArgumentException 回复里既没有 TOOL 行也没有 FINAL 行；
     *                                  调用方应把它当成一次观察结果反馈给模型，而不是直接失败
     */
    public static Action parse(String response) {

        List<String> lines = response.lines().toList();
        String thought = null;

        for (int i = 0; i < lines.size(); i++) {

            String line = lines.get(i).strip();

            if (line.startsWith("THOUGHT:")) {
                thought = line.substring("THOUGHT:".length()).strip();
                continue;
            }

            if (line.startsWith("FINAL:")) {
                String inline = line.substring("FINAL:".length()).strip();
                String rest = String.join("\n", lines.subList(i + 1, lines.size())).strip();
                String answer = rest.isEmpty() ? inline : inline.isEmpty() ? rest : inline + "\n" + rest;
                return new Action(thought, null, Map.of(), answer);
            }

            if (line.startsWith("TOOL:")) {
                String name = line.substring("TOOL:".length()).strip();
                if (name.isEmpty()) {
                    throw new IllegalArgumentException("TOOL 行没有写工具名");
                }
                return new Action(thought, name, parseArguments(lines.subList(i + 1, lines.size())), null);
            }
        }

        throw new IllegalArgumentException("回复里既没有 TOOL: 也没有 FINAL: 行");
    }

    private static Map<String, String> parseArguments(List<String> lines) {

        Map<String, String> arguments = new LinkedHashMap<>();
        String currentKey = null;
        List<String> currentValue = new ArrayList<>();

        for (String line : lines) {

            Matcher matcher = ARGUMENT_LINE.matcher(line);

            if (matcher.matches()) {
                if (currentKey != null) {
                    arguments.put(currentKey, String.join("\n", currentValue).strip());
                }
                currentKey = matcher.group(1).toLowerCase(Locale.ROOT);
                currentValue = new ArrayList<>();
                currentValue.add(matcher.group(2));
            } else if (currentKey != null) {
                currentValue.add(line);
            }
        }

        if (currentKey != null) {
            arguments.put(currentKey, String.join("\n", currentValue).strip());
        }

        return arguments;
    }
}
