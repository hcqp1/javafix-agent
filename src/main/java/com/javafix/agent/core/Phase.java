package com.javafix.agent.core;

/**
 * Agent 工作流的四个阶段。
 *
 * <p>阶段存在的意义是给模型一条强制的工作顺序：先复现，再定位，然后才允许改代码。
 * 如果不把阶段写成代码里的显式状态，模型一定会走"直接改代码"这条最短路径，
 * 跳过复现和定位——这正是修 bug 最大的风险来源。
 */
public enum Phase {

    REPRODUCE(
            6,
            "复现",
            "你的唯一目标是先把用户报告的现象变成一条确定失败的测试：先跑现有测试，"
                    + "看看有没有已经失败的；没有就在 src/test 下写一条能复现现象的测试。"
                    + "注意：现有测试全绿是很常见的情况——那恰恰说明这个 bug 没被覆盖，"
                    + "你必须自己写一条；可以打开同一个测试目录下已有的测试，照着它的骨架来。"
                    + "禁止修改 src/main 下的任何文件（这条会被硬性拦截）。"
                    + "拿到一条失败测试后，用 FINAL 说明你复现了什么。"
    ),

    LOCALIZE(
            6,
            "定位",
            "目标是定位最可能的缺陷位置并给出证据，不要修改任何代码。"
                    + "把候选按可能性从高到低排序，用 FINAL 输出：涉及的文件与方法清单、"
                    + "根因判断、以及支撑证据（哪几行、哪个测试）。"
    ),

    FIX(
            5,
            "修复",
            "只改最小必要的地方。这个阶段的出口由代码判定：没有真正改动文件就不算完成，"
                    + "继续读代码、继续搜索都不算。"
                    + "改动现有文件用 replace_in_file（只替换你要改的那一小段，不需要重写整个文件）；"
                    + "新建文件才用 write_file。用 shell 改文件不被认可。"
                    + "改完后用 FINAL 说明改了什么、为什么。"
    ),

    VERIFY(
            5,
            "验证",
            "运行测试确认修复有效，并检查没有破坏其他东西。确认后输出 FINAL 给用户一个结论。"
    );

    private final String label;
    private final int stepBudget;
    private final String instruction;

    Phase(int stepBudget, String label, String instruction) {
        this.stepBudget = stepBudget;
        this.label = label;
        this.instruction = instruction;
    }

    public String label() {
        return label;
    }

    /**
     * 这个阶段最多允许花多少步。
     *
     * <p>预算存在的意义是逼收敛：只有总预算时，模型可以把全部步数耗在第一个阶段
     * （真实运行里发生过——16 步全花在复现的探索上）。分阶段限流之后，
     * 一个阶段磨太久就会被推着往前走，而不是把整条命都花在这一关。
     */
    public int stepBudget() {
        return stepBudget;
    }

    public String instruction() {
        return instruction;
    }

    /** 下一个阶段；已经是最后一个阶段时不再前进。 */
    public Phase next() {
        int index = ordinal() + 1;
        return index >= values().length ? this : values()[index];
    }
}
