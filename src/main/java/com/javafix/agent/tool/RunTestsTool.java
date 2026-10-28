package com.javafix.agent.tool;

import com.javafix.agent.core.TestResult;
import com.javafix.agent.core.TestRunner;

import java.nio.file.Path;
import java.util.ArrayList;
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
    private static final int MAX_FAILURE_CASES = 2;

    private static final Pattern FAILED_COUNT = Pattern.compile("Failures: [1-9]|Errors: [1-9]");
    private static final Pattern PASSING_COUNT =
            Pattern.compile("Tests run: [1-9][0-9]*, Failures: 0, Errors: 0");
    private static final Pattern FAILURE_DETAIL = Pattern.compile(
            "<<< (?:FAILURE|ERROR)!|AssertionFailed|expected:|Caused by:|COMPILATION ERROR|(?:Exception|Error):");
    private static final Pattern STACK_FRAME = Pattern.compile(
            "^\\s*(?:\\[ERROR\\]\\s*)?at\\s+([\\w.$]+)\\([^)]*\\.java:\\d+\\)");
    private static final Pattern ENVIRONMENT_FAILURE = Pattern.compile(
            "Setting a Security Manager is not supported|Unsupported class file major version|"
                    + "UnsupportedClassVersionError|Could not resolve dependencies|Could not transfer artifact|"
                    + "PluginResolutionException|JAVA_HOME.*(?:not defined|incorrect)|"
                    + "Maven version.*(?:required|must be)|requires Maven version",
            Pattern.CASE_INSENSITIVE);

    private final TestRunner testRunner;

    private static final class FailureCase {
        final String header;
        final List<String> details = new ArrayList<>();
        final List<String> projectFrames = new ArrayList<>();
        final List<String> frameworkFrames = new ArrayList<>();

        FailureCase(String header) {
            this.header = header;
        }
    }

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
        return "在仓库根目录执行 Maven 测试，并区分测试通过、测试用例失败、构建/环境失败、超时和未启动。"
                + "只有出现测试用例失败时，才可将结果作为 Bug 复现证据；构建或环境失败不是测试失败。"
                + "参数：test（可选，只跑指定的测试类，例如 FooTest；真实仓库务必用它把范围收窄，"
                + "否则一次全量测试可能几十分钟）。";
    }

    @Override
    public String execute(Map<String, String> arguments) {

        rejectUnknownArguments(arguments, "test");

        TestResult result = testRunner.run(root, arguments.get("test"));

        StringBuilder observation = new StringBuilder();

        String log = (result.getStdout() == null ? "" : result.getStdout()) + "\n"
                + (result.getStderr() == null ? "" : result.getStderr());
        boolean hasTestReport = log.contains("Tests run:");
        if (result.isSuccess()) {
            observation.append("测试通过。");
        } else if (result.getExitCode() == TestResult.EXIT_TIMEOUT) {
            // 超时和"跑完了但失败"是两回事：前者根本没有结论，不能当成失败证据
            observation.append("测试超时，没有跑完。环境/耗时问题，不是测试失败结论。");
        } else if (result.getExitCode() == TestResult.EXIT_START_FAILURE) {
            observation.append("测试未启动，没有拿到测试结果。请检查构建工具和项目路径。");
        } else if (ENVIRONMENT_FAILURE.matcher(log).find()) {
            observation.append("测试环境失败，未得到测试结论。命中工具链/依赖兼容错误；不要把它当成 Bug 复现。");
        } else if (!hasTestReport && log.contains("BUILD FAILURE")) {
            observation.append("构建失败，未得到测试结论。测试用例尚未运行；请先处理构建错误。");
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
        List<FailureCase> failureCases = new ArrayList<>();
        FailureCase currentCase = null;
        boolean inCase = false;
        int caseCount = 0;
        int relevantLines = 0;

        for (String line : log.lines().toList()) {
            String shown = shorten(line.strip(), MAX_LINE_CHARS);
            if (line.contains("Tests run:")) {
                relevantLines++;
                inCase = false;
                currentCase = null;
                if (FAILED_COUNT.matcher(line).find()) {
                    failedCounts.add(shown);
                } else if (PASSING_COUNT.matcher(line).find()) {
                    passingCounts.add(shown);
                } else {
                    zeroCounts.add(shown);
                }
                continue;
            }
            if (line.contains("BUILD SUCCESS") || line.contains("BUILD FAILURE")) {
                relevantLines++;
                inCase = false;
                currentCase = null;
                buildResults.add(shown);
                continue;
            }
            if (line.contains("<<< FAILURE!") || line.contains("<<< ERROR!")) {
                relevantLines++;
                inCase = true;
                caseCount++;
                currentCase = null;
                if (failureCases.size() < MAX_FAILURE_CASES) {
                    currentCase = new FailureCase(shorten(
                            line.strip().replaceFirst("^\\[ERROR\\]\\s*", ""), MAX_LINE_CHARS));
                    failureCases.add(currentCase);
                }
                continue;
            }
            Matcher frame = STACK_FRAME.matcher(line);
            boolean isFrame = frame.find();
            if (inCase) {
                boolean isDetail = FAILURE_DETAIL.matcher(line).find();
                if (isDetail || isFrame) {
                    relevantLines++;
                }
                if (currentCase != null) {
                    if (isDetail && currentCase.details.size() < 2) {
                        currentCase.details.add(shown);
                    } else if (isFrame) {
                        List<String> frames = isFrameworkFrame(frame.group(1))
                                ? currentCase.frameworkFrames : currentCase.projectFrames;
                        if (frames.size() < 2) {
                            frames.add(shown);
                        }
                    }
                }
                continue;
            }
            if (FAILURE_DETAIL.matcher(line).find()) {
                relevantLines++;
                details.add(shown);
            } else if (isFrame) {
                relevantLines++;
                (isFrameworkFrame(frame.group(1)) ? frameworkFrames : projectFrames).add(shown);
            } else if (line.contains("[ERROR]")) {
                relevantLines++;
                otherErrors.add(shown);
            }
        }

        List<String> selected = new ArrayList<>();
        addFirst(selected, failedCounts, failureCases.isEmpty() ? 4 : 1);
        if (!failureCases.isEmpty() && !buildResults.isEmpty()) {
            selected.add(buildResults.get(buildResults.size() - 1));
        }
        for (int i = 0; i < failureCases.size(); i++) {
            FailureCase failed = failureCases.get(i);
            selected.add(shorten("失败用例 " + (i + 1) + "/" + caseCount + ": " + failed.header,
                    MAX_LINE_CHARS));
            addFirst(selected, failed.details, 2);
            addFirst(selected, failed.projectFrames.isEmpty()
                    ? failed.frameworkFrames : failed.projectFrames, 2);
        }
        if (caseCount > failureCases.size()) {
            selected.add("另有 " + (caseCount - failureCases.size()) + " 个失败用例未展示，完整日志见 target/javafix-mvn-test.log");
        }
        if (!failureCases.isEmpty()) {
            addFirst(selected, failedCounts.subList(Math.min(1, failedCounts.size()), failedCounts.size()), 3);
        }
        addFirst(selected, passingCounts, 2);
        addFirst(selected, zeroCounts, 1);
        if (failureCases.isEmpty() && !buildResults.isEmpty()) {
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
        int syntheticLines = caseCount > failureCases.size() ? 1 : 0;
        boolean omitted = relevantLines > selected.size() - syntheticLines;
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

    private static void addFirst(List<String> selected, List<String> lines, int limit) {
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
