package com.javafix.agent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FindReferencesToolTest {

    @TempDir
    Path project;

    @Test
    void findsDeclarationsOverloadsAndCallsOnTypedVariables() throws IOException {
        write("src/main/java/p/Foo.java", """
                package p;
                public class Foo {
                    public void run() { this.run(1); }
                    public void run(int value) { }
                }
                """);
        write("src/main/java/p/Caller.java", """
                package p;
                class Caller {
                    void call(Foo foo) { foo.run(); Runnable reference = foo::run; }
                }
                """);

        String result = tool().execute(Map.of("type", "p.Foo", "method", "run"));

        assertTrue(result.contains("DECLARATION p.Foo#run"), result);
        assertTrue(result.contains("src/main/java/p/Foo.java:3"), result);
        assertTrue(result.contains("src/main/java/p/Foo.java:4"), result);
        assertTrue(result.contains("src/main/java/p/Caller.java:3"), result);
        assertTrue(result.contains("method-reference"), result);
    }

    @Test
    void parseErrorsDoNotHideValidDeclarations() throws IOException {
        write("src/main/java/p/Foo.java", "package p; class Foo { void run() {} }");
        write("src/main/java/p/Broken.java", "package p; class Broken { void x( ");

        String result = tool().execute(Map.of("type", "p.Foo", "method", "run"));

        assertTrue(result.contains("DECLARATION p.Foo#run"), result);
        assertTrue(result.toLowerCase().contains("parse error"), result);
    }

    @Test
    void limitsResultsAndReportsWhenMoreCandidatesExist() throws IOException {
        write("src/main/java/p/Foo.java", "package p; class Foo { void run() {} void run(int n) {} }");

        String result = tool().execute(Map.of("type", "p.Foo", "method", "run", "max_results", "1"));

        assertTrue(result.contains("DECLARATION p.Foo#run"), result);
        assertTrue(result.toLowerCase().contains("results truncated"), result);
    }

    @Test
    void respectsRepositoryRelativeScopeAndRejectsTraversal() throws IOException {
        write("src/main/java/p/Foo.java", "package p; class Foo { void run() {} }");
        write("src/test/java/p/Other.java", "package p; class Other { void run() {} }");

        String result = tool().execute(Map.of("type", "p.Foo", "method", "run", "path", "src/test"));
        assertTrue(!result.contains("src/main/java/p/Foo.java"), result);

        assertThrows(IllegalArgumentException.class,
                () -> tool().execute(Map.of("type", "p.Foo", "method", "run", "path", "../../outside")));
    }

    private FindReferencesTool tool() {
        return new FindReferencesTool(project);
    }

    private void write(String relative, String content) throws IOException {
        Path path = project.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
}
