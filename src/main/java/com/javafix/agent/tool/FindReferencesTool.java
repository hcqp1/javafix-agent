package com.javafix.agent.tool;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded JavaParser lookup for a method declaration and syntactically identifiable call sites. */
public final class FindReferencesTool extends ProjectTool {

    private static final int DEFAULT_RESULTS = 20;
    private static final int MAX_RESULTS = 30;
    private static final int MAX_FILES = 3000;
    private static final long MAX_SOURCE_BYTES = 1024 * 1024;
    private static final int MAX_OUTPUT_CHARS = 5000;
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(
            ".git", ".idea", "target", "build", "out", "node_modules", "generated-sources", "generated-test-sources");

    private record SourceScan(List<Path> files, boolean truncated) { }

    public FindReferencesTool(Path root) {
        super(root);
    }

    @Override
    public String name() {
        return "find_references";
    }

    @Override
    public String description() {
        return "用 Java AST 查找指定类型和方法的声明，以及类型可识别的调用候选。参数：type（限定类名或简单类名）、"
                + "method；可选 path（仓库相对 Java 文件/目录）、max_results（1-30，默认 20）。"
                + "JavaParser 不做完整类型求解；若没有命中或调用接收者无法识别，用 search_code 回退。";
    }

    @Override
    public String execute(Map<String, String> arguments) {
        rejectUnknownArguments(arguments, "type", "method", "path", "max_results");
        String targetType = required(arguments, "type");
        String targetMethod = required(arguments, "method");
        int resultLimit = parseResultLimit(arguments.get("max_results"));
        Path start = arguments.containsKey("path") ? resolve(arguments.get("path")) : root;
        if (!Files.exists(start, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("path does not exist: " + arguments.get("path"));
        }
        if (!Files.isRegularFile(start) && !Files.isDirectory(start)) {
            throw new IllegalArgumentException("path must be a Java file or directory");
        }
        if (Files.isRegularFile(start) && !start.getFileName().toString().endsWith(".java")) {
            throw new IllegalArgumentException("path must point to a .java file");
        }

        SourceScan sourceScan = collectSources(start);
        List<Path> sourceFiles = sourceScan.files();
        List<String> matches = new ArrayList<>();
        List<String> parseErrors = new ArrayList<>();
        Set<String> matchingTypes = new LinkedHashSet<>();
        int unresolvedCalls = 0;
        int scannedFiles = 0;
        int skippedLargeFiles = 0;
        JavaParser parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));

        for (Path file : sourceFiles) {
            try {
                if (!file.toRealPath().startsWith(root.toRealPath())) {
                    continue;
                }
                if (Files.size(file) > MAX_SOURCE_BYTES) {
                    skippedLargeFiles++;
                    continue;
                }
                scannedFiles++;
                var parsed = parser.parse(Files.readString(file, StandardCharsets.UTF_8));
                if (parsed.getResult().isEmpty() || !parsed.isSuccessful()) {
                    if (parseErrors.size() < 3) {
                        parseErrors.add(relative(file) + " (" + parsed.getProblems().size() + " problem(s))");
                    }
                    continue;
                }
                CompilationUnit unit = parsed.getResult().orElseThrow();
                Map<String, String> variableTypes = variableTypes(unit);
                List<TypeDeclaration<?>> types = new ArrayList<>();
                for (Node node : unit.findAll(Node.class)) {
                    if (node instanceof TypeDeclaration<?> type && matchesType(type, targetType, unit)) types.add(type);
                }
                for (TypeDeclaration<?> type : types) {
                    String qualifiedName = qualifiedName(type, unit);
                    matchingTypes.add(qualifiedName);
                    type.getMethodsByName(targetMethod).forEach(method -> addMatch(matches,
                            "DECLARATION " + qualifiedName + "#" + targetMethod,
                            file, method.getBegin().map(position -> position.line).orElse(1), resultLimit));
                }

                for (MethodCallExpr call : unit.findAll(MethodCallExpr.class)) {
                    if (!call.getNameAsString().equals(targetMethod)) continue;
                    if (matchesCall(call, targetType, unit, variableTypes)) {
                        String qualifiedName = matchedOwnerName(call, targetType, unit);
                        if (qualifiedName == null) qualifiedName = targetType;
                        addMatch(matches, "REFERENCE " + qualifiedName + "#" + targetMethod + " call",
                                file, call.getBegin().map(position -> position.line).orElse(1), resultLimit);
                    } else if (call.getScope().isPresent()) {
                        unresolvedCalls++;
                    }
                }
                for (MethodReferenceExpr reference : unit.findAll(MethodReferenceExpr.class)) {
                    if (!reference.getIdentifier().equals(targetMethod)) continue;
                    if (matchesReceiver(reference.getScope().toString(), null, targetType, unit, variableTypes)) {
                        addMatch(matches, "REFERENCE " + targetType + "#" + targetMethod + " method-reference",
                                file, reference.getBegin().map(position -> position.line).orElse(1), resultLimit);
                    } else {
                        unresolvedCalls++;
                    }
                }
                unit.getImports().stream().filter(importDeclaration -> importDeclaration.isStatic()
                                && !importDeclaration.isAsterisk()
                                && matchesStaticImport(importDeclaration.getNameAsString(), targetType, targetMethod))
                        .forEach(importDeclaration -> addMatch(matches,
                                "REFERENCE " + targetType + "#" + targetMethod + " static-import",
                                file, importDeclaration.getBegin().map(position -> position.line).orElse(1), resultLimit));
            } catch (IOException error) {
                if (parseErrors.size() < 3) parseErrors.add(relative(file) + " (read error: " + error.getMessage() + ")");
            }
            if (matches.size() >= resultLimit) break;
        }

