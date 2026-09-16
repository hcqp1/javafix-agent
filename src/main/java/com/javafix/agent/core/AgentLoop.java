package com.javafix.agent.core;

import com.javafix.agent.llm.LlmClient;
import com.javafix.agent.tool.Tool;
import com.javafix.agent.tool.WorkspaceStatus;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Agent 主循环，按「复现 -> 定位 -> 修复 -> 验证」四个阶段编排。
 *
 * <p>输入是用户报告的现象，Agent 自己复现、自己定位、自己修、自己验证。
 * 阶段的推进由两条路触发：模型输出 FINAL 表示"这个阶段我做完了"，
 * 或者代码识别到可判定的出口条件（比如复现阶段看到了失败测试）。
 *
 * <p>步数预算是分层的：每个阶段有自己的预算，总和是整条任务的兜底预算。
 * 预算还会写进每一步的提示词——<b>预算只有在被看见的时候才会影响行为</b>；
 * 之前只给一个总数、模型完全不知道还剩多少，结果是 16 步全耗在第一个阶段的探索上。
 */
public class AgentLoop {

    /** 整条任务的兜底预算：各阶段预算之和。 */
    private static final int DEFAULT_MAX_STEPS =
            Arrays.stream(Phase.values()).mapToInt(Phase::stepBudget).sum();

    /**
     * 单条观察结果回灌给模型时的长度上限。
     *
     * <p>一次 read_file 就可能带回几百行，而这些内容会被完整写进轨迹、
     * 并在之后的每一步重新发一遍。
     */
    private static final int MAX_OBSERVATION_CHARS = 4000;

    /** 复现阶段连续这么多步还没有产出，就开始催。 */
    private static final int STALL_THRESHOLD = 3;

    /** 验证失败最多退回修复几轮，防止"改—验—再改"无限循环。 */
    private static final int MAX_FIX_ROUNDS = 3;

    /** 一条测试失败，用于判定"复现"阶段完成。 */
    private static final Pattern FAILING_TEST =
            Pattern.compile("Tests run:.*(Failures: [1-9]|Errors: [1-9])");

    /** 一条测试通过，用于判定"验证"阶段完成。 */
    private static final Pattern PASSING_TEST =
            Pattern.compile("Tests run:.*(Failures: 0, Errors: 0)");

    private static final String ACTION_FORMAT = """

            请严格按下面的格式回复，每次只输出一个动作。
            第一行以 THOUGHT 加冒号开头，写你这一步的思路：只写一行，控制在 50 字以内，
            不要复述已经知道的信息，也不要写长篇推理。
            要调用工具时，回复由若干行组成：第二行以 TOOL 加冒号开头，写工具名；
            之后每个参数占一行，行首是参数名
            （全大写英文，例如 PATH、CONTENT）加冒号，后面跟参数值。
            参数值可以有多行（比如一整个文件的内容）：从参数名那一行的下一行开始写，
            一直写到下一个参数名为止。
            分隔符请用英文半角冒号，不要用中文输入法打出来的全角冒号。
            当前阶段完成时，用 FINAL 加冒号输出本阶段的结论。
            一条回复里只给一个动作，不要写多个动作块。
            """;

    private final LlmClient llmClient;
    private final List<Tool> tools;
    private final int maxSteps;
    private final List<String> transcript = new ArrayList<>();

    /** 进度回调：每一步都会回一行简短描述，供命令行实时显示。默认什么也不做。 */
    private Consumer<String> progress = line -> {
    };

    public AgentLoop(LlmClient llmClient, List<Tool> tools) {
        this(llmClient, tools, DEFAULT_MAX_STEPS);
    }

    public AgentLoop(LlmClient llmClient, List<Tool> tools, int maxSteps) {
        this.llmClient = llmClient;
        this.tools = List.copyOf(tools);
        this.maxSteps = maxSteps;
    }

    public LlmClient llmClient() {
        return llmClient;
    }

    public List<Tool> tools() {
        return tools;
    }

    /**
     * 注册进度回调。
     *
     * <p>一次真实运行可能要十几分钟（中间夹着好几分钟的 Maven 构建），如果期间什么都不打印，
     * 用户分不清是在干活还是卡死了。有了这个回调，每一步都会实时回一行。
     */
    public AgentLoop onProgress(Consumer<String> listener) {
        this.progress = listener == null ? line -> {
        } : listener;
        return this;
    }

