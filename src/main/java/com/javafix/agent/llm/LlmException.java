package com.javafix.agent.llm;

/**
 * 调用大模型失败。
 *
 * <p>它表示「这一轮拿不到模型回复」，而不是「模型回复得不对」——
 * 后者（格式问题）由 AgentLoop 转成观察结果让模型自己纠正。
 */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
