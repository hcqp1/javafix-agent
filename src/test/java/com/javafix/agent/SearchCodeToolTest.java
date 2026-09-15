package com.javafix.agent;

import com.javafix.agent.tool.SearchCodeTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 搜索工具的两个行为：能把范围收窄到指定目录，以及拒绝不认识的参数。
 *
 * <p>两条都来自真实运行：模型一直传 {@code path} 想收窄范围，而工具静默忽略它，
 * 于是搜索永远"太宽泛"，七步就这么烧掉了。
 */
class SearchCodeToolTest {

    @TempDir
    Path project;

    @Test
    void shouldScopeTheSearchToTheGivenPath() throws IOException {

        Files.createDirectories(project.resolve("a"));
        Files.createDirectories(project.resolve("b"));
        Files.writeString(project.resolve("a/Hit.java"), "class Hit { String s = \"needle\"; }");
        Files.writeString(project.resolve("b/Other.java"), "class Other { String s = \"needle\"; }");

        SearchCodeTool tool = new SearchCodeTool(project);

        String everything = tool.execute(Map.of("query", "needle"));
        assertTrue(everything.contains("Hit.java"), everything);
        assertTrue(everything.contains("Other.java"), everything);

        String scoped = tool.execute(Map.of("query", "needle", "path", "a"));
        assertTrue(scoped.contains("Hit.java"), "限定目录后应该仍然找到它：" + scoped);
        assertFalse(scoped.contains("Other.java"), "path 参数应该把搜索限制在 a 目录：" + scoped);
    }

    @Test
    void shouldRejectUnknownArgumentsAndSayWhatIsSupported() {

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new SearchCodeTool(project).execute(Map.of("query", "x", "grep", "y"))
        );

        assertTrue(failure.getMessage().contains("grep"), "要说清哪个参数不被支持：" + failure.getMessage());
        assertTrue(failure.getMessage().contains("query"), "要列出支持的参数：" + failure.getMessage());
    }
}
