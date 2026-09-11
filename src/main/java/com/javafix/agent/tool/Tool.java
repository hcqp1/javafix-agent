package com.javafix.agent.tool;

import java.util.Map;

/**
 * Agent 可调用工具的统一抽象。
 *
 * <p>V0 阶段只约定最小契约：名称、描述、执行入口。
 * ReadFileTool / SearchCodeTool / WriteFileTool / ShellTool 均实现本接口。
 */
public interface Tool {

    /**
     * 工具名称，供 LLM 在 tool call 中引用，在同一个 Agent 内需保持唯一。
     *
     * @return 工具名称，例如 {@code read_file}
     */
    String name();

    /**
     * 工具用途描述，供 LLM 判断何时调用该工具。
     *
     * @return 工具描述
     */
    String description();

    /**
     * 执行工具。
     *
     * <p>参数刻意使用简单的字符串键值对，避免在 V0 阶段引入大量 DTO。
     * 例如：{@code {"path": "src/main/java/Foo.java"}}、{@code {"command": "mvn test"}}。
     *
     * @param arguments 工具参数，可能为空 Map，但不应为 {@code null}
     * @return 工具执行结果的文本表示
     */
    String execute(Map<String, String> arguments);
}
