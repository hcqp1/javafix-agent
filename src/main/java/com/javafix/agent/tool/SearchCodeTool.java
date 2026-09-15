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
                + "参数：query（要搜索的内容）；可选参数 regex=true 表示把 query 当正则表达式；"
                + "可选参数 path 把搜索限制在某个目录或文件里（大仓库里务必用它，否则会返回大量无关命中）。";
    }

    @Override
    public String execute(Map<String, String> arguments) {

        rejectUnknownArguments(arguments, "query", "regex", "path");

        String query = arguments.get("query");

        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query is required");
        }

        boolean regex = "true".equalsIgnoreCase(arguments.get("regex"));
        Pattern pattern = Pattern.compile(regex ? query : Pattern.quote(query));

        List<String> matches = new ArrayList<>();
        boolean truncated = false;

        for (Path path : collectFiles(arguments.get("path"))) {

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

        if (matches.isEmpty()) {
            return "没有找到匹配 " + query + " 的内容";
        }

        String result = String.join("\n", matches);
        return truncated ? result + "\n（命中超过 " + MAX_MATCHES + " 条，已截断）" : result;
    }

    /**
     * 收集这次要搜索的文件。
     *
     * <p>支持把范围收窄到某个目录或单个文件。大仓库里这是必需的能力：不加限定地搜一个常见词，
     * 会返回几百条无关命中——对定位没有帮助，还会把上下文挤满。
     */
    private List<Path> collectFiles(String pathArgument) {

        List<Path> files = new ArrayList<>();
        Path start = pathArgument == null || pathArgument.isBlank() ? root : resolve(pathArgument);

        if (Files.isRegularFile(start)) {
            files.add(start);
            return files;
        }

        try (Stream<Path> paths = Files.walk(start)) {
            paths.filter(Files::isRegularFile).forEach(files::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return files;
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
