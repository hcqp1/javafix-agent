package com.javafix.agent.tool;

import javax.lang.model.SourceVersion;
import java.util.Objects;

/** Produces a self-contained Java 8 helper for an instrumented source file. */
public final class TraceEventWriter {
    private TraceEventWriter() {
    }

    public static String helperSource(String className, String source, String method,
                                      int eventLimit, int valueLimit) {
        if (className == null || !SourceVersion.isIdentifier(className)
                || SourceVersion.isKeyword(className) || className.equals("_")) {
            throw new IllegalArgumentException("Expected a simple Java helper class name");
        }
        if (eventLimit <= 0 || valueLimit <= 0) {
            throw new IllegalArgumentException("Trace bounds must be positive");
        }
        return """
                final class %s {
                    private static long sequence;
                    private static long omitted;
                    private static java.nio.file.Path path;
                    static {
                        try {
                            String configured = System.getenv("JAVAFIX_TRACE_FILE");
                            if (configured != null && !configured.isEmpty()) {
                                path = java.nio.file.Paths.get(configured);
                                Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                                    public void run() { flushOmitted(); }
                                }));
                            }
                        } catch (Throwable ignored) { path = null; }
                    }
                    static synchronized void record(String kind, int line, String variableName, Object value) {
                        if (path == null) return;
                        if (sequence >= %d) { if (omitted < Long.MAX_VALUE) omitted++; return; }
                        try {
                            String text = scalar(value);
                            boolean truncated = text != null && text.length() > %d;
                            if (truncated) text = text.substring(0, %d);
                            append("{\\"sequence\\":" + (++sequence)
                                + ",\\"source\\":" + quote(%s) + ",\\"method\\":" + quote(%s)
                                + ",\\"kind\\":" + quote(kind) + ",\\"line\\":" + line
                                + ",\\"variable\\":" + quote(variableName) + ",\\"value\\":" + quote(text)
                                + ",\\"truncated\\":" + truncated + "}");
                        } catch (Throwable ignored) { path = null; }
                    }
                    private static String scalar(Object value) {
                        if (value == null) return null;
                        Class<?> type = value.getClass();
                        if (type == String.class) return (String) value;
                        if (type == Character.class || type == Boolean.class || type == Byte.class
                                || type == Short.class || type == Integer.class || type == Long.class
                                || type == Float.class || type == Double.class) return value.toString();
                        return "<type:" + type.getName() + ">";
                    }
                    private static synchronized void flushOmitted() {
                        if (path == null || omitted == 0) return;
                        try {
                            append("{\\"sequence\\":" + (sequence + 1)
                                + ",\\"kind\\":\\"overflow\\",\\"omitted\\":" + omitted + "}");
                        } catch (Throwable ignored) { path = null; }
                    }
                    private static void append(String event) throws java.io.IOException {
                        java.nio.file.Files.write(path, (event + "\\n").getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                    }
                    private static String quote(String text) {
                        if (text == null) return "null";
                        StringBuilder result = new StringBuilder("\\\"");
                        for (int i = 0; i < text.length(); i++) {
                            char c = text.charAt(i);
                            if (c == 34 || c == 92) { result.append((char) 92).append(c); }
                            else if (c < 32 || Character.isSurrogate(c)) {
                                result.append((char) 92).append('u');
                                String hex = Integer.toHexString(c);
                                for (int j = hex.length(); j < 4; j++) result.append('0');
                                result.append(hex);
                            } else result.append(c);
                        }
                        return result.append((char) 34).toString();
                    }
                }
                """.formatted(className, eventLimit, valueLimit, valueLimit, javaLiteral(source), javaLiteral(method));
    }

    private static String javaLiteral(String value) {
        Objects.requireNonNull(value);
        StringBuilder result = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') result.append('\\').append(c);
            else if (c < 32) result.append(String.format("\\%03o", (int) c));
            else result.append(c);
        }
        return result.append('"').toString();
    }
}
