package com.javafix.agent.tool;

import com.javafix.agent.core.TestResult;
import com.javafix.agent.core.TestRunner;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 {@link TestRunner} 包装成一个工具，让 Agent 能自己触发测试。
 *
 * <p>测试结果是这个项目里最重要的反馈信号：它决定 Agent 下一步改哪里。
 *
 * <p>把失败和调用栈优先整理成有界摘要；完整日志由执行器留在 target 下。
 */
public class RunTestsTool extends ProjectTool {

    /** 模型只看有界诊断视图；完整 Maven 日志仍保存在 target 下。 */
    private static final int MAX_LINES = 40;
    private static final int MAX_SUMMARY_CHARS = 3400;
    private static final int MAX_LINE_CHARS = 250;
    private static final int MAX_STDERR_CHARS = 400;

    private static final Pattern FAILED_COUNT = Pattern.compile("Failures: [1-9]|Errors: [1-9]");
    private static final Pattern PASSING_COUNT =
            Pattern.compile("Tests run: [1-9][0-9]*, Failures: 0, Errors: 0");
    private static final Pattern FAILURE_DETAIL = Pattern.compile(
            "<<< (?:FAILURE|ERROR)!|AssertionFailed|expected:|Caused by:|COMPILATION ERROR|(?:Exception|Error):");
    private static final Pattern STACK_FRAME = Pattern.compile(
            "^\\s*(?:\\[ERROR\\]\\s*)?at\\s+([\\w.$]+)\\([^)]*\\.java:\\d+\\)");

    private final TestRunner testRunner;

    public RunTestsTool(TestRunner testRunner, Path root) {
        super(root);
        this.testRunner = testRunner;
    }

    @Override
    public String name() {
        return "run_tests";
    }

    @Override
    public String description() {
        return "在仓库根目录执行 mvn test，返回构建与测试的输出。"
                + "参数：test（可选，只跑指定的测试类，例如 FooTest；真实仓库务必用它把范围收窄，"
                + "否则一次全量测试可能几十分钟）。";
    }

    @Override
    public String execute(Map<String, String> arguments) {

        rejectUnknownArguments(arguments, "test");

        TestResult result = testRunner.run(root, arguments.get("test"));

        StringBuilder observation = new StringBuilder();

        if (result.isSuccess()) {
            observation.append("测试通过。");
        } else if (result.getExitCode() == TestResult.EXIT_TIMEOUT) {
            // 超时和"跑完了但失败"是两回事：前者根本没有结论，不能当成失败证据
            observation.append("测试超时，没有跑完。");
        } else if (result.getExitCode() == TestResult.EXIT_START_FAILURE) {
            observation.append("测试未启动，没有拿到测试结果。");
        } else {
            observation.append("测试未通过。");
        }
        observation.append("（退出码 ").append(result.getExitCode()).append("）\n");
        observation.append(summarize(result.getStdout()));

        if (result.getStderr() != null && !result.getStderr().isBlank()) {
            observation.append("\n").append(shorten(result.getStderr().strip(), MAX_STDERR_CHARS));
        }

        return observation.toString();
    }

    /** 失败摘要和项目调用栈先于插件噪声，避免固定行数截断时丢掉定位证据。 */
    private String summarize(String log) {
        List<String> failedCounts = new ArrayList<>();
        List<String> details = new ArrayList<>();
        List<String> projectFrames = new ArrayList<>();
        List<String> frameworkFrames = new ArrayList<>();
        List<String> passingCounts = new ArrayList<>();
        List<String> zeroCounts = new ArrayList<>();
        List<String> buildResults = new ArrayList<>();
        List<String> otherErrors = new ArrayList<>();

        for (String line : log.lines().toList()) {
            String shown = shorten(line.strip(), MAX_LINE_CHARS);
            Matcher frame = STACK_FRAME.matcher(line);
            if (line.contains("Tests run:")) {
                if (FAILED_COUNT.matcher(line).find()) {
                    failedCounts.add(shown);
                } else if (PASSING_COUNT.matcher(line).find()) {
                    passingCounts.add(shown);
                } else {
                    zeroCounts.add(shown);
                }
            } else if (FAILURE_DETAIL.matcher(line).find()) {
                details.add(shown);
            } else if (frame.find()) {
                (isFrameworkFrame(frame.group(1)) ? frameworkFrames : projectFrames).add(shown);
            } else if (line.contains("BUILD SUCCESS") || line.contains("BUILD FAILURE")) {
                buildResults.add(shown);
            } else if (line.contains("[ERROR]")) {
                otherErrors.add(shown);
            }
        }

        LinkedHashSet<String> selected = new LinkedHashSet<>();
        addFirst(selected, failedCounts, 4);
        addFirst(selected, passingCounts, 2);
        addFirst(selected, zeroCounts, 1);
        if (!buildResults.isEmpty()) {
            selected.add(buildResults.get(buildResults.size() - 1));
        }
        addFirst(selected, details, 4);
        addFirst(selected, projectFrames, 8);
        if (projectFrames.isEmpty()) {
            addFirst(selected, frameworkFrames, 2);
        }
        selected.addAll(details.subList(Math.min(4, details.size()), Math.min(10, details.size())));
        addFirst(selected, otherErrors, 8);

        if (selected.isEmpty()) {
            return "（日志里没有可提炼的关键行，完整日志见 target/javafix-mvn-test.log）";
        }

        StringBuilder summary = new StringBuilder();
        int lines = 0;
        boolean omitted = failedCounts.size() + passingCounts.size() + zeroCounts.size()
                + buildResults.size() + details.size() + projectFrames.size()
                + frameworkFrames.size() + otherErrors.size() > selected.size();
        for (String line : selected) {
            if (lines >= MAX_LINES - 1 || summary.length() + line.length() + 1 > MAX_SUMMARY_CHARS - 60) {
                omitted = true;
                break;
            }
            if (lines++ > 0) {
                summary.append('\n');
            }
            summary.append(line);
        }
        if (omitted) {
            summary.append("\n（摘要已截断，完整日志见 target/javafix-mvn-test.log）");
        }
        return summary.toString();
    }

    private static void addFirst(LinkedHashSet<String> selected, List<String> lines, int limit) {
        selected.addAll(lines.subList(0, Math.min(limit, lines.size())));
    }

    private static boolean isFrameworkFrame(String className) {
        return className.startsWith("java.") || className.startsWith("jdk.")
                || className.startsWith("org.junit.") || className.startsWith("org.opentest4j.")
                || className.startsWith("org.apache.maven.") || className.startsWith("org.apache.surefire.");
    }

    private static String shorten(String value, int maxChars) {
        return value.length() <= maxChars ? value : value.substring(0, maxChars) + "…";
    }
}
