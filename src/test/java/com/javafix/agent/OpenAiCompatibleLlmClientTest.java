package com.javafix.agent;

import com.javafix.agent.llm.LlmException;
import com.javafix.agent.llm.OpenAiCompatibleLlmClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用 JDK 自带的 HttpServer 起一个假的模型服务端，验证真实的 HTTP 路径：
 * 请求怎么组装、响应怎么解析、出错怎么重试、什么情况不该重试。
 *
 * <p>不联网、不花钱、结果确定，但它跑的是真正的 {@code HttpClient} 代码，
 * 而不是把网络那一层替换掉的桩。
 */
class OpenAiCompatibleLlmClientTest {

    private record StubResponse(int status, String body) {
    }

    private HttpServer server;
    private final Deque<StubResponse> responses = new ArrayDeque<>();
    private final List<String> requestBodies = new ArrayList<>();
    private final List<String> authorizationHeaders = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/chat/completions", exchange -> {

            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));

            StubResponse stub = responses.isEmpty()
                    ? new StubResponse(500, "{\"error\":\"测试脚本里没有更多响应了\"}")
                    : responses.poll();

            byte[] body = stub.body().getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(stub.status(), body.length);

            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });

        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void shouldSendPromptAndReturnContent() {

        responses.add(new StubResponse(200, completion("TOOL: read_file")));

        String answer = client().complete("看看这个文件");

        assertEquals("TOOL: read_file", answer);

        String sentBody = requestBodies.get(0);
        assertTrue(sentBody.contains("看看这个文件"), "prompt 要原样发出去，实际：" + sentBody);
        assertTrue(sentBody.contains("\"model\":\"test-model\""), sentBody);
        assertEquals("Bearer test-key", authorizationHeaders.get(0));
    }

    @Test
    void shouldRetryOnServerError() {

        responses.add(new StubResponse(500, "{\"error\":\"boom\"}"));
        responses.add(new StubResponse(200, completion("FINAL: 好了")));

        assertEquals("FINAL: 好了", client().complete("任务"));
        assertEquals(2, requestBodies.size(), "5xx 应该重试一次");
    }

    @Test
    void shouldAccumulateTokenUsage() {

        responses.add(new StubResponse(200, completion("FINAL: 好了")));

        OpenAiCompatibleLlmClient client = client();
        client.complete("任务");

        assertEquals(1, client.usage().calls());
        assertEquals(123, client.usage().promptTokens());
        assertEquals(45, client.usage().completionTokens());
        assertEquals(168, client.usage().totalTokens());
    }

    @Test
    void shouldNotRetryOnUnauthorized() {

        responses.add(new StubResponse(401, "{\"error\":\"invalid api key\"}"));

        LlmException failure = assertThrows(LlmException.class, () -> client().complete("任务"));

        assertTrue(failure.getMessage().contains("401"), failure.getMessage());
        assertTrue(failure.getMessage().contains("invalid api key"), "要带上服务端给的原因");
        assertEquals(1, requestBodies.size(), "key 错了重试多少次都一样，不该重试");
    }

    @Test
    void shouldFailWhenResponseIsNotJson() {

        // 比如网关返回了一页 HTML，这种情况要说清楚是响应不可解析，而不是笼统地报失败
        responses.add(new StubResponse(200, "<html>502 Bad Gateway</html>"));

        LlmException failure = assertThrows(LlmException.class, () -> client().complete("任务"));

        assertTrue(failure.getMessage().contains("不是合法 JSON"), failure.getMessage());
    }

    @Test
    void shouldFailWhenResponseHasNoContent() {

        responses.add(new StubResponse(200, "{\"choices\":[]}"));

        LlmException failure = assertThrows(LlmException.class, () -> client().complete("任务"));

        assertTrue(failure.getMessage().contains("没有正文"), failure.getMessage());
    }

    private OpenAiCompatibleLlmClient client() {
        return new OpenAiCompatibleLlmClient(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-key",
                "test-model",
                Duration.ofSeconds(10),
                Duration.ZERO
        );
    }

    private static String completion(String content) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\""
                + content + "\"}}],\"usage\":{\"prompt_tokens\":123,\"completion_tokens\":45}}";
    }
}