    /** 运行轨迹：每一步的阶段、动作与观察结果。 */
    public List<String> transcript() {
        return List.copyOf(transcript);
    }

    /**
     * 运行 Agent，直到验证通过或预算用尽。
     *
     * @param symptom 用户报告的现象，例如"下单偶尔少一条记录"，而不是已经定位好的位置
     * @return 验证通过时是最终答复；预算用尽时是一份状态报告
     */
    public String run(String symptom) {

        Phase phase = Phase.REPRODUCE;
        String lastSummary = null;
        int phaseSteps = 0;
        int unproductiveSteps = 0;
        boolean sourceChanged = false;
        boolean sawPassingTest = false;
        int fixRounds = 1;
        Phase countedPhase = null;
        int testsRunInPhase = 0;
        boolean lastTestPassedInPhase = false;

        for (int step = 1; step <= maxSteps; step++) {

            // 每个阶段的"跑过几次测试"单独计数
            if (countedPhase != phase) {
                countedPhase = phase;
                testsRunInPhase = 0;
                lastTestPassedInPhase = false;
            }

            emit("[" + phase.label() + " " + (phaseSteps + 1) + "/" + phase.stepBudget() + "] 思考中…");

            String response = llmClient.complete(
                    buildPrompt(symptom, phase, lastSummary, step, phaseSteps,
                            buildNudge(phase, phaseSteps, unproductiveSteps, sawPassingTest, sourceChanged))
            );

            Action action;
            try {
                action = Action.parse(response);
            } catch (RuntimeException e) {
                record(phase, step, "(无法解析的回复)\n" + response, "解析失败：" + e.getMessage());
                emit("[" + phase.label() + " " + (phaseSteps + 1) + "/" + phase.stepBudget()
                        + "] ！回复格式不对，已要求重试");
                phaseSteps++;
                continue;
            }

            if (action.isFinal()) {
                record(phase, step, "阶段结论：" + action.finalAnswer(), "");
                lastSummary = action.finalAnswer();

                // 用工作区状态判断"到底改没改代码"，而不是看 write_file 的返回文本——
                // 模型改用 shell 编辑文件就绕过去了，真实运行里发生过
                boolean codeChanged = sourceChanged || mainSourcesChangedNow();

                if (phase == Phase.VERIFY) {
                    // 一次测试都没跑就说验证完成——这是最常见的"假完成"，直接拒绝
                    if (testsRunInPhase == 0) {
                        emit("验证阶段一次测试都没跑，不许结束");
                        record(phase, step, "（验证阶段未跑测试）",
                                "验证阶段还没有运行过任何测试，无法确认修复是否有效。"
                                        + "下一步必须用 run_tests 跑一次相关的测试。");
                        phaseSteps++;
                        continue;
                    }

                    if (!lastTestPassedInPhase && fixRounds < MAX_FIX_ROUNDS) {
                        fixRounds++;
                        emit("验证不通过，退回修复阶段（第 " + fixRounds + " 轮）");
                        record(phase, step, "（验证退回）", "验证阶段的测试没有全绿，退回修复阶段继续改。");
                        phase = Phase.FIX;
                        phaseSteps = 0;
                        unproductiveSteps = 0;
                        continue;
                    }

                    // 验证阶段发现"什么都没改"，说明修复阶段是空转的，退回重做
                    if (!codeChanged && fixRounds < MAX_FIX_ROUNDS) {
                        fixRounds++;
                        emit("没有任何代码改动，退回修复阶段（第 " + fixRounds + " 轮）");
                        record(phase, step, "（验证退回）",
                                "仓库里没有任何代码改动，说明修复阶段没有产出，退回修复阶段重做。");
                        phase = Phase.FIX;
                        phaseSteps = 0;
                        unproductiveSteps = 0;
                        continue;
                    }
                    emit("验证阶段结束，任务完成");
                    return lastSummary;
                }

                // 修复阶段不许空手离开：阶段出口由代码判定，不交给模型自觉
                if (phase == Phase.FIX && !codeChanged && phaseSteps < phase.stepBudget()) {
                    emit("修复阶段还没改任何文件，不许离开这一阶段");
                    record(phase, step, "（修复阶段未产出）",
                            "修复阶段到目前为止没有改动任何文件——只在代码里查找不算完成修复。"
                                    + "下一步必须用 write_file 落地一个最小改动（用 shell 改文件不算，"
                                    + "那种改动不会被流程认可）。");
                    phaseSteps++;
                    continue;
                }

                Phase next = phase.next();
                emit("阶段推进：" + phase.label() + " → " + next.label());
                phase = next;
                phaseSteps = 0;
                unproductiveSteps = 0;
                continue;
            }

            // 复现阶段禁止改生产代码——这条靠代码强制，而不是靠提示词请求模型配合
            if (phase == Phase.REPRODUCE && isMainSourceWrite(action)) {
                record(
                        phase,
                        step,
                        describe(action),
                        "复现阶段禁止修改 src/main 下的代码；先把现象复现成 src/test 下的一条失败测试。"
                );
                emit("[" + phase.label() + " " + (phaseSteps + 1) + "/" + phase.stepBudget()
                        + "] ！拦下一次改生产代码的尝试");
                phaseSteps++;
                unproductiveSteps++;
                continue;
            }

            // 复现阶段的第一个动作必须是跑测试：没有基线就谈不上"复现"
            if (phase == Phase.REPRODUCE && testsRunInPhase == 0 && !"run_tests".equals(action.toolName())) {
                record(
                        phase,
                        step,
                        describe(action),
                        "复现阶段的第一个动作必须是 run_tests——先跑一遍现有测试，看清有没有已经失败的。"
                                + "在那之前不做别的。"
                );
                emit("[" + phase.label() + " " + (phaseSteps + 1) + "/" + phase.stepBudget()
                        + "] ！复现阶段的第一个动作必须是 run_tests");
                phaseSteps++;
                unproductiveSteps++;
                continue;
            }

            emit("[" + phase.label() + " " + (phaseSteps + 1) + "/" + phase.stepBudget()
                    + "] → " + action.toolName());

            long toolStartedAt = System.nanoTime();
            String observation = execute(action);

            emit("[" + phase.label() + " " + (phaseSteps + 1) + "/" + phase.stepBudget()
                    + "] ← " + elapsedSeconds(toolStartedAt) + "s  " + firstLine(observation));

            record(phase, step, describe(action), observation);
            phaseSteps++;

            boolean wroteFile = "write_file".equals(action.toolName()) && observation.startsWith("已写入 ");
            boolean ranTests = "run_tests".equals(action.toolName());

            if (wroteFile) {
                sourceChanged = true;
            }
            if (ranTests) {
                testsRunInPhase++;
                lastTestPassedInPhase = looksPassing(observation);
                if (lastTestPassedInPhase) {
                    sawPassingTest = true;
                }
            }

            // "产出"指的是写文件或跑测试；只是搜索、读文件不算——那样可以永远探索下去
            unproductiveSteps = (wroteFile || ranTests) ? 0 : unproductiveSteps + 1;

            if (phase == Phase.REPRODUCE && FAILING_TEST.matcher(observation).find()) {
                lastSummary = "已复现一条失败测试";
                emit("阶段推进：复现 → 定位（已经有一条失败的测试了）");
                phase = Phase.LOCALIZE;
                phaseSteps = 0;
                unproductiveSteps = 0;
                continue;
            }

            if (phase == Phase.VERIFY && looksPassing(observation)) {
                return lastSummary == null ? "验证通过" : lastSummary;
            }

            // 验证阶段跑出失败：说明修得不彻底，退回修复阶段
            if (phase == Phase.VERIFY && FAILING_TEST.matcher(observation).find() && fixRounds < MAX_FIX_ROUNDS) {
                fixRounds++;
                emit("验证不通过，退回修复阶段（第 " + fixRounds + " 轮）");
                record(phase, step, "（验证退回）", "验证阶段跑出失败测试，退回修复阶段继续改。");
                phase = Phase.FIX;
                phaseSteps = 0;
                unproductiveSteps = 0;
                continue;
            }

            if (phaseSteps >= phase.stepBudget()) {
                record(
                        phase,
                        step,
                        "（本阶段预算用完）",
                        "「" + phase.label() + "」最多 " + phase.stepBudget() + " 步，已用完，转入下一阶段。"
                );
                lastSummary = "「" + phase.label() + "」阶段目标未达成，步数预算已用完";
                Phase next = phase.next();
                emit("本阶段预算用完，转入：" + next.label());
                phase = next;
                phaseSteps = 0;
                unproductiveSteps = 0;
            }
        }

        emit("步数预算用尽，输出状态报告");
        return report(symptom, phase, lastSummary);
    }

