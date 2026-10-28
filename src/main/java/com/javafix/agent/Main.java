package com.javafix.agent;

import com.javafix.agent.chat.ChatSession;
import com.javafix.agent.config.ModelConfig;
import com.javafix.agent.config.ModelConfigStore;
import com.javafix.agent.task.TaskStore;
import com.javafix.agent.core.AgentLoop;
import com.javafix.agent.llm.LlmClient;
import com.javafix.agent.llm.OpenAiCompatibleLlmClient;
import com.javafix.agent.tool.MavenTestRunner;
import com.javafix.agent.tool.ReadFileTool;
import com.javafix.agent.tool.ReplaceInFileTool;
import com.javafix.agent.tool.RunTestsTool;
import com.javafix.agent.tool.SearchCodeTool;
import com.javafix.agent.tool.ShellTool;
import com.javafix.agent.tool.Tool;
import com.javafix.agent.tool.TraceDiagnosticTool;
import com.javafix.agent.tool.WriteFileTool;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 命令行入口。
 *
 * <pre>
 * mvn -q exec:java -Dexec.args="&lt;项目目录&gt; &lt;Bug 描述&gt;"
 * </pre>
 *
 * <p>模型配置从环境变量读取，不写进代码也不进仓库：
 * 持续聊天模式从用户目录配置读取模型设置；环境变量可覆盖配置文件。
 */
public class Main {

    private static final String ENV_BASE_URL = "JAVAFIX_LLM_BASE_URL";
    private static final String ENV_API_KEY = "JAVAFIX_LLM_API_KEY";
    private static final String ENV_MODEL = "JAVAFIX_LLM_MODEL";

    public static void main(String[] args) {

        if (args.length == 0) {
            runChat();
            return;
        }

        if (args.length < 2) {
            usage();
            return;
        }

        Path project = Path.of(args[0]).toAbsolutePath().normalize();

        if (!Files.isDirectory(project)) {
            System.err.println("项目目录不存在：" + project);
            return;
        }

        ModelConfig config;
        try {
            config = resolveModelConfig(false);
        } catch (IOException | IllegalStateException e) {
            System.err.println(e.getMessage());
            return;
        }

        String task = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

        RunJournal journal;
        try {
            journal = RunJournal.create(
                    Path.of(System.getProperty("user.home"), ".javafix-agent", "runs"), project, task);
        } catch (IOException e) {
            System.err.println("无法创建运行记录：" + e.getMessage());
            return;
        }

        LlmClient llmClient = new OpenAiCompatibleLlmClient(config.baseUrl(), config.apiKey(), config.model());

        List<Tool> tools = createTools(project);

        AgentLoop loop = new AgentLoop(llmClient, tools);
        loop.onRecord(journal::appendStep);

        // 实时进度：一次运行可能十几分钟，中间还夹着几分钟的 Maven 构建，
        // 不打这行日志的话，用户会以为程序卡死了。
        loop.onProgress(line -> System.out.println(
                "[" + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "] " + line
        ));

        System.out.println("项目：" + project);
        System.out.println("任务：" + task);
        System.out.println("模型：" + config.model());
        System.out.println("运行记录：" + journal.path());

        long startedAt = System.nanoTime();
        boolean returned = false;

        try {
            String answer = loop.run(task);
            returned = true;
            System.out.println("\n=== Agent 的结论 ===");
            System.out.println(answer);
        } finally {
            try {
                journal.finish(returned ? "returned" : "error");
            } catch (UncheckedIOException e) {
                System.err.println("无法保存运行结束状态：" + e.getMessage());
            }
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

            System.out.println("\n=== 本次运行的度量 ===");
            System.out.println("步数：" + loop.transcript().size());
            System.out.println("耗时：" + elapsedMillis + " ms");
            System.out.println("token：" + llmClient.usage());

            // 终端只给摘要：完整轨迹动辄上千行，刷屏反而让人看不到重点
            System.out.println("\n=== 各阶段一览 ===");
            loop.transcript().forEach(entry -> System.out.println(summarize(entry)));

            System.out.println("\n完整轨迹已逐步写入：" + journal.path());
        }
    }

