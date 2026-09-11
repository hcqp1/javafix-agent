package com.javafix.agent.llm;

/**
 * 大模型调用抽象。
 *
 * <p>V0 骨架：仅约定最小契约，尚未接入任何真实模型，
 * 也刻意不引入 Spring AI / LangChain4j 等框架。
 */
public interface LlmClient {

    /**
     * 发送一次提示词，返回模型输出的文本。
     *
     * @param prompt 提示词
     * @return 模型返回的文本
     */
    String complete(String prompt);
}
