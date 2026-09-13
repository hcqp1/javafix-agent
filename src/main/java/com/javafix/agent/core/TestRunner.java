package com.javafix.agent.core;

import java.nio.file.Path;

public interface TestRunner {

    TestResult run(Path projectPath);

    /**
     * 只跑指定测试类的重载。
     *
     * <p>真实仓库跑一次全量测试要几十分钟，Agent 必须能自己把范围收窄到它正在验证的那一个
     * 测试类——这是「先把现象复现成一条失败测试」这个阶段能成立的前提。
     */
    default TestResult run(Path projectPath, String testSelector) {
        return run(projectPath);
    }

}
