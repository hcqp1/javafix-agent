package com.javafix.agent.tool;

import java.util.Map;

/**
 * 在仓库根目录执行 shell 命令，例如运行 Maven 测试。
 *
 * <p>V0 骨架：仅定义契约，尚未实现真实命令执行。
 * 后续实现时必须配套命令白名单与超时控制，当前阶段不做安全系统设计。
 */
public class ShellTool implements Tool {

    @Override
    public String name() {
        return "run_shell";
    }

    @Override
    public String description() {
        return "在仓库根目录执行 shell 命令并返回输出，例如 mvn test。参数：command。";
    }

    @Override
    public String execute(Map<String, String> arguments) {
        // TODO: V0 阶段实现——执行 arguments.get("command")，并捕获 stdout/stderr 与退出码
        throw new UnsupportedOperationException("ShellTool is not implemented yet");
    }
}