    public static List<Tool> createTools(Path project) {
        return List.of(
                new ReadFileTool(project),
                new ReplaceInFileTool(project),
                new SearchCodeTool(project),
                new WriteFileTool(project),
                new RunTestsTool(new MavenTestRunner(), project),
                new ShellTool(project),
                new TraceDiagnosticTool(new MavenTestRunner(Duration.ofMinutes(5)), project)
        );
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

    private static void usage() {
        System.out.println("用法：mvn -q exec:java -Dexec.args=\"<项目目录> <Bug 描述>\"");
        System.out.println();
        System.out.println("不带参数启动持续聊天：mvn -q exec:java");
        System.out.println("模型设置保存在用户目录 .javafix-agent/config.json；也支持环境变量覆盖：");
        System.out.println("  set " + ENV_BASE_URL + "=https://api.deepseek.com/v1");
        System.out.println("  set " + ENV_API_KEY + "=你的 key");
        System.out.println("  set " + ENV_MODEL + "=deepseek-chat");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void runChat() {
        try {
            ModelConfig config = resolveModelConfig(true);
            Path dataDirectory = Path.of(System.getProperty("user.home"), ".javafix-agent");
            ChatSession session = new ChatSession(
                    new BufferedReader(new InputStreamReader(System.in)),
                    new PrintWriter(System.out, true),
                    new TaskStore(dataDirectory.resolve("tasks")),
                    new OpenAiCompatibleLlmClient(config.baseUrl(), config.apiKey(), config.model()));
            session.run();
        } catch (IOException | IllegalStateException e) {
            System.err.println(e.getMessage());
        }
    }

    private static ModelConfig resolveModelConfig(boolean allowInteractiveSetup) throws IOException {
        Path dataDirectory = Path.of(System.getProperty("user.home"), ".javafix-agent");
        ModelConfigStore store = new ModelConfigStore(dataDirectory.resolve("config.json"));
        ModelConfig saved = store.load().orElse(null);
        Map<String, String> environment = System.getenv();

        if (saved != null) {
            try {
                return ModelConfigStore.fromEnvironment(saved, environment);
            } catch (IllegalStateException e) {
                // A complete saved config should make this unreachable; report the config issue plainly.
                throw new IOException("用户目录中的模型配置无效，请检查 .javafix-agent/config.json", e);
            }
        }

        if (hasEnvironmentConfig(environment)) {
            ModelConfig fromEnvironment = ModelConfigStore.fromEnvironment(null, environment);
            store.save(fromEnvironment);
            return fromEnvironment;
        }

        if (!allowInteractiveSetup) {
            throw new IllegalStateException("尚未配置模型。请不带参数启动 JavaFixAgent 完成首次配置，"
                    + "或设置 JAVAFIX_LLM_BASE_URL、JAVAFIX_LLM_API_KEY、JAVAFIX_LLM_MODEL。");
        }

        java.io.Console console = System.console();
        if (console == null) {
            throw new IllegalStateException("首次配置需要交互式终端以隐藏输入 API Key。"
                    + "请在系统终端启动，或设置 JAVAFIX_LLM_BASE_URL、JAVAFIX_LLM_API_KEY、JAVAFIX_LLM_MODEL。");
        }
        String baseUrl = console.readLine("模型 API Base URL [https://api.deepseek.com/v1]: ");
        if (isBlank(baseUrl)) baseUrl = "https://api.deepseek.com/v1";
        String model = console.readLine("模型名称 [deepseek-chat]: ");
        if (isBlank(model)) model = "deepseek-chat";
        char[] secret = console.readPassword("API Key（输入不回显）: ");
        if (secret == null || secret.length == 0) throw new IllegalStateException("API Key 不能为空。");
        ModelConfig config = new ModelConfig(baseUrl, new String(secret), model);
        java.util.Arrays.fill(secret, '\0');
        store.save(config);
        System.out.println("模型配置已保存到用户目录 .javafix-agent/config.json。");
        return config;
    }

    private static boolean hasEnvironmentConfig(Map<String, String> environment) {
        return !isBlank(environment.get(ENV_BASE_URL))
                && !isBlank(environment.get(ENV_API_KEY))
                && !isBlank(environment.get(ENV_MODEL));
    }
}
