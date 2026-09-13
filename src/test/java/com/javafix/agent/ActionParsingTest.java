package com.javafix.agent;

import com.javafix.agent.core.Action;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 解析器容错的回归测试。
 *
 * <p>下面这些"坏格式"全部来自 2026-09-13 那次真实运行——那次 16 步全部卡在复现阶段，
 * 9 次工具调用都因为参数没解析出来而失败。把真实翻车现场固化成用例，
 * 以后再出现同类形状就不会重蹈覆辙。
 */
class ActionParsingTest {

    @Test
    void shouldRecoverArgumentsFromInlineBraceForm() {

        Action action = Action.parse(
                "TOOL: search_code\nARGS: {query=RocksDB, path=, regex=false}\n"
        );

        assertEquals("search_code", action.toolName());
        assertEquals("RocksDB", action.arguments().get("query"));
    }

    @Test
    void shouldRecoverArgumentsNestedUnderArgsKey() {

        Action action = Action.parse(
                "TOOL: search_code\nARGS: {args={query=TimerMessageRocksDBStore, regex=false}}\n"
        );

        assertEquals("TimerMessageRocksDBStore", action.arguments().get("query"));
    }

    @Test
    void shouldRecoverPathFromDoublyNestedBraces() {

        Action action = Action.parse(
                "TOOL: read_file\nARGS: {args={PATH: store/src/main/java/org/apache/rocketmq"
                        + "/store/timer/rocksdb/Timeline.java}}\n"
        );

        assertEquals(
                "store/src/main/java/org/apache/rocketmq/store/timer/rocksdb/Timeline.java",
                action.arguments().get("path")
        );
    }

    @Test
    void shouldStillUnderstandTheProperLineProtocol() {

        Action action = Action.parse("""
                TOOL: write_file
                PATH: src/test/java/com/example/CalculatorTest.java
                CONTENT:
                package com.example;

                class CalculatorTest {
                }
                """);

        assertEquals("write_file", action.toolName());
        assertEquals("src/test/java/com/example/CalculatorTest.java", action.arguments().get("path"));
        assertTrue(action.arguments().get("content").contains("package com.example;"));
        assertTrue(action.arguments().get("content").contains("class CalculatorTest"));
    }

    @Test
    void shouldStillRejectResponsesWithoutToolOrFinal() {

        assertThrows(IllegalArgumentException.class, () -> Action.parse("我觉得这里有点问题\n"));
    }
}
