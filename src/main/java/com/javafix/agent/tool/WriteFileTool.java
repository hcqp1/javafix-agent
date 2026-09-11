package com.javafix.agent.tool;

import java.util.Map;

/**
 * 将内容写入仓库中的指定文件。
 *
 * <p>V0 骨架：仅定义契约，尚未实现真实写入逻辑。
 * 当前阶段严禁真正修改目标 Repository，因此 {@link #execute(Map)} 直接抛出异常。
 */
public class WriteFileTool implements Tool {

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public String description() {
        return "将内容写入指定路径的文件，用于修复缺陷。参数：path、content。";
    }

    @Override
    public String execute(Map<String, String> arguments) {
        // TODO: V0 阶段实现——把 arguments.get("content") 写入 arguments.get("path")
        //       实现时必须加入路径白名单校验，禁止越出目标仓库根目录
        throw new UnsupportedOperationException("WriteFileTool is not implemented yet");
    }
}
