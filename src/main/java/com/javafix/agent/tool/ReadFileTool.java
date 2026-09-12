package com.javafix.agent.tool;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        return "读取仓库中某个文件的内容。参数：path（相对仓库根目录的路径）。";
    }

    @Override
    public String execute(Map<String, String> arguments) {

        Path file = resolve(arguments.get("path"));

        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("not a file: " + arguments.get("path"));
        }

        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
