package com.javafix.agent.tool;

import java.nio.file.Path;

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
}
