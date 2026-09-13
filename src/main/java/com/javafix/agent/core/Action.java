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

        Map<String, String> arguments = parseLines(lines);

        // 容错：模型有时把整个参数表塞进一个叫 args 的值里，还可能嵌套一层。
        // 这些形状都来自真实运行，不是假想出来的。
        for (int depth = 0; depth < 2; depth++) {
            String nested = arguments.remove("args");
            if (nested == null) {
                break;
            }
            Map<String, String> inner = parseLines(nested.lines().toList());
            if (inner.isEmpty()) {
                inner = parseInline(nested);
            }
            inner.forEach(arguments::putIfAbsent);
        }

        return arguments;
    }

    private static Map<String, String> parseLines(List<String> lines) {

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
                continue;
            }

            // 正常规则认不出来时才走容错：模型可能把参数写成一行键值对，
            // 例如 {query=Foo, regex=false}
            if (currentKey == null) {
                Map<String, String> inline = parseInline(line);
                if (!inline.isEmpty()) {
                    arguments.putAll(inline);
                    continue;
                }
            }

            if (currentKey != null) {
                currentValue.add(line);
            }
        }

        if (currentKey != null) {
            arguments.put(currentKey, String.join("\n", currentValue).strip());
        }

        return arguments;
    }

    /**
     * 解析一行「外层带花括号的键值对」，例如 {@code {query=Foo, regex=false}}
     * 或 {@code {PATH: a/b.java}}。
     *
     * <p>这是给模型的容错兜底，不是通用语法解析：只认「外层花括号 + 逗号分隔」这一种形状，
     * 认不出来就返回空，交回正常规则处理。容错的目的是别让一步白白浪费掉，
     * 不是"什么都能收"——真出现更多变体，该做的是换协议，而不是继续加规则。
     */
    private static Map<String, String> parseInline(String line) {

        String body = line == null ? "" : line.strip();

        // 没有花括号的普通行交给正常规则，避免误伤多行内容
        if (!body.startsWith("{") && !body.endsWith("}")) {
            return Map.of();
        }

        if (body.startsWith("{")) {
            body = body.substring(1);
        }
        if (body.endsWith("}")) {
            body = body.substring(0, body.length() - 1);
        }

        Map<String, String> parsed = new LinkedHashMap<>();

        for (String pair : body.split(",")) {

            int separator = pair.indexOf('=');
            if (separator < 0) {
                separator = pair.indexOf(':');
            }
            if (separator <= 0) {
                continue;
            }

            String key = pair.substring(0, separator).strip().toLowerCase(Locale.ROOT);
            String value = pair.substring(separator + 1).strip();

            if (value.endsWith("}")) {
                value = value.substring(0, value.length() - 1).strip();
            }

            if (!key.isEmpty()) {
                parsed.put(key, value);
            }
        }

        return parsed;
    }
}
