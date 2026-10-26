package com.javafix.agent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DiagnosticWorkspaceTest {

    @TempDir
    Path project;

    @Test
    void copiesProjectFilesToBothRootsAndRemovesTemporaryRootOnClose() throws Exception {
        Files.createDirectories(project.resolve("src/main/java"));
        Files.writeString(project.resolve("pom.xml"), "<project/>\n");
        Files.writeString(project.resolve("src/main/java/A.java"), "class A {}\n");
        Files.createDirectories(project.resolve(".git"));
        Files.writeString(project.resolve(".git/config"), "ignored");
        Files.createDirectories(project.resolve("target/classes"));
        Files.writeString(project.resolve("target/classes/A.class"), "ignored");

        DiagnosticWorkspace workspace = DiagnosticWorkspace.create(project);
        Path tempRoot = workspace.baselineRoot().getParent();
        try (workspace) {
            for (Path root : new Path[]{workspace.baselineRoot(), workspace.instrumentedRoot()}) {
                assertEquals("<project/>\n", Files.readString(root.resolve("pom.xml")));
                assertEquals("class A {}\n", Files.readString(root.resolve("src/main/java/A.java")));
                assertFalse(Files.exists(root.resolve(".git")));
                assertFalse(Files.exists(root.resolve("target")));
            }
            assertNotEquals(workspace.baselineRoot(), workspace.instrumentedRoot());
            assertEquals("class A {}\n", Files.readString(project.resolve("src/main/java/A.java")));
        }
        assertFalse(Files.exists(tempRoot));
    }

    @Test
    void skipsSymbolicLinks() throws Exception {
        Path outside = Files.createTempFile("diagnostic-workspace-link", ".txt");
        try {
            Files.writeString(outside, "outside");
            Files.createSymbolicLink(project.resolve("linked.txt"), outside);
        } catch (UnsupportedOperationException | IOException e) {
            Files.deleteIfExists(outside);
            Assumptions.abort("Symbolic links are unavailable: " + e.getMessage());
        }
        try {
            try (DiagnosticWorkspace workspace = DiagnosticWorkspace.create(project)) {
                assertFalse(Files.exists(workspace.baselineRoot().resolve("linked.txt")));
                assertFalse(Files.exists(workspace.instrumentedRoot().resolve("linked.txt")));
            }
        } finally {
            Files.deleteIfExists(project.resolve("linked.txt"));
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void rejectsSymbolicLinkAsProjectRoot() throws Exception {
        Path link = project.getParent().resolve("diagnostic-project-link");
        try {
            Files.createSymbolicLink(link, project);
        } catch (UnsupportedOperationException | IOException e) {
            Files.deleteIfExists(link);
            Assumptions.abort("Symbolic links are unavailable: " + e.getMessage());
            return;
        }
        try {
            assertThrows(IOException.class, () -> DiagnosticWorkspace.create(link));
        } finally {
            Files.deleteIfExists(link);
        }
    }

    @Test
    void skipsTemporaryRootWhenItIsCreatedInsideSourceTree() throws Exception {
        Path tempBase = project.resolve("temporary-area");
        Files.createDirectories(tempBase);
        Files.writeString(project.resolve("project.txt"), "source");

        try (DiagnosticWorkspace workspace = DiagnosticWorkspace.create(project, 1024, tempBase)) {
            assertEquals("source", Files.readString(workspace.baselineRoot().resolve("project.txt")));
            assertEquals("source", Files.readString(workspace.instrumentedRoot().resolve("project.txt")));
            try (var entries = Files.list(workspace.baselineRoot().resolve("temporary-area"))) {
                assertTrue(entries.findAny().isEmpty(), "generated workspace must not be copied into itself");
            }
            try (var entries = Files.list(workspace.instrumentedRoot().resolve("temporary-area"))) {
                assertTrue(entries.findAny().isEmpty(), "generated workspace must not be copied into itself");
            }
        }
    }

    @Test
    void rejectsProjectsThatExceedConfiguredCopyLimit() throws Exception {
        Files.writeString(project.resolve("large.txt"), "12345");
        Path tempDirectory = Path.of(System.getProperty("java.io.tmpdir"));
        long existingWorkspaces;
        try (var entries = Files.list(tempDirectory)) {
            existingWorkspaces = entries.filter(path -> path.getFileName().toString().startsWith("javafix-diagnostic-")).count();
        }

        assertThrows(IOException.class, () -> DiagnosticWorkspace.create(project, 4));

        try (var entries = Files.list(tempDirectory)) {
            assertEquals(existingWorkspaces,
                    entries.filter(path -> path.getFileName().toString().startsWith("javafix-diagnostic-")).count(),
                    "failed copies must remove their temporary root");
        }
    }
}
