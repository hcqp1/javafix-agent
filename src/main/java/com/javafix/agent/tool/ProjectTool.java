package com.javafix.agent.tool;

import java.nio.file.Path;
import java.util.Map;

/**
 * 作用于某个代码仓库的工具的公共基类：绑定仓库根目录，并把相对路径安全地解析出来。
 *
 * <p>工具参数是模型给的，不能无条件信任：所有路径都必须落在仓库根目录之内，
 * 否则一个写错（或者被诱导）的 {@code ../} 就能让 Agent 改到仓库外面去。
 */
abstract class ProjectTool implements Tool {

    protected final Path root;

    protected ProjectTool(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /**
     * 把模型给的相对路径解析成绝对路径。
     *
     * @throws IllegalArgumentException 路径为空，或者解析之后跑到仓库外面去了
     */
    protected Path resolve(String path) {

        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }

        Path resolved = root.resolve(path).normalize();

        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("path escapes project root: " + path);
        }

        return resolved;
    }

    /** 反过来的操作：把绝对路径变回仓库内的相对路径，用于给模型一个可读的反馈。 */
    protected String relative(Path path) {
        return root.relativize(path).toString();
    }

    /**
     * 拒绝不认识的参数。
     *
     * <p>静默忽略参数是所有接口设计里最坏的一种：模型传了一个我们没实现的名字，
     * 它拿不到任何反馈，只能一遍遍换着猜。真实运行里就吃过这个亏——{@code search_code}
     * 当时不支持 {@code path}，而模型一直想用它收窄搜索范围，七步就这么烧掉了。
     * 所以宁可当场报错，把"支持哪些参数"明明白白地说回去。
     */
    protected static void rejectUnknownArguments(Map<String, String> arguments, String... supported) {

        for (String name : arguments.keySet()) {

            boolean known = false;
            for (String candidate : supported) {
                if (candidate.equals(name)) {
                    known = true;
                    break;
                }
            }

            if (!known) {
                throw new IllegalArgumentException(
                        "不支持的参数：" + name + "；支持的参数是 " + String.join("、", supported)
                );
            }
        }
    }
}