    /** 观察结果看起来是"全部通过"：有通过行，且没有任何失败行。 */
    private static boolean looksPassing(String observation) {
        return PASSING_TEST.matcher(observation).find() && !FAILING_TEST.matcher(observation).find();
    }

    private void emit(String line) {
        progress.accept(line);
    }

    private static String elapsedSeconds(long startedAt) {
        return String.format(java.util.Locale.ROOT, "%.1f", (System.nanoTime() - startedAt) / 1_000_000_000.0);
    }

    /** 观察结果的第一行，截断到 100 字符——进度提示只需要点一下发生了什么。 */
    private static String firstLine(String observation) {

        if (observation == null || observation.isBlank()) {
            return "(无输出)";
        }

        String line = observation.strip().lines().findFirst().orElse("").strip();
        return line.length() <= 100 ? line : line.substring(0, 100) + "…";
    }

    /** 从工具身上找到项目根目录，再问 git 工作区有没有改动。 */
    private boolean mainSourcesChangedNow() {
        return tools.stream()
                .map(Tool::projectRoot)
                .flatMap(java.util.Optional::stream)
                .findFirst()
                .map(WorkspaceStatus::mainSourcesChanged)
                .orElse(false);
    }

    /** 到预算上限时给一份状态报告，而不是抛异常——白跑十几步什么都不留是最差的结果。 */
    private String report(String symptom, Phase phase, String lastSummary) {

        StringBuilder report = new StringBuilder();
        report.append("没能完成这个任务。以下是停下来时的状态。\n\n");
        report.append("任务：").append(symptom).append('\n');
        report.append("停在阶段：").append(phase.label()).append('\n');
        report.append("总步数预算 ").append(maxSteps).append(" 步已用尽\n");

        if (lastSummary != null) {
            report.append("上一阶段的结论：").append(lastSummary).append('\n');
        }

        report.append("\n最后几步在做什么：\n");
        int from = Math.max(0, transcript.size() - 3);
        for (int i = from; i < transcript.size(); i++) {
            report.append("- ").append(brief(transcript.get(i))).append('\n');
        }

        report.append("\n建议：根据上面的轨迹判断是线索不足、还是工具或环境的问题，再决定是否重跑。\n");
        return report.toString();
    }

