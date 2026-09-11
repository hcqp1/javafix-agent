package com.javafix.agent.tool;

import java.util.Map;

/**
 * 读取仓库中指定路径的文件内容。
 *
 * <p>V0 骨架：仅定义契约，尚未实现真实读取逻辑。
 */
public class ReadFileTool implements Tool {

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "读取指定路径的文本文件内容。参数：path。";
    }

    @Override
    public String execute(Map<String, String> arguments) {
        // TODO: V0 阶段实现——读取 arguments.get("path") 指向的文件内容
        throw new UnsupportedOperationException("ReadFileTool is not implemented yet");
    }
}
