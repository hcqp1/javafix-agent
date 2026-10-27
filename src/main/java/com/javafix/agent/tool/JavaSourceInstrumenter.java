package com.javafix.agent.tool;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Position;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.TypePatternExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.TypeParameter;

import javax.lang.model.SourceVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Adds probes at AST statement boundaries without rewriting any original source. */
public final class JavaSourceInstrumenter {
    public record Result(String source, int eventCount, List<String> skippedProbes) {
        public Result {
            skippedProbes = List.copyOf(skippedProbes);
        }
    }

    public static final class InstrumentationException extends Exception {
        public InstrumentationException(String message) {
            super(message);
        }
    }

    public Result instrument(Path sourceFile, String className, String methodName, String helperClassName)
            throws IOException, InstrumentationException {
        for (Path component : sourceFile.toAbsolutePath().normalize()) {
            String name = component.toString().toLowerCase(Locale.ROOT);
            if (name.contains("generated-sources") || name.contains("generated-test-sources")) {
                throw new InstrumentationException("Generated source is unsupported: " + sourceFile);
            }
        }
        if (helperClassName == null || !SourceVersion.isName(helperClassName, SourceVersion.RELEASE_17)) {
            throw new InstrumentationException("Invalid helper class name: " + helperClassName);
        }
        byte[] sourceBytes;
        try (var input = Files.newInputStream(sourceFile)) { sourceBytes = input.readNBytes(1024 * 1024 + 1); }
        if (sourceBytes.length > 1024 * 1024) throw new InstrumentationException("Java source size limit exceeded (1 MiB)");
        String source = java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(sourceBytes)).toString();
        var parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17).setTabSize(1));
        var parsed = parser.parse(source);
        if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
            throw new InstrumentationException("Cannot parse " + sourceFile + " as Java 17: " + parsed.getProblems());
        }
        var types = parsed.getResult().orElseThrow().stream()
                .filter(node -> node instanceof TypeDeclaration<?>)
                .map(node -> (TypeDeclaration<?>) node)
                .filter(type -> type.getNameAsString().equals(className)
                        || type.getFullyQualifiedName().filter(name -> name.equals(className)).isPresent())
                .toList();
        if (types.size() != 1) {
            throw new InstrumentationException((types.isEmpty() ? "Class not found: " : "Ambiguous class: ") + className);
        }
        TypeDeclaration<?> type = types.get(0);
        var methods = type.getMethodsByName(methodName);
        if (methods.size() != 1) {
            throw new InstrumentationException((methods.isEmpty() ? "Method not found: " : "Ambiguous method: ")
                    + className + "." + methodName);
        }
        MethodDeclaration method = methods.get(0);
        for (Node node = method; node != null; node = node.getParentNode().orElse(null)) {
            if (node instanceof NodeWithAnnotations<?> annotated && annotated.getAnnotations().stream()
                    .anyMatch(annotation -> annotation.getName().getIdentifier().equals("Generated"))) {
                throw new InstrumentationException("Generated source is unsupported: " + className + "." + methodName);
            }
        }
        BlockStmt body = method.getBody().orElseThrow(() ->
                new InstrumentationException("Selected method has no body: " + className + "." + methodName));
        validateHelperReference(method, helperClassName);
        List<Insertion> insertions = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        addProbe(insertions, source, helperClassName, "METHOD_ENTRY", line(method), "", "null",
                body.getBegin().orElseThrow(), '{');
        for (ExpressionStmt statement : body.findAll(ExpressionStmt.class)) {
            if (!belongsToMethod(statement, method) || !(statement.getParentNode().orElse(null) instanceof BlockStmt)) {
                continue;
            }
            if (statement.getExpression() instanceof VariableDeclarationExpr declaration) {
                for (var variable : declaration.getVariables()) {
                    if (variable.getInitializer().isPresent()) {
                        String name = variable.getNameAsString();
                        addProbe(insertions, source, helperClassName, "LOCAL_DECLARATION", line(variable), name, name,
                                statement.getEnd().orElseThrow(), ';');
                    }
                }
            } else if (statement.getExpression() instanceof AssignExpr assignment
                    && assignment.getTarget().isNameExpr()) {
                String name = assignment.getTarget().asNameExpr().getNameAsString();
                if (isLocalOrParameter(statement, method, name)) {
                    addProbe(insertions, source, helperClassName, "ASSIGNMENT", line(statement), name, name,
                            statement.getEnd().orElseThrow(), ';');
                }
            }
        }
        for (IfStmt branch : body.findAll(IfStmt.class)) {
            if (!belongsToMethod(branch, method)) {
                continue;
            }
            addBranch(insertions, skipped, source, helperClassName, branch.getThenStmt(), "BRANCH_THEN");
            if (branch.getElseStmt().isPresent()) {
                addBranch(insertions, skipped, source, helperClassName, branch.getElseStmt().orElseThrow(), "BRANCH_ELSE");
            }
        }
        // Insert backwards at original AST offsets; every original byte outside probes remains intact.
        StringBuilder transformed = new StringBuilder(source);
        insertions.sort(Comparator.comparingInt(Insertion::offset).reversed()
                .thenComparing(Comparator.comparingInt(Insertion::order).reversed()));
        for (Insertion insertion : insertions) {
            transformed.insert(insertion.offset(), insertion.text());
        }
        return new Result(transformed.toString(), insertions.size(), skipped);
    }

    private static void validateHelperReference(MethodDeclaration method, String helper)
            throws InstrumentationException {
        String leadingName = helper.split("\\.", 2)[0];
        for (Node enclosing = method; enclosing != null; enclosing = enclosing.getParentNode().orElse(null)) {
            if (!(enclosing instanceof TypeDeclaration<?>) && !(enclosing instanceof CompilationUnit)) {
                // Fail closed across nested lexical scopes, including captured locals of local classes.
                for (Node node : enclosing.stream().toList()) {
                    checkHelperConflict(node, leadingName);
                }
            } else if (enclosing instanceof TypeDeclaration<?> type) {
                checkHelperConflict(type, leadingName);
                for (Node member : type.getChildNodes()) {
                    checkHelperConflict(member, leadingName);
                }
                for (var field : type.getFields()) {
                    for (var variable : field.getVariables()) {
                        checkHelperConflict(variable, leadingName);
                    }
                }
            }
        }
        var unit = method.findCompilationUnit().orElseThrow();
        for (var type : unit.getTypes()) {
            checkHelperConflict(type, leadingName);
        }
        for (var imported : unit.getImports()) {
            if (!imported.isAsterisk() && imported.getName().getIdentifier().equals(leadingName)) {
                throw new InstrumentationException("Helper name conflict: " + leadingName + " is imported at line " + line(imported));
            }
        }
    }

    private static void checkHelperConflict(Node node, String leadingName) throws InstrumentationException {
        if ((node instanceof VariableDeclarator || node instanceof Parameter || node instanceof TypeParameter
                || node instanceof TypeDeclaration<?> || node instanceof EnumConstantDeclaration || node instanceof TypePatternExpr)
                && node instanceof NodeWithSimpleName<?> named && named.getNameAsString().equals(leadingName)) {
            throw new InstrumentationException("Helper name conflict: " + leadingName + " is declared at line "
                    + line(node) + "; choose a different generated helper name");
        }
    }

    private static boolean belongsToMethod(Node node, MethodDeclaration method) {
        for (Node parent = node.getParentNode().orElse(null); parent != null; parent = parent.getParentNode().orElse(null)) {
            if (parent == method) {
                return true;
            }
            if (parent instanceof Expression || parent instanceof TypeDeclaration<?>) {
                return false;
            }
        }
        return false;
    }

    private static boolean isLocalOrParameter(ExpressionStmt statement, MethodDeclaration method, String name) {
        if (method.getParameters().stream().anyMatch(parameter -> parameter.getNameAsString().equals(name))) {
            return true;
        }
        Node child = statement;
        for (Node parent = child.getParentNode().orElse(null); parent != method;
             child = parent, parent = parent.getParentNode().orElseThrow()) {
            if (parent instanceof BlockStmt block) {
                for (Statement sibling : block.getStatements()) {
                    if (sibling == child) {
                        break;
                    }
                    if (sibling instanceof ExpressionStmt expression
                            && expression.getExpression() instanceof VariableDeclarationExpr declaration
                            && declaration.getVariables().stream().anyMatch(variable -> variable.getNameAsString().equals(name))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void addBranch(List<Insertion> insertions, List<String> skipped, String source,
                                  String helper, Statement statement, String kind) throws InstrumentationException {
        if (statement instanceof BlockStmt) {
            addProbe(insertions, source, helper, kind, line(statement), "", "null", statement.getBegin().orElseThrow(), '{');
        } else {
            skipped.add("Skipped " + kind + " probe at line " + line(statement) + ": single-statement branch body");
        }
    }

    private static void addProbe(List<Insertion> insertions, String source, String helper, String kind,
                                 int line, String name, String value, Position position, char boundary)
            throws InstrumentationException {
        int offset = offset(source, position);
        if (offset >= source.length() || source.charAt(offset) != boundary) {
            throw new InstrumentationException("Unsupported source mapping at " + position);
        }
        String newline = source.contains("\r\n") ? "\r\n" : "\n";
        String probe = newline + helper + ".record(\"" + kind + "\", " + line + ", \"" + name + "\", " + value + ");" + newline;
        insertions.add(new Insertion(offset + 1, probe, insertions.size()));
    }

    private static int offset(String source, Position position) throws InstrumentationException {
        int offset = 0;
        int line = 1;
        while (line < position.line && offset < source.length()) {
            char character = source.charAt(offset++);
            if (character == '\r') {
                if (offset < source.length() && source.charAt(offset) == '\n') {
                    offset++;
                }
                line++;
            } else if (character == '\n') {
                line++;
            }
        }
        if (line != position.line || offset + position.column - 1 >= source.length()) {
            throw new InstrumentationException("Unsupported source position: " + position);
        }
        return offset + position.column - 1;
    }

    private static int line(Node node) {
        return node.getBegin().orElseThrow().line;
    }

    private record Insertion(int offset, String text, int order) {}
}
