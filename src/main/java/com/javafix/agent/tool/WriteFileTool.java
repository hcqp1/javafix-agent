package com.javafix.agent.tool;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 用给定内容整体覆盖仓库中的某个文件，父目录不存在就创建。
 *
 * <p>V0 只能整文件写入。代价是模型必须把整个文件重写一遍，长文件既费 token 又容易抄错；
 * 更合适的是基于「查找—替换」的局部修改，那属于后续迭代。
 */
public class WriteFileTool extends ProjectTool {

    public WriteFileTool(Path root) {
        super(root);
    }

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public String description() {
        return "用给定内容覆盖仓库中的某个文件（整文件写入）。"
                + "参数：path（相对仓库根目录的路径）、content（新的文件内容）。";
    }

    @Override
    public String execute(Map<String, String> arguments) {

        Path file = resolve(arguments.get("path"));
        String content = arguments.get("content");

        if (content == null) {
            throw new IllegalArgumentException("content is required");
        }

        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return "已写入 " + relative(file) + "（" + content.lines().count() + " 行）";
    }
}
