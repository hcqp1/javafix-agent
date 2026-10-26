package com.javafix.agent.tool;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class JavaSourceInstrumenterTest {
    @TempDir Path directory;

    @Test
    void instrumentsOnlySelectedMethodWithOriginalLinesAndOrderedProbes() throws Exception {
        String original;
        try (var resource = getClass().getResourceAsStream("/trace/TraceFixture.java")) {
            original = new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        Path source = write(original);
        var result = new JavaSourceInstrumenter().instrument(source, "TraceFixture", "selected", "TraceSink");
        assertEquals(7, result.eventCount());
        assertTrue(result.skippedProbes().isEmpty());
        assertEquals(original.substring(0, original.indexOf("static int selected")),
                result.source().substring(0, result.source().indexOf("static int selected")));
        assertEquals(original.substring(original.indexOf("    static boolean check")),
                result.source().substring(result.source().indexOf("    static boolean check")));
        assertTrue(result.source().contains("TraceSink.record(\"METHOD_ENTRY\", 5, \"\", null)"));
        assertTrue(result.source().contains("TraceSink.record(\"LOCAL_DECLARATION\", 6, \"value\", value)"));
        assertTrue(result.source().contains("TraceSink.record(\"ASSIGNMENT\", 7, \"value\", value)"));
        assertTrue(result.source().contains("TraceSink.record(\"BRANCH_THEN\", 8, \"\", null)"));
        assertTrue(result.source().contains("TraceSink.record(\"BRANCH_ELSE\", 10, \"\", null)"));
        assertTrue(result.source().indexOf("int value = 1;") < result.source().indexOf("\"LOCAL_DECLARATION\""));
        assertTrue(result.source().indexOf("value = value + 2;") < result.source().indexOf("\"ASSIGNMENT\""));
        assertEquals(1, occurrences(result.source(), "check(flag)"));
        assertEquals(original, Files.readString(source), "instrumenter must not write to input");
        compileAndRunFixture(result.source());
    }

    @Test
    void skipsEmbeddedAssignmentsFieldsAndUninitializedLocals() throws Exception {
        var result = instrument("class TraceFixture { int field; int selected() { int x; int y = 0; "
                + "if ((y = 3) > 0) { x = y; } else { x = 0; } field = 4; "
                + "this.field = 5; int[] a = {0}; a[0] = 6; return (y = 7); } }");
        assertFalse(result.source().contains("\"LOCAL_DECLARATION\", 1, \"x\""));
        assertFalse(result.source().contains("\"ASSIGNMENT\", 1, \"y\""));
        assertFalse(result.source().contains("\"ASSIGNMENT\", 1, \"field\""));
        assertEquals(1, occurrences(result.source(), "(y = 3)"));
        assertEquals(1, occurrences(result.source(), "return (y = 7)"));
        assertEquals(7, result.eventCount());
    }

    @Test
    void leavesLambdaAnonymousAndLocalClassBodiesUnchanged() throws Exception {
        String lambda = "() -> { int inside = 1; if (inside > 0) { inside = 2; } }";
        String anonymous = "new Runnable() { public void run() { int inside = 1; } }";
        String localClass = "class Local { void run() { int inside = 1; } }";
        var result = instrument("class TraceFixture { void selected() { Runnable r = " + lambda
                + "; Runnable s = " + anonymous + "; " + localClass + " } }");
        assertTrue(result.source().contains(lambda));
        assertTrue(result.source().contains(anonymous));
        assertTrue(result.source().contains(localClass));
        assertEquals(3, result.eventCount());
    }

    @Test
    void leavesStatementsInsideReturnAndInitializerExpressionsUnchanged() throws Exception {
        String expression = "switch (x) { default -> { int inner = 1; if (x > 0) { inner = 2; } yield inner; } }";
        var result = instrument("class TraceFixture { int selected(int x) { int y = " + expression
                + "; return " + expression + "; } }");
        assertEquals(2, result.eventCount(), "only method entry and the outer local declaration are safe boundaries");
        assertEquals(2, occurrences(result.source(), expression));
    }

    @Test
    void skipsSingleStatementBranchesAndDisclosesBoth() throws Exception {
        var result = instrument("class TraceFixture { int selected(int x) { if (x > 0) x = 1; else x = 2; return x; } }");
        assertEquals(1, result.eventCount());
        assertEquals(2, result.skippedProbes().size());
        assertTrue(result.skippedProbes().stream().allMatch(s -> s.contains("line 1")));
        assertTrue(result.source().contains("if (x > 0) x = 1; else x = 2;"));
    }

    @Test
    void recognizesParametersAndDoesNotMistakeOutOfScopeLocalsForFields() throws Exception {
        var result = instrument("class TraceFixture { int x; void selected(int p) { { int x = 1; } x = 2; p += 3; } }");
        assertEquals(3, result.eventCount());
        assertTrue(result.source().contains("\"ASSIGNMENT\", 1, \"p\", p"));
        assertFalse(result.source().contains("\"ASSIGNMENT\", 1, \"x\", x"));
    }

    @Test
    void rejectsOverloadedMethods() throws Exception {
        assertDiagnostic("class TraceFixture { void selected() {} void selected(int x) {} }", "Ambiguous method");
    }

    @Test
    void rejectsMissingAndAmbiguousClassesAndMissingMethods() throws Exception {
        assertDiagnostic("class Other {}", "Class not found");
        assertDiagnostic("class A { class TraceFixture {} } class B { class TraceFixture {} }", "Ambiguous class");
        assertDiagnostic("class TraceFixture { void other() {} }", "Method not found");
        assertDiagnostic("abstract class TraceFixture { abstract void selected(); }", "no body");
    }

    @Test
    void supportsQualifiedClassSelectionWithoutSelectingNestedMethods() throws Exception {
        Path source = write("package demo; class TraceFixture { class Nested { void selected() {} } void selected() {} }");
        var result = new JavaSourceInstrumenter().instrument(source, "demo.TraceFixture", "selected", "TraceSink");
        assertEquals(1, result.eventCount());
        assertTrue(result.source().contains("class Nested { void selected() {} }"));
    }

    @Test
    void rejectsInvalidSyntaxWithActionableParseMessage() throws Exception {
        assertDiagnostic("class TraceFixture { void selected( { }", "Cannot parse");
        assertDiagnostic("class TraceFixture { void selected() { String x = STR.\"hello\"; } }", "Cannot parse");
    }

    @Test
    void rejectsGeneratedPathsAndAnnotations() throws Exception {
        Path generated = directory.resolve("target/generated-sources/TraceFixture.java");
        Files.createDirectories(generated.getParent());
        Files.writeString(generated, "class TraceFixture { void selected() {} }");
        var exception = assertThrows(JavaSourceInstrumenter.InstrumentationException.class,
                () -> new JavaSourceInstrumenter().instrument(generated, "TraceFixture", "selected", "TraceSink"));
        assertTrue(exception.getMessage().contains("Generated source"));
        assertDiagnostic("@javax.annotation.processing.Generated(\"tool\") class TraceFixture { void selected() {} }", "Generated source");
        assertDiagnostic("class TraceFixture { @Generated void selected() {} }", "Generated source");
    }

    @Test
    void rejectsInvalidHelperNameRatherThanInjectingSource() throws Exception {
        Path source = write("class TraceFixture { void selected() {} }");
        assertThrows(JavaSourceInstrumenter.InstrumentationException.class,
                () -> new JavaSourceInstrumenter().instrument(source, "TraceFixture", "selected", "Sink); evil("));
    }

    @Test
    void preservesCrLfAndUnicodeSourcePositions() throws Exception {
        String original = "// 文本\r\nclass TraceFixture {\r\n\tvoid selected() { String 文本 = \"😊\"; 文本 = \"ok\"; }\r\n}\r\n";
        var result = instrument(original);
        assertTrue(result.source().startsWith("// 文本\r\nclass TraceFixture {\r\n"));
        assertTrue(result.source().endsWith("}\r\n"));
        assertEquals(3, result.eventCount());
        assertTrue(result.source().contains("文本 = \"ok\";"));
    }

    @Test
    void preservesMultipleDeclarationProbeOrderAndOriginalExpressions() throws Exception {
        var result = instrument("class TraceFixture { void selected() { int a = 1, b = a++; } }");
        assertEquals(3, result.eventCount());
        assertTrue(result.source().contains("int a = 1, b = a++;"));
        assertTrue(result.source().indexOf("\"a\", a") < result.source().indexOf("\"b\", b"));
        assertEquals(1, occurrences(result.source(), "a++"));
    }

    private JavaSourceInstrumenter.Result instrument(String source) throws Exception {
        var result = new JavaSourceInstrumenter().instrument(write(source), "TraceFixture", "selected", "TraceSink");
        var parsed = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)).parse(result.source());
        assertTrue(parsed.isSuccessful(), parsed.getProblems().toString());
        return result;
    }

    private void assertDiagnostic(String source, String message) throws Exception {
        Path path = write(source);
        var failure = assertThrows(JavaSourceInstrumenter.InstrumentationException.class,
                () -> new JavaSourceInstrumenter().instrument(path, "TraceFixture", "selected", "TraceSink"));
        assertTrue(failure.getMessage().contains(message), failure.getMessage());
    }

    private Path write(String source) throws Exception {
        Path path = directory.resolve("TraceFixture.java");
        Files.writeString(path, source);
        return path;
    }

    private static int occurrences(String text, String needle) {
        return (text.length() - text.replace(needle, "").length()) / needle.length();
    }

    private void compileAndRunFixture(String source) throws Exception {
        Path fixture = write(source);
        Path sink = directory.resolve("TraceSink.java");
        Files.writeString(sink, """
                public class TraceSink {
                    public static final java.util.List<String> events = new java.util.ArrayList<>();
                    public static void record(String kind, int line, String name, Object value) {
                        events.add(kind + ":" + line + ":" + name + ":" + value);
                    }
                }
                """);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "17", "-d", directory.toString(), fixture.toString(), sink.toString()));
        try (var loader = new URLClassLoader(new java.net.URL[]{directory.toUri().toURL()})) {
            Class<?> type = loader.loadClass("TraceFixture");
            var selected = type.getDeclaredMethod("selected", boolean.class);
            selected.setAccessible(true);
            assertEquals(4, selected.invoke(null, true));
            assertEquals(5, selected.invoke(null, false));
            var checks = type.getDeclaredField("checks");
            checks.setAccessible(true);
            assertEquals(2, checks.get(null));
            var events = (java.util.List<?>) loader.loadClass("TraceSink").getField("events").get(null);
            assertEquals(java.util.List.of("METHOD_ENTRY:5::null", "LOCAL_DECLARATION:6:value:1",
                    "ASSIGNMENT:7:value:3", "BRANCH_THEN:8::null", "ASSIGNMENT:9:value:4",
                    "METHOD_ENTRY:5::null", "LOCAL_DECLARATION:6:value:1", "ASSIGNMENT:7:value:3",
                    "BRANCH_ELSE:10::null", "ASSIGNMENT:11:value:5"), events);
        }
    }
}
