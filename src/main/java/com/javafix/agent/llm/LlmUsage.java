package com.javafix.agent.llm;

/**
 * 模型调用的累计消耗。
 *
 * <p>没有度量就没法评测：谈「修复成功率」之前，先得知道每一步花了多少钱、多少时间。
 */
public record LlmUsage(long promptTokens, long completionTokens, int calls) {

    /** 不统计的实现用它表示「未知」，而不是「消耗为零」。 */
    public static final LlmUsage NONE = new LlmUsage(0, 0, 0);

    public long totalTokens() {
        return promptTokens + completionTokens;
    }

    public LlmUsage plus(long prompt, long completion) {
        return new LlmUsage(promptTokens + prompt, completionTokens + completion, calls + 1);
    }

    @Override
    public String toString() {
        return "调用 " + calls + " 次 / 输入 " + promptTokens
                + " / 输出 " + completionTokens + " / 合计 " + totalTokens() + " tokens";
    }
}
