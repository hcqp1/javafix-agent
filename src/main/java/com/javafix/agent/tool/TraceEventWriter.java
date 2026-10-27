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
                    private static java.nio.file.Path path;
                    private static java.lang.String monitor;
                    private static java.io.RandomAccessFile state;
                    private static java.io.RandomAccessFile trace;
                    private static java.io.RandomAccessFile status;
                    static {
                        try {
                            java.lang.String configured = java.lang.System.getenv("JAVAFIX_TRACE_FILE");
                            if (configured != null && !configured.isEmpty()) {
                                path = java.nio.file.Paths.get(configured);
                                monitor = ("javafix.trace:" + path.toFile().getCanonicalPath()).intern();
                                // The orchestrator preallocates this independent status channel before Maven starts.
                                status = new java.io.RandomAccessFile(path.toString() + ".status", "rws");
                            }
                        } catch (java.lang.Throwable ignored) { failCapture(); }
                    }
                    static synchronized void record(java.lang.String kind, int line,
                            java.lang.String variableName, java.lang.Object value) {
                        if (path == null) return;
                        // Interning shares the JVM monitor across generated helpers and classloaders.
                        synchronized (monitor) {
                            try {
                                // Keep unbuffered handles for this JVM's lifetime; avoid reopening on hot probes.
                                if (state == null) state = new java.io.RandomAccessFile(path.toString() + ".state", "rw");
                                try (java.nio.channels.FileLock lock = state.getChannel().lock()) {
                                    if (trace == null) trace = new java.io.RandomAccessFile(path.toFile(), "rw");
                                    long sequence = 0;
                                    long omitted = 0;
                                    long end = 0;
                                    state.seek(0);
                                    if (state.length() == 24) {
                                        sequence = state.readLong(); omitted = state.readLong(); end = state.readLong();
                                    } else if (state.length() != 0 || trace.length() != 0) {
                                        throw new java.io.IOException("Trace requires matching state");
                                    }
                                    trace.seek(end);
                                    if (sequence >= %d) {
                                        if (omitted < java.lang.Long.MAX_VALUE) omitted++;
                                        write(trace, "{\\"sequence\\":" + (sequence + 1)
                                            + ",\\"kind\\":\\"overflow\\",\\"omitted\\":" + omitted + "}");
                                    } else {
                                        java.lang.String text = scalar(value);
                                        boolean truncated = text != null && text.length() > %d;
                                        if (truncated) text = text.substring(0, %d);
                                        write(trace, "{\\"sequence\\":" + (++sequence)
                                            + ",\\"source\\":" + quote(%s) + ",\\"method\\":" + quote(%s)
                                            + ",\\"kind\\":" + quote(kind) + ",\\"line\\":" + line
                                            + ",\\"variable\\":" + quote(variableName) + ",\\"value\\":" + quote(text)
                                            + ",\\"truncated\\":" + truncated + "}");
                                        end = trace.getFilePointer();
                                    }
                                    state.seek(0);
                                    state.writeLong(sequence); state.writeLong(omitted); state.writeLong(end);
                                }
                            } catch (java.lang.Throwable ignored) {
                                failCapture();
                                try { if (trace != null) trace.close(); } catch (java.lang.Throwable closeFailure) { }
                                try { if (state != null) state.close(); } catch (java.lang.Throwable closeFailure) { }
                            }
                        }
                    }
                    private static void failCapture() {
                        // This already-open synchronous channel does not depend on the interrupted state lock.
                        boolean interrupted = java.lang.Thread.interrupted();
                        try {
                            try {
                                if (status != null) {
                                    status.seek(0);
                                    status.write(1);
                                }
                            } catch (java.lang.Throwable markerFailure) { }
                            // Keep the legacy marker as a secondary signal for older readers/diagnostics.
                            try {
                                if (path != null) try (java.io.RandomAccessFile failed =
                                        new java.io.RandomAccessFile(path.toString() + ".failed", "rws")) { failed.write(1); }
                            } catch (java.lang.Throwable markerFailure) { }
                        } finally {
                            if (interrupted) java.lang.Thread.currentThread().interrupt();
                        }
                        path = null;
                    }
                    private static java.lang.String scalar(java.lang.Object value) {
                        if (value == null) return null;
                        java.lang.Class<?> type = value.getClass();
                        if (type == java.lang.String.class) return (java.lang.String) value;
                        if (type == java.lang.Character.class || type == java.lang.Boolean.class || type == java.lang.Byte.class
                                || type == java.lang.Short.class || type == java.lang.Integer.class || type == java.lang.Long.class
                                || type == java.lang.Float.class || type == java.lang.Double.class) return value.toString();
                        return "<type:" + type.getName() + ">";
                    }
                    private static void write(java.io.RandomAccessFile trace, java.lang.String event) throws java.io.IOException {
                        trace.write((event + "\\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                    private static java.lang.String quote(java.lang.String text) {
                        if (text == null) return "null";
                        java.lang.StringBuilder result = new java.lang.StringBuilder("\\\"");
                        for (int i = 0; i < text.length(); i++) {
                            char c = text.charAt(i);
                            if (c == 34 || c == 92) { result.append((char) 92).append(c); }
                            else if (c < 32 || java.lang.Character.isSurrogate(c)) {
                                result.append((char) 92).append('u');
                                java.lang.String hex = java.lang.Integer.toHexString(c);
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
