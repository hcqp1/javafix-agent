package com.javafix.agent;

import com.javafix.agent.llm.LlmClient;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 预先写好回复的假模型：不联网、不花钱、结果完全确定。
 *
 * <p>它的用途是先把「循环 + 工具 + 测试反馈」这条管道验证通。
 * 管道还没通就去接真模型，一旦出问题会分不清是 prompt 的问题、工具的问题还是循环结构的问题。
 *
 * <p>脚本用完还被调用，说明循环多跑了一步——这时直接抛异常让测试立刻失败，
 * 而不是悄悄返回空字符串把问题掩盖过去。
 */
class ScriptedLlmClient implements LlmClient {

    private final Deque<String> responses;
    private final List<String> prompts = new ArrayList<>();

    ScriptedLlmClient(String... responses) {
        this.responses = new ArrayDeque<>(List.of(responses));
    }

    @Override
    public String complete(String prompt) {

        prompts.add(prompt);

        if (responses.isEmpty()) {
            throw new IllegalStateException("脚本里的回复已经用完，但模型又被调用了一次");
        }

        return responses.poll();
    }

    /** 收到的 prompt，用来断言循环到底给了模型什么信息。 */
    List<String> prompts() {
        return List.copyOf(prompts);
    }
}
