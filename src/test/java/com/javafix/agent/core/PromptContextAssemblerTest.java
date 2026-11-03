package com.javafix.agent.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptContextAssemblerTest {

    private final PromptContextAssembler assembler = new PromptContextAssembler();

    @Test
    void keepsPreviousPhaseConclusionsAndRecentCurrentPhaseEvidence() {
        List<String> transcript = List.of(
                "[复现] 步骤 2\n调用了工具：run_tests\n观察：Tests run: 1, Failures: 1, Errors: 0",
                "[复现] 步骤 3\n阶段结论：PullAPIWrapperTest 已复现",
                "[定位] 步骤 4\n调用了工具：read_file\n观察：PullAPIWrapper.java:301 可疑空指针",
                "[验证] 步骤 5\n调用了工具：run_tests\n观察：测试通过，BUILD SUCCESS");

        String context = assembler.assemble(Phase.LOCALIZE, "PullAPIWrapper 抛 NullPointerException",
                transcript, List.of(), List.of());

        assertTrue(context.contains("已复现"), context);
        assertTrue(context.contains("可疑空指针"), context);
        assertFalse(context.contains("BUILD SUCCESS"), context);
    }

    @Test
    void ranksRelevantSameTaskContextAndIncludesFailedRepairFeedback() {
        List<String> prior = List.of("另一个无关模块的缓存说明", "PullAPIWrapper 在空路由下抛 NullPointerException");
        List<String> failures = List.of("第 1 轮：方案「空判断」；测试范围 PullAPIWrapperTest；结果：仍抛 NullPointerException");

        String context = assembler.assemble(Phase.FIX, "PullAPIWrapper NullPointerException",
                List.of("[定位] 步骤 1\n阶段结论：根因是路由数据缺失"), prior, failures);

        assertTrue(context.indexOf("PullAPIWrapper 在空路由") < context.indexOf("另一个无关模块"), context);
        assertTrue(context.contains("仍抛 NullPointerException"), context);
    }

    @Test
    void capsEachEntryAndTheWholeContext() {
        String large = "[定位] 步骤 1\n" + "PullAPIWrapper evidence ".repeat(500);
        String context = assembler.assemble(Phase.LOCALIZE, "PullAPIWrapper",
                List.of(large, large, large, large, large, large), List.of(), List.of());

        assertTrue(context.length() <= PromptContextAssembler.MAX_CONTEXT_CHARS, Integer.toString(context.length()));
        assertTrue(context.contains("entry truncated"), context);
    }

    @Test
    void doesNotCarryContextBetweenSeparateTaskCalls() {
        String first = assembler.assemble(Phase.FIX, "bug A", List.of("[定位] 步骤 1\nsecret task A evidence"), List.of(), List.of());
        String second = assembler.assemble(Phase.REPRODUCE, "bug B", List.of(), List.of(), List.of());

        assertTrue(first.contains("secret task A evidence"));
        assertFalse(second.contains("secret task A evidence"));
    }

    @Test
    void keepsValidationFailureEvidenceWhenValidationReturnsToFix() {
        String validationFailure = "[验证] 步骤 9\n调用了工具：run_tests\n观察：测试未通过。Tests run: 3, Failures: 1\n"
                + "实际异常：org.example.DeepFailureException\n" + "trace-line ".repeat(100);

        String context = assembler.assemble(Phase.FIX, "PullAPIWrapper NullPointerException",
                List.of("[修复] 步骤 8\n阶段结论：添加了空值检查", validationFailure),
                List.of(), List.of("第 1 轮修复反馈"));

        assertTrue(context.contains("DeepFailureException"), context);
        assertTrue(context.contains("测试未通过"), context);
    }

    @Test
    void keepsPriorPhaseConclusionInTheActualTranscriptFormat() {
        String context = assembler.assemble(Phase.FIX, "unrelated symptom", List.of(
                        "[定位] 步骤 3\n阶段结论：root cause is missing route data\n观察：分析完毕",
                        "[修复] 步骤 4\n调用了工具：read_file\n观察：first repair action",
                        "[修复] 步骤 5\n调用了工具：search_code\n观察：second repair action"),
                List.of(), List.of());

        assertTrue(context.contains("root cause is missing route data"), context);
    }

    @Test
    void recentToolEvidenceAndFailureFeedbackSurviveHighlyRelevantOlderContext() {
        List<String> prior = java.util.stream.IntStream.range(0, 8)
                .mapToObj(i -> "OLD_TASK_CONTEXT PullAPIWrapper NullPointerException 空路由缺少类过滤信息 ".repeat(25) + i)
                .toList();
        List<String> transcript = new java.util.ArrayList<>(java.util.stream.IntStream.range(0, 20)
                .mapToObj(i -> "[复现] 步骤 " + i + "\n观察：PullAPIWrapper NullPointerException 空路由缺少类过滤信息 ".repeat(20))
                .toList());
        transcript.add("[定位] 步骤 7\n阶段结论：根因是空路由\n观察：分析完成");
        transcript.add("[修复] 步骤 8\n调用了工具：read_file\n观察：LATEST_TOOL_RESULT PullAPIWrapper.java:301");
        List<String> attempts = List.of("FAILED_FIX_RESULT PullAPIWrapperTest 仍抛 NullPointerException");

        String context = assembler.assemble(Phase.FIX,
                "PullAPIWrapper NullPointerException 空路由缺少类过滤信息", transcript, prior, attempts);

        assertTrue(context.contains("LATEST_TOOL_RESULT"), context);
        assertTrue(context.contains("FAILED_FIX_RESULT"), context);
        assertFalse(context.contains("OLD_TASK_CONTEXT"), context);
    }
}
