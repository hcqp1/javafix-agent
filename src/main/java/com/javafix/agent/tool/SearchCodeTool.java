package com.javafix.agent.tool;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 在仓库中按关键字或正则表达式搜索代码，返回命中的文件与行号。
 *
 * <p>两个刻意的限制：
 *
 * <ul>
 *   <li>跳过 {@code .git} / {@code target} 这些目录——构建产物里的命中基本都是噪音，
 *       而且会让命中数爆掉。</li>
 *   <li>命中数设上限。搜索结果最终会进模型上下文，不设上限的话，
 *       搜一个常见词就能把上下文窗口吃满。</li>
 * </ul>
 */
public class SearchCodeTool extends ProjectTool {

    /** 单次搜索最多返回多少条命中。 */
    private static final int MAX_MATCHES = 200;

    private static final Set<String> SKIPPED_DIRECTORIES =
            Set.of(".git", ".idea", "target", "build", "out", "node_modules");

    public SearchCodeTool(Path root) {
        super(root);
    }

    @Override
    public String name() {
        return "search_code";
    }

    @Override
    public String description() {
        return "在仓库中按关键字或正则搜索代码，返回命中的文件与行号。"
                + "参数：query（要搜索的内容）；可选参数 regex=true 表示把 query 当正则表达式。";
    }

    @Override
    public String execute(Map<String, String> arguments) {

        String query = arguments.get("query");

        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query is required");
        }

        boolean regex = "true".equalsIgnoreCase(arguments.get("regex"));
        Pattern pattern = Pattern.compile(regex ? query : Pattern.quote(query));

        List<String> matches = new ArrayList<>();
        boolean truncated = false;

        try (Stream<Path> paths = Files.walk(root)) {

            for (Path path : paths.filter(Files::isRegularFile).toList()) {

                if (isSkipped(path)) {
                    continue;
                }

                for (String match : searchFile(path, pattern)) {
                    if (matches.size() >= MAX_MATCHES) {
                        truncated = true;
                        break;
                    }
                    matches.add(match);
                }

                if (truncated) {
                    break;
                }
            }

        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        if (matches.isEmpty()) {
            return "没有找到匹配 " + query + " 的内容";
        }

        String result = String.join("\n", matches);
        return truncated ? result + "\n（命中超过 " + MAX_MATCHES + " 条，已截断）" : result;
    }

    private List<String> searchFile(Path file, Pattern pattern) {

        List<String> matches = new ArrayList<>();

        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);

            for (int i = 0; i < lines.size(); i++) {
                if (pattern.matcher(lines.get(i)).find()) {
                    matches.add(relative(file) + ":" + (i + 1) + ": " + lines.get(i).strip());
                }
            }

        } catch (MalformedInputException e) {
            // 二进制文件，直接跳过
            return matches;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return matches;
    }

    private boolean isSkipped(Path file) {
        for (Path segment : root.relativize(file)) {
            if (SKIPPED_DIRECTORIES.contains(segment.toString())) {
                return true;
            }
        }
        return false;
    }
}
