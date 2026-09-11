package com.javafix.agent.core;

import com.javafix.agent.llm.LlmClient;
import com.javafix.agent.tool.Tool;

import java.util.List;

/**
 * Agent 主循环：编排「思考 -> 调用工具 -> 观察结果」的迭代过程。
 *
 * <p>V0 骨架：仅保留协作关系（依赖 {@link LlmClient} 与一组 {@link Tool}），尚未实现循环逻辑。
 */
public class AgentLoop {

    private final LlmClient llmClient;
    private final List<Tool> tools;

    public AgentLoop(LlmClient llmClient, List<Tool> tools) {
        this.llmClient = llmClient;
        this.tools = List.copyOf(tools);
    }

    public LlmClient llmClient() {
        return llmClient;
    }

    public List<Tool> tools() {
        return tools;
    }

    /**
     * 运行 Agent，直到任务完成或达到最大迭代次数。
     *
     * @param task 任务描述，例如一段 Bug 报告
     */
    public void run(String task) {
        // TODO: V0 阶段实现 ReAct 风格的循环：
        //   1. 组装 prompt（任务描述 + 可用工具列表）
        //   2. 调用 LlmClient 获取下一步动作
        //   3. 解析动作并分发到对应的 Tool
        //   4. 把工具执行结果作为 observation 回灌，进入下一轮
        //   5. 任务完成或达到最大迭代次数时结束
        throw new UnsupportedOperationException("AgentLoop.run is not implemented yet");
    }
}
