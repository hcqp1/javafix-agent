package com.javafix.agent.tool;

import java.util.Map;

/**
 * 在仓库中按关键字或正则表达式搜索代码。
 *
 * <p>V0 骨架：仅定义契约，尚未实现真实搜索逻辑。
 */
public class SearchCodeTool implements Tool {

    @Override
    public String name() {
        return "search_code";
    }

    @Override
    public String description() {
        return "在代码仓库中按关键字或正则搜索代码，返回命中的文件与行号。参数：query。";
    }

    @Override
    public String execute(Map<String, String> arguments) {
        // TODO: V0 阶段实现——按 arguments.get("query") 搜索仓库代码
        throw new UnsupportedOperationException("SearchCodeTool is not implemented yet");
    }
}
