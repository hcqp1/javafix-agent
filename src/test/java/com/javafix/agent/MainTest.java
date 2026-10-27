package com.javafix.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MainTest {
    @TempDir Path project;

    @Test
    void registersExactlyOneTraceDiagnosticTool() {
        assertEquals(1, Main.createTools(project).stream()
                .filter(tool -> tool.name().equals("diagnose_trace"))
                .count());
    }
}
