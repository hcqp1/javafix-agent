package com.javafix.agent.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 走 OpenAI 兼容协议的模型客户端。
 *
 * <p>选这个协议而不是某个厂商的私有 SDK，是因为国内几家（DeepSeek、通义、Kimi、智谱）
 * 以及本地 Ollama 都提供兼容接口，换模型只需要改 baseUrl 和 model 两个字符串。
 *
 * <p>HTTP 用 JDK 自带的 {@link HttpClient}，JSON 交给 Jackson——请求体要正确转义、
 * 响应是嵌套结构，这两件事都不该手写。
 *
 * <p>错误分成两类，处理方式不同：
 *
 * <ul>
 *   <li><b>网络故障、5xx、429</b>：多半是暂时的，重试有意义，退避后重试若干次。</li>
 *   <li><b>其他 4xx</b>：请求本身有问题（模型名写错、key 过期、余额不足），
 *       重试只是浪费时间，直接失败并把服务端返回的原因带出来。</li>
 * </ul>
 */
public class OpenAiCompatibleLlmClient implements LlmClient {

    /** 单次请求的超时。模型要生成一整段代码，比普通接口慢得多。 */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration DEFAULT_RETRY_BACKOFF = Duration.ofMillis(400);
    private static final int MAX_ATTEMPTS = 3;
    private static final int BODY_PREVIEW_LIMIT = 300;

    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final Duration retryBackoff;
    private LlmUsage usage = LlmUsage.NONE;

    public OpenAiCompatibleLlmClient(String baseUrl, String apiKey, String model) {
        this(baseUrl, apiKey, model, DEFAULT_TIMEOUT, DEFAULT_RETRY_BACKOFF);
    }

    /** 超时与重试间隔可注入，测试里把重试间隔设成 0 就不用真的等。 */
    public OpenAiCompatibleLlmClient(
            String baseUrl,
            String apiKey,
            String model,
            Duration timeout,
            Duration retryBackoff) {

        this.endpoint = URI.create(trimTrailingSlashes(baseUrl) + "/chat/completions");
        this.apiKey = apiKey;
        this.model = model;
        this.timeout = timeout;
        this.retryBackoff = retryBackoff;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    @Override
    public String complete(String prompt) {

        String body = requestBody(prompt);
        LlmException lastFailure = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {

            if (attempt > 1) {
                sleep(retryBackoff.multipliedBy(attempt - 1));
            }

            try {
                HttpResponse<String> response = httpClient.send(
                        request(body),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );

                if (response.statusCode() == 200) {
                    recordUsage(response.body());
                    return extractContent(response.body());
                }

                LlmException failure = new LlmException(
                        "模型接口返回 " + response.statusCode() + "：" + preview(response.body())
                );

                if (response.statusCode() < 500 && response.statusCode() != 429) {
                    throw failure;
                }

                lastFailure = failure;

            } catch (IOException e) {
                lastFailure = new LlmException("调用模型失败：" + e, e);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException("调用模型被中断", e);
            }
        }

        throw new LlmException(
                "重试 " + MAX_ATTEMPTS + " 次仍然失败：" + lastFailure.getMessage(),
                lastFailure
        );
    }

    private HttpRequest request(String body) {
        return HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    /**
     * 组装请求体。
     *
     * <p>temperature 设成 0：修复任务要的是稳定和可复现，不是发挥。
     * 这里不额外加 system 消息——prompt 的格式约定由 AgentLoop 一处负责，
     * 分两处写迟早会互相打架。
     */
    private String requestBody(String prompt) {

        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        root.put("temperature", 0);

        ArrayNode messages = root.putArray("messages");
        messages.addObject()
                .put("role", "user")
                .put("content", prompt);

        try {
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法序列化请求体", e);
        }
    }

    private String extractContent(String responseBody) {

        return contentOf(responseBody);
    }

    /**
     * 记录这次调用消耗的 token。
     *
     * <p>统计是附加信息，服务端没给 usage 字段、或者响应不合法时都不该影响修复任务本身，
     * 所以这里吞掉异常，只跳过统计。
     */
    private void recordUsage(String responseBody) {
        try {
            JsonNode usageNode = mapper.readTree(responseBody).path("usage");
            usage = usage.plus(
                    usageNode.path("prompt_tokens").asLong(0),
                    usageNode.path("completion_tokens").asLong(0)
            );
        } catch (JsonProcessingException e) {
            // 忽略：拿不到用量不影响这一轮是否成功
        }
    }

    /** 到目前为止累计的 token 消耗。 */
    public LlmUsage usage() {
        return usage;
    }

    private String contentOf(String responseBody) {

        JsonNode root;
        try {
            root = mapper.readTree(responseBody);
        } catch (JsonProcessingException e) {
            throw new LlmException("模型的响应不是合法 JSON：" + preview(responseBody), e);
        }

        JsonNode content = root.path("choices").path(0).path("message").path("content");

        if (content.isMissingNode() || content.isNull() || content.asText().isBlank()) {
            throw new LlmException("模型的响应里没有正文：" + preview(responseBody));
        }

        return content.asText();
    }

    private static String trimTrailingSlashes(String baseUrl) {
        return baseUrl.replaceAll("/+$", "");
    }

    /** 错误原文可能很长（带着一整页 HTML），截断后再放进异常信息。 */
    private static String preview(String body) {
        if (body == null) {
            return "(空)";
        }
        String flattened = body.strip();
        return flattened.length() <= BODY_PREVIEW_LIMIT
                ? flattened
                : flattened.substring(0, BODY_PREVIEW_LIMIT) + "...";
    }

    private static void sleep(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("重试等待被中断", e);
        }
    }
}