        StringBuilder output = new StringBuilder();
        output.append("Scanned ").append(scannedFiles).append(" Java file(s); found ")
                .append(matches.size()).append(" result(s) for ").append(targetType).append('#').append(targetMethod).append(".\n");
        matches.forEach(match -> output.append(match).append('\n'));
        if (matchingTypes.size() > 1) {
            output.append("Ambiguous simple type name; matches: ").append(String.join(", ", matchingTypes)).append('\n');
        }
        if (!parseErrors.isEmpty()) {
            output.append("Parse errors/read errors (partial results retained): ").append(String.join("; ", parseErrors)).append('\n');
        }
        if (skippedLargeFiles > 0) output.append("Skipped ").append(skippedLargeFiles).append(" source file(s) over 1 MiB.\n");
        if (sourceScan.truncated()) output.append("File scan capped at ").append(MAX_FILES).append(" Java files.\n");
        if (unresolvedCalls > 0) {
            output.append(unresolvedCalls).append(" same-name reference candidate(s) had unresolved receiver types; use search_code as fallback.\n");
        }
        if (matches.size() >= resultLimit) output.append("Results truncated at ").append(resultLimit).append(".\n");
        if (matches.isEmpty() && parseErrors.isEmpty()) output.append("No matching declaration or resolvable reference. Try search_code.");
        if (output.length() > MAX_OUTPUT_CHARS) return output.substring(0, MAX_OUTPUT_CHARS) + "\n(output truncated)";
        return output.toString().stripTrailing();
    }

    private SourceScan collectSources(Path start) {
        List<Path> sources = new ArrayList<>();
        if (Files.isRegularFile(start)) return new SourceScan(List.of(start), false);
        try {
            Files.walkFileTree(start, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                    if (!directory.equals(start) && SKIPPED_DIRECTORIES.contains(directory.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && file.getFileName().toString().endsWith(".java")) sources.add(file);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException error) {
            throw new IllegalArgumentException("cannot scan Java source files: " + error.getMessage(), error);
        }
        sources.sort(Comparator.comparing(this::relativeUnix));
        boolean truncated = sources.size() > MAX_FILES;
        return new SourceScan(truncated ? List.copyOf(sources.subList(0, MAX_FILES)) : sources, truncated);
    }

    private static Map<String, String> variableTypes(CompilationUnit unit) {
        Map<String, String> types = new HashMap<>();
        unit.findAll(VariableDeclarator.class).forEach(variable ->
                types.putIfAbsent(variable.getNameAsString(), normalizeType(variable.getTypeAsString())));
        unit.findAll(Parameter.class).forEach(parameter ->
                types.putIfAbsent(parameter.getNameAsString(), normalizeType(parameter.getTypeAsString())));
        return types;
    }

    private static String normalizeType(String raw) {
        return raw.replaceAll("<.*>", "").replace("[]", "").strip();
    }

    private static boolean matchesType(TypeDeclaration<?> type, String target, CompilationUnit unit) {
        return target.equals(qualifiedName(type, unit)) || target.equals(type.getNameAsString());
    }

    private static boolean matchesCall(MethodCallExpr call, String target, CompilationUnit unit,
                                       Map<String, String> variableTypes) {
        if (call.getScope().isEmpty()) {
            return matchedOwnerName(call, target, unit) != null;
        }
        return matchesReceiver(call.getScope().orElseThrow().toString(), call, target, unit, variableTypes);
    }

    private static boolean matchesReceiver(String receiver, Node call, String target, CompilationUnit unit,
                                           Map<String, String> variableTypes) {
        String simpleTarget = simpleName(target);
        if ((receiver.equals("this") || receiver.equals("super")) && call != null) {
            return matchedOwnerName(call, target, unit) != null;
        }
        String lastPart = receiver.substring(receiver.lastIndexOf('.') + 1);
        String variableType = variableTypes.get(lastPart);
        if (variableType != null && typeMatchesQuery(resolveTypeName(variableType, unit), target)) return true;
        return typeMatchesQuery(resolveTypeName(receiver, unit), target);
    }

    private static String resolveTypeName(String name, CompilationUnit unit) {
        String packageName = unit.getPackageDeclaration().map(declaration -> declaration.getNameAsString()).orElse("");
        if (name.contains(".")) {
            if (!packageName.isEmpty() && name.startsWith(packageName + ".")) return name;
            String firstPart = name.substring(0, name.indexOf('.'));
            if (!packageName.isEmpty() && !firstPart.isEmpty() && Character.isUpperCase(firstPart.charAt(0))) {
                return packageName + "." + name;
            }
            return name;
        }
        for (var imported : unit.getImports()) {
            if (!imported.isStatic() && !imported.isAsterisk()
                    && simpleName(imported.getNameAsString()).equals(name)) {
                return imported.getNameAsString();
            }
        }
        return packageName.isEmpty() ? name : packageName + "." + name;
    }

    private static boolean typeMatchesQuery(String resolvedType, String query) {
        return query.contains(".") ? resolvedType.equals(query) : simpleName(resolvedType).equals(query);
    }

    private static String matchedOwnerName(Node node, String target, CompilationUnit unit) {
        for (Node current = node; current != null; current = current.getParentNode().orElse(null)) {
            if (current instanceof TypeDeclaration<?> owner && matchesType(owner, target, unit)) {
                return qualifiedName(owner, unit);
            }
        }
        return null;
    }

    private static String qualifiedName(TypeDeclaration<?> type, CompilationUnit unit) {
        List<String> names = new ArrayList<>();
        for (Node current = type; current != null; current = current.getParentNode().orElse(null)) {
            if (current instanceof TypeDeclaration<?> declaration) names.add(declaration.getNameAsString());
        }
        java.util.Collections.reverse(names);
        String name = String.join(".", names);
        return unit.getPackageDeclaration().map(packageDeclaration -> packageDeclaration.getNameAsString() + "." + name)
                .orElse(name);
    }

    private static String simpleName(String name) {
        int index = name.lastIndexOf('.');
        return index < 0 ? name : name.substring(index + 1);
    }

    private static boolean matchesStaticImport(String importedName, String targetType, String targetMethod) {
        String suffix = "." + targetMethod;
        if (!importedName.endsWith(suffix)) return false;
        String importedType = importedName.substring(0, importedName.length() - suffix.length());
        return importedType.equals(targetType) || simpleName(importedType).equals(simpleName(targetType));
    }

    private void addMatch(List<String> matches, String description, Path file, int line, int limit) {
        if (matches.size() < limit) matches.add(description + " (" + relativeUnix(file) + ":" + line + ")");
    }

    private String relativeUnix(Path path) {
        return relative(path).replace('\\', '/');
    }

    private static String required(Map<String, String> arguments, String name) {
        String value = arguments.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.strip();
    }

    private static int parseResultLimit(String value) {
        if (value == null || value.isBlank()) return DEFAULT_RESULTS;
        try {
            int limit = Integer.parseInt(value);
            if (limit < 1 || limit > MAX_RESULTS) throw new IllegalArgumentException("max_results must be between 1 and 30");
            return limit;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("max_results must be an integer between 1 and 30", error);
        }
    }
}
