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

    /**
     * 到目前为止该客户端累计消耗的 token。
     *
     * <p>统计能力是可选项：测试用的假模型没有真实消耗，所以给一个默认实现，
     * 而不是把它变成每个实现都必须回答的问题。
     */
    default LlmUsage usage() {
        return LlmUsage.NONE;
    }
}
