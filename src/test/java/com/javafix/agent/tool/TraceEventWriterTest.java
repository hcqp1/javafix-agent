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
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

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

    @Test
    void neverResolvesApplicationClassesAsJdkAllowlistOrRuntimeTypes() throws Exception {
        compile(5, 100, "Probe.record(\"local\", 1, \"x\", new Boolean());", """
                class Boolean {
                    public java.lang.String toString() {
                        throw new java.lang.AssertionError("application Boolean conversion");
                    }
                }
                class System {
                    public static java.lang.String getenv(java.lang.String name) {
                        java.lang.System.out.print("application System invoked");
                        return java.lang.System.getenv(name);
                    }
                }
                class Object {} class String {} class Character {} class Byte {} class Short {}
                class Integer {} class Long {} class Float {} class Double {} class Class {}
                class StringBuilder {} class Throwable {} class Runtime {} class Thread {} class Runnable {}
                """);
        ProcessRunner.Result result = run(Map.of("JAVAFIX_TRACE_FILE", directory.resolve("trace.jsonl").toString()));
        assertEquals(0, result.exitCode(), result.output());
        assertTrue(Files.exists(directory.resolve("trace.jsonl")), result.output());
        assertEquals("<type:Boolean>", readEvents().get(0).get("value").asText());
        assertEquals("", result.output());
    }

    @Test
    void countsShutdownHookCallsWithoutDependingOnHookOrder() throws Exception {
        List<JsonNode> events = execute(1, 20, """
                Probe.record("main", 1, "x", 1);
                Probe.record("main", 2, "x", 2);
                Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                    public void run() {
                        try { Thread.sleep(300); } catch (InterruptedException e) { throw new AssertionError(e); }
                        for (int i = 0; i < 3; i++) Probe.record("shutdown", 3, "x", i);
                    }
                }));
                """);
        assertEquals(2, events.size());
        assertEquals(4, events.get(1).get("omitted").asInt());
    }

    @Test
    void sharesCapAndExactOmissionCountAcrossConcurrentThreadsAndForkedJvms() throws Exception {
        compile(7, 20, """
                Thread[] threads = new Thread[4];
                for (int i = 0; i < threads.length; i++) {
                    threads[i] = new Thread(new Runnable() {
                        public void run() {
                            for (int j = 0; j < 100; j++) {
                                if (j % 2 == 0) Probe.record("thread", j, "x", j);
                                else OtherProbe.record("thread", j, "x", j);
                            }
                        }
                    });
                    threads[i].start();
                }
                for (Thread thread : threads) {
                    try { thread.join(); } catch (InterruptedException e) { throw new AssertionError(e); }
                }
                """, TraceEventWriter.helperSource("OtherProbe", "src/Fixture.java", "run", 7, 20));
        List<Process> children = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                ProcessBuilder child = new ProcessBuilder(javaCommand());
                child.environment().put("JAVAFIX_TRACE_FILE", directory.resolve("trace.jsonl").toString());
                child.redirectErrorStream(true).redirectOutput(directory.resolve("fork-" + i + ".log").toFile());
                children.add(child.start());
            }
            for (Process child : children) {
                assertTrue(child.waitFor(30, TimeUnit.SECONDS));
                assertEquals(0, child.exitValue());
            }
        } finally {
            children.forEach(Process::destroyForcibly);
        }
        List<JsonNode> events = readEvents();
        assertEquals(8, events.size());
        for (int i = 0; i < events.size(); i++) assertEquals(i + 1, events.get(i).get("sequence").asInt());
        assertEquals(1193, events.get(7).get("omitted").asInt());
        assertTrue(Files.size(directory.resolve("trace.jsonl")) < 3000);
    }

    @Test
    void exposesExactOmissionsBeforeJvmShutdown() throws Exception {
        execute(1, 20, """
                for (int i = 0; i < 12; i++) Probe.record("local", i, "x", i);
                try {
                    java.util.List<String> lines = java.nio.file.Files.readAllLines(
                        java.nio.file.Paths.get(System.getenv("JAVAFIX_TRACE_FILE")), java.nio.charset.StandardCharsets.UTF_8);
                    if (lines.size() != 2 || !lines.get(1).contains("\\"omitted\\":11"))
                        throw new AssertionError("missing live omission summary: " + lines);
                } catch (java.io.IOException e) { throw new AssertionError(e); }
                """);
    }

    private List<JsonNode> execute(int cap, int length, String body) throws Exception {
        compile(cap, length, body);
        ProcessRunner.Result result = run(Map.of("JAVAFIX_TRACE_FILE", directory.resolve("trace.jsonl").toString()));
        assertEquals(0, result.exitCode(), result.output());
        return readEvents();
    }

    private List<JsonNode> readEvents() throws Exception {
        return Files.readAllLines(directory.resolve("trace.jsonl")).stream().map(line -> {
            try { return JSON.readTree(line); }
            catch (Exception e) { throw new AssertionError(line, e); }
        }).toList();
    }

    private void compile(int cap, int length, String body) throws Exception {
        compile(cap, length, body, "");
    }

    private void compile(int cap, int length, String body, String extraTypes) throws Exception {
        Path source = directory.resolve("Fixture.java");
        Files.writeString(source, """
                public class Fixture {
                    static class Dangerous extends java.lang.Number {
                        public java.lang.String toString() { throw new AssertionError("unsafe toString"); }
                        public int intValue() { throw new AssertionError("unsafe Number"); }
                        public long longValue() { throw new AssertionError("unsafe Number"); }
                        public float floatValue() { throw new AssertionError("unsafe Number"); }
                        public double doubleValue() { throw new AssertionError("unsafe Number"); }
                    }
                    public static void main(java.lang.String[] args) { %s }
                }
                %s
                %s
                """.formatted(body, TraceEventWriter.helperSource("Probe", "src/Fixture.java", "run", cap, length), extraTypes));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-encoding", "UTF-8", "--release", "8", source.toString()));
    }

    private ProcessRunner.Result run(Map<String, String> environment) throws Exception {
        return ProcessRunner.run(javaCommand(), directory, directory.resolve("child.log"),
                Duration.ofSeconds(20), environment);
    }

    private List<String> javaCommand() {
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", directory.toString(), "Fixture");
    }
}
