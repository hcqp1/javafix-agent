package com.javafix.agent;

import com.javafix.agent.core.AgentLoop;
import com.javafix.agent.llm.LlmClient;
import com.javafix.agent.llm.OpenAiCompatibleLlmClient;
import com.javafix.agent.tool.MavenTestRunner;
import com.javafix.agent.tool.ReadFileTool;
import com.javafix.agent.tool.RunTestsTool;
import com.javafix.agent.tool.SearchCodeTool;
import com.javafix.agent.tool.ShellTool;
import com.javafix.agent.tool.Tool;
import com.javafix.agent.tool.WriteFileTool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

/**
 * 命令行入口。
 *
 * <pre>
 * mvn -q exec:java -Dexec.args="&lt;项目目录&gt; &lt;Bug 描述&gt;"
 * </pre>
 *
 * <p>模型配置从环境变量读取，不写进代码也不进仓库：
 * {@code JAVAFIX_LLM_BASE_URL}、{@code JAVAFIX_LLM_API_KEY}、{@code JAVAFIX_LLM_MODEL}。
 */
public class Main {

    private static final String ENV_BASE_URL = "JAVAFIX_LLM_BASE_URL";
    private static final String ENV_API_KEY = "JAVAFIX_LLM_API_KEY";
    private static final String ENV_MODEL = "JAVAFIX_LLM_MODEL";

    public static void main(String[] args) {

        if (args.length < 2) {
            usage();
            return;
        }

        Path project = Path.of(args[0]).toAbsolutePath().normalize();

        if (!Files.isDirectory(project)) {
            System.err.println("项目目录不存在：" + project);
            return;
        }

        String baseUrl = System.getenv(ENV_BASE_URL);
        String apiKey = System.getenv(ENV_API_KEY);
        String model = System.getenv(ENV_MODEL);

        if (isBlank(baseUrl) || isBlank(apiKey) || isBlank(model)) {
            System.err.println("缺少模型配置，请先设置这三个环境变量：");
            System.err.println("  " + ENV_BASE_URL + "  例如 https://api.deepseek.com/v1");
            System.err.println("  " + ENV_API_KEY + "    模型服务的 API Key");
            System.err.println("  " + ENV_MODEL + "      例如 deepseek-chat");
            return;
        }

        String task = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

        LlmClient llmClient = new OpenAiCompatibleLlmClient(baseUrl, apiKey, model);

        List<Tool> tools = List.of(
                new ReadFileTool(project),
                new SearchCodeTool(project),
                new WriteFileTool(project),
                new RunTestsTool(new MavenTestRunner(), project),
                new ShellTool(project)
        );

        AgentLoop loop = new AgentLoop(llmClient, tools);

        System.out.println("项目：" + project);
        System.out.println("任务：" + task);
        System.out.println("模型：" + model);

        long startedAt = System.nanoTime();

        try {
            String answer = loop.run(task);
            System.out.println("\n=== Agent 的结论 ===");
            System.out.println(answer);
        } finally {
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

            System.out.println("\n=== 本次运行的度量 ===");
            System.out.println("步数：" + loop.transcript().size());
            System.out.println("耗时：" + elapsedMillis + " ms");
            System.out.println("token：" + llmClient.usage());

            // 终端只给摘要：完整轨迹动辄上千行，刷屏反而让人看不到重点
            System.out.println("\n=== 各阶段一览 ===");
            loop.transcript().forEach(entry -> System.out.println(summarize(entry)));

            System.out.println("\n完整轨迹已写入：" + writeTranscript(loop.transcript()));
        }
    }

    /** 把一条轨迹压成一行：阶段 + 这一步调用了什么。 */
    private static String summarize(String entry) {

        String[] lines = entry.split("\n");
        String head = lines.length > 0 ? lines[0] : entry;

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("调用了工具：") || line.startsWith("阶段结论：")) {
                return head + "  " + line;
            }
        }

        return head;
    }

    /** 完整轨迹写进临时文件——Agent 出错时，它是唯一的线索，但没必要刷屏。 */
    private static Path writeTranscript(List<String> transcript) {
        try {
            Path file = Path.of(
                    System.getProperty("java.io.tmpdir"),
                    "javafix-transcript-"
                            + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                            + ".log"
            );
            Files.writeString(file, String.join("\n\n", transcript), StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            return Path.of("（写入失败：" + e.getMessage() + "）");
        }
    }

    private static void usage() {
        System.out.println("用法：mvn -q exec:java -Dexec.args=\"<项目目录> <Bug 描述>\"");
        System.out.println();
        System.out.println("需要先在环境变量里配置模型：");
        System.out.println("  set " + ENV_BASE_URL + "=https://api.deepseek.com/v1");
        System.out.println("  set " + ENV_API_KEY + "=你的 key");
        System.out.println("  set " + ENV_MODEL + "=deepseek-chat");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
