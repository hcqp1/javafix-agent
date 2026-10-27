package com.javafix.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TraceEventWriterTest {
    @TempDir Path directory;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void recordsOnlySafeScalarsAndEscapesJson() throws Exception {
        List<JsonNode> events = execute(30, 100, """
                Object[] values = {null, "quote" + new String(new char[]{34, 92, 10, 9, 1}), 'x', true, (byte) 1, (short) 2,
                    3, 4L, 1.5f, 2.5d, new Dangerous(), new int[]{1}};
                for (Object value : values) Probe.record("local", 12, "variable", value);
                """);
        assertEquals(12, events.size());
        assertTrue(events.get(0).get("value").isNull());
        assertEquals("quote\"\\\n\t\u0001", events.get(1).get("value").asText());
        List<String> expected = List.of("x", "true", "1", "2", "3", "4", "1.5", "2.5",
                "<type:Fixture$Dangerous>", "<type:[I>");
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i), events.get(i + 2).get("value").asText());
        }
        for (int i = 0; i < events.size(); i++) {
            JsonNode event = events.get(i);
            assertEquals(i + 1, event.get("sequence").asInt());
            assertEquals("src/Fixture.java", event.get("source").asText());
            assertEquals("run", event.get("method").asText());
            assertEquals("local", event.get("kind").asText());
            assertEquals(12, event.get("line").asInt());
            assertEquals("variable", event.get("variable").asText());
        }
    }

    @Test
    void boundsValuesAndRetainsEarliestEventsWithExactOmittedCount() throws Exception {
        List<JsonNode> events = execute(2, 8, """
                for (int i = 0; i < 1000; i++) Probe.record("local", i, "longVariableName", "01234567890123456789");
                """);
        assertEquals(3, events.size());
        assertEquals("01234567", events.get(0).get("value").asText());
        assertTrue(events.get(0).get("truncated").asBoolean());
        assertEquals(0, events.get(0).get("line").asInt());
        assertEquals(1, events.get(1).get("line").asInt());
        assertEquals("overflow", events.get(2).get("kind").asText());
        assertEquals(998, events.get(2).get("omitted").asInt());
        assertTrue(Files.size(directory.resolve("trace.jsonl")) < 2000);
    }

    @Test
    void writerFailuresDoNotChangeApplicationExecution() throws Exception {
        compile(2, 8, "Probe.record(\"entry\", 1, \"x\", new Dangerous()); System.out.print(\"survived\");");
        ProcessRunner.Result result = run(Map.of("JAVAFIX_TRACE_FILE", directory.toString()));
        assertEquals(0, result.exitCode(), result.output());
        assertEquals("survived", result.output());
    }

    @Test
    void rejectsInvalidGeneratedClassNamesAndBounds() {
        assertThrows(IllegalArgumentException.class, () -> TraceEventWriter.helperSource("x;bad", "s", "m", 1, 1));
        assertThrows(IllegalArgumentException.class, () -> TraceEventWriter.helperSource("class", "s", "m", 1, 1));
        assertThrows(IllegalArgumentException.class, () -> TraceEventWriter.helperSource("Probe", "s", "m", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> TraceEventWriter.helperSource("Probe", "s", "m", 1, 0));
    }

    private List<JsonNode> execute(int cap, int length, String body) throws Exception {
        compile(cap, length, body);
        ProcessRunner.Result result = run(Map.of("JAVAFIX_TRACE_FILE", directory.resolve("trace.jsonl").toString()));
        assertEquals(0, result.exitCode(), result.output());
        return Files.readAllLines(directory.resolve("trace.jsonl")).stream().map(line -> {
            try { return JSON.readTree(line); }
            catch (Exception e) { throw new AssertionError(line, e); }
        }).toList();
    }

    private void compile(int cap, int length, String body) throws Exception {
        Path source = directory.resolve("Fixture.java");
        Files.writeString(source, """
                public class Fixture {
                    static class Dangerous extends Number {
                        public String toString() { throw new AssertionError("unsafe toString"); }
                        public int intValue() { throw new AssertionError("unsafe Number"); }
                        public long longValue() { throw new AssertionError("unsafe Number"); }
                        public float floatValue() { throw new AssertionError("unsafe Number"); }
                        public double doubleValue() { throw new AssertionError("unsafe Number"); }
                    }
                    public static void main(String[] args) { %s }
                }
                %s
                """.formatted(body, TraceEventWriter.helperSource("Probe", "src/Fixture.java", "run", cap, length)));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-encoding", "UTF-8", "--release", "8", source.toString()));
    }

    private ProcessRunner.Result run(Map<String, String> environment) throws Exception {
        return ProcessRunner.run(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", directory.toString(), "Fixture"), directory, directory.resolve("child.log"),
                Duration.ofSeconds(20), environment);
    }
}