    private static String brief(String entry) {

        String[] lines = entry.split("\n");
        StringBuilder brief = new StringBuilder(lines.length > 0 ? lines[0] : entry);

        for (int i = 1; i < lines.length; i++) {
            if (lines[i].startsWith("调用了工具：") || lines[i].startsWith("阶段结论：")) {
                brief.append("  ").append(lines[i]);
                break;
            }
        }

        return brief.toString();
    }

    /** 该催的时候催一句：快用完预算了，或者连续好几步只探索没产出。 */
    private static String buildNudge(
            Phase phase,
            int phaseSteps,
            int unproductiveSteps,
            boolean sawPassingTest,
            boolean sourceChanged) {

        if (phase == Phase.REPRODUCE) {

            if (sawPassingTest && unproductiveSteps >= 1) {
                return "现有测试全部通过，说明这个现象根本没有被测试覆盖——所以复现只能靠你自己写。"
                        + "下一步必须写出一条能失败的测试（write_file 到 src/test 下）；"
                        + "可以打开同一个测试目录下已有的测试，照着它的骨架来写。";
            }

            if (unproductiveSteps >= STALL_THRESHOLD) {
                return "你已经连续 " + unproductiveSteps + " 步只做探索、没有产出。下一步必须二选一："
                        + "写出复现测试（write_file 到 src/test 下），或者运行测试（run_tests）。";
            }

            if (phaseSteps >= phase.stepBudget() - 1) {
                return "本阶段只剩最后一步：请在这一步内拿出能失败的复现测试，"
                        + "否则将带着「未复现」进入下一阶段。";
            }

            return null;
        }

        if (phase == Phase.FIX && !sourceChanged) {
            return "修复阶段到现在还没有改动任何文件。下一步必须用 write_file 落地一个最小改动——"
                    + "继续读代码、继续搜索都不算完成这个阶段。";
        }

        return null;
    }

