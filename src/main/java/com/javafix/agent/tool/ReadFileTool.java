package com.javafix.agent.tool;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 读取仓库中指定路径的文件内容。
 *
 * <p>返回原始内容，不带行号——后续生成 patch 要按原文比对，
 * 带行号反而容易让模型把行号也写进文件里。
 */
public class ReadFileTool extends ProjectTool {

    public ReadFileTool(Path root) {
        super(root);
    }

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "读取仓库中某个文件的内容。参数：path（相对仓库根目录的路径）；"
                + "可选参数 start、end 指定行号区间（从 1 开始、含两端），"
                + "大文件请分段读，一次读太多会被截断。";
    }

    @Override
    public String execute(Map<String, String> arguments) {

        rejectUnknownArguments(arguments, "path", "start", "end");

        Path file = resolve(arguments.get("path"));

        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("not a file: " + arguments.get("path"));
        }

        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);

            int start = lineNumber(arguments.get("start"), 1);
            int end = lineNumber(arguments.get("end"), lines.size());

            if (start < 1 || end > lines.size() || start > end) {
                throw new IllegalArgumentException(
                        "行号超出范围：" + relative(file) + " 一共 " + lines.size()
                                + " 行，请求的是 " + start + "-" + end);
            }

            if (start == 1 && end == lines.size()) {
                return String.join("\n", lines);
            }

            return "（" + relative(file) + " 第 " + start + "-" + end + " 行，共 " + lines.size() + " 行）\n"
                    + String.join("\n", lines.subList(start - 1, end));

        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int lineNumber(String value, int fallback) {

        if (value == null || value.isBlank()) {
            return fallback;
        }

        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("行号必须是数字：" + value);
        }
    }
}
