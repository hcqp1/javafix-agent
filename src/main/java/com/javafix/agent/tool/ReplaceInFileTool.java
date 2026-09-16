package com.javafix.agent.tool;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 把文件里的一段原文替换成新内容——局部修改，不需要重写整个文件。
 *
 * <p>为什么必须有这个工具：{@link WriteFileTool} 只能整文件覆盖，而真实仓库里的文件动辄几百行
 * （三万字符量级），观察结果又只回传前面一小段。模型于是陷入死循环：要写就得先读全，
 * 但永远读不全——真实运行里它连着两步都在说"需读全文件才能 write_file"，
 * 最后改用 shell 去改文件。局部替换才是改代码的正常方式。
 */
public class ReplaceInFileTool extends ProjectTool {

    public ReplaceInFileTool(Path root) {
        super(root);
    }

    @Override
    public String name() {
        return "replace_in_file";
    }

    @Override
    public String description() {
        return "把文件中一段确定的文本替换成新内容（局部修改，不需要重写整个文件）。"
                + "参数：path（相对仓库根目录的路径）、find（要被替换的原文，"
                + "必须与文件里的内容完全一致，包括缩进，并且要足够长以保证唯一）、"
                + "replace（替换成的新内容，可以是空字符串表示删除）。";
    }

    @Override
    public String execute(Map<String, String> arguments) {

        rejectUnknownArguments(arguments, "path", "find", "replace");

        Path file = resolve(arguments.get("path"));
        String find = arguments.get("find");
        String replace = arguments.get("replace");

        if (find == null || find.isEmpty()) {
            throw new IllegalArgumentException("find is required（要替换的原文）");
        }
        if (replace == null) {
            throw new IllegalArgumentException("replace is required（替换成什么，删除就传空字符串）");
        }

        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("not a file: " + arguments.get("path"));
        }

        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        int occurrences = countOccurrences(content, find);

        if (occurrences == 0) {
            throw new IllegalArgumentException(
                    "在 " + relative(file) + " 里找不到这段原文。请先用 read_file 确认它完全一致（包括缩进和换行）。");
        }
        if (occurrences > 1) {
            throw new IllegalArgumentException(
                    "这段原文在 " + relative(file) + " 里出现了 " + occurrences
                            + " 次，无法确定改哪一处。请把 find 带上更多上下文，让它唯一。");
        }

        try {
            Files.writeString(file, content.replace(find, replace), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return "已替换 " + relative(file) + " 中的 1 处内容";
    }

    private static int countOccurrences(String content, String find) {

        int count = 0;
        int index = content.indexOf(find);

        while (index >= 0) {
            count++;
            index = content.indexOf(find, index + find.length());
        }

        return count;
    }
}