    private String buildPrompt(
            String symptom,
            Phase phase,
            String lastSummary,
            int step,
            int phaseSteps,
            String nudge) {

        StringBuilder prompt = new StringBuilder();

        prompt.append("你是一个 Java 缺陷修复助手，按阶段工作。当前阶段：")
                .append(phase.label())
                .append("\n\n");
        prompt.append(phase.instruction()).append("\n");

        prompt.append("\n预算：整个任务共 ").append(maxSteps).append(" 步，已用 ").append(step - 1)
                .append(" 步；当前阶段最多 ").append(phase.stepBudget())
                .append(" 步，已用 ").append(phaseSteps).append(" 步。\n");

        if (nudge != null) {
            prompt.append("\n注意：").append(nudge).append('\n');
        }

        prompt.append("\n用户报告的现象：\n").append(symptom).append("\n\n");

        prompt.append("可用工具：\n");
        for (Tool tool : tools) {
            prompt.append("- ").append(tool.name()).append("：").append(tool.description()).append('\n');
        }

        prompt.append(ACTION_FORMAT);

        if (lastSummary != null) {
            prompt.append("\n上一阶段的结论：").append(lastSummary).append('\n');
        }

        if (!transcript.isEmpty()) {
            prompt.append("\n到目前为止的进展：\n");
            transcript.forEach(entry -> prompt.append(entry).append('\n'));
        }

        return prompt.toString();
    }

    /**
     * 判断一个 write_file 动作是不是想改生产代码。
     * 复现阶段只允许在 src/test 下写复现测试。
     */
    private static boolean isMainSourceWrite(Action action) {

        if (!"write_file".equals(action.toolName())) {
            return false;
        }

        String path = action.arguments().get("path");
        if (path == null) {
            return false;
        }

        String normalized = path.replace('\\', '/');
        return normalized.equals("src/main") || normalized.startsWith("src/main/");
    }

    /**
     * 分发动作到工具。模型编出不存在的工具名，或者工具自身抛异常，
     * 都只作为观察结果返回——对 Agent 来说"这一步做错了"和"这一步没做成"是同一种信息。
     */
    private String execute(Action action) {

        Tool tool = tools.stream()
                .filter(candidate -> candidate.name().equals(action.toolName()))
                .findFirst()
                .orElse(null);

        if (tool == null) {
            return "没有名为 " + action.toolName() + " 的工具。可用工具：" + toolNames();
        }

        try {
            return tool.execute(action.arguments());
        } catch (RuntimeException e) {
            return "工具 " + action.toolName() + " 执行失败：" + e;
        }
    }

    /**
     * 把一步动作渲染给模型看。
     *
     * <p>刻意不按协议的样子写（不出现 TOOL、参数名这类形状）：模型会把提示词和轨迹里
     * "看起来能直接照抄的形状"当成模板模仿。协议只在 {@code ACTION_FORMAT} 那一处说明。
     */
    private String describe(Action action) {

        StringBuilder description = new StringBuilder("思路：" + action.thought());
        description.append("\n调用了工具：").append(action.toolName());

        if (action.arguments().isEmpty()) {
            return description.append("\n参数：无").toString();
        }

        action.arguments().forEach((name, value) ->
                description.append("\n参数 ").append(name).append(" = ").append(shorten(value)));

        return description.toString();
    }

    /**
     * 参数值的可读形式：只留第一行，最长 200 字符。
     *
     * <p>之前为了防"格式被照抄"，轨迹里只显示参数名不显示值。结果排查时看不出模型到底执行了什么命令，
     * 模型自己也失去了"我刚跑过什么"的记忆。现在用「参数 名字 = 值」这种描述性写法，
     * 它不像动作语法，不会被当成模板照抄。
     */
    private static String shorten(String value) {

        if (value == null) {
            return "(空)";
        }

        String firstLine = value.strip().lines().findFirst().orElse("").strip();
        String shown = firstLine.length() <= 200 ? firstLine : firstLine.substring(0, 200) + "…";

        return value.lines().count() > 1 ? shown + "（还有更多行）" : shown;
    }

    private String toolNames() {
        return tools.stream().map(Tool::name).toList().toString();
    }

    private void record(Phase phase, int step, String action, String observation) {
        transcript.add("[" + phase.label() + "] 步骤 " + step
                + "\n" + action
                + "\n观察：" + trim(observation));
    }

    /** 观察结果超长就截断，并明确告诉模型"这里被截断了，完整内容要重新读"。 */
    private static String trim(String observation) {

        if (observation == null || observation.length() <= MAX_OBSERVATION_CHARS) {
            return observation;
        }

        return observation.substring(0, MAX_OBSERVATION_CHARS)
                + "\n（内容过长已截断，原文共 " + observation.length()
                + " 字符；需要完整内容请重新读取相关部分）";
    }
}
