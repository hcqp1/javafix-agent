package com.javafix.agent.tool;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;

/** Owns two disposable copies of a project for isolated diagnostics. */
public final class DiagnosticWorkspace implements AutoCloseable {

    private static final long DEFAULT_MAX_BYTES = 100L * 1024 * 1024;
    private static final Set<String> SKIPPED_DIRECTORIES =
            Set.of(".git", "target", ".gradle", ".idea", "node_modules");

    private final Path temporaryRoot;
    private final Path baselineRoot;
    private final Path instrumentedRoot;

    private DiagnosticWorkspace(Path temporaryRoot) {
        this.temporaryRoot = temporaryRoot;
        this.baselineRoot = temporaryRoot.resolve("baseline");
        this.instrumentedRoot = temporaryRoot.resolve("instrumented");
    }

    public static DiagnosticWorkspace create(Path projectRoot) throws IOException {
        return create(projectRoot, DEFAULT_MAX_BYTES);
    }

    static DiagnosticWorkspace create(Path projectRoot, long maxBytes) throws IOException {
        if (maxBytes < 0) {
            throw new IllegalArgumentException("maxBytes must not be negative");
        }
        Path source = projectRoot.toAbsolutePath().normalize();
        return create(source, maxBytes, Path.of(System.getProperty("java.io.tmpdir")));
    }

    static DiagnosticWorkspace create(Path projectRoot, long maxBytes, Path temporaryBase) throws IOException {
        if (maxBytes < 0) {
            throw new IllegalArgumentException("maxBytes must not be negative");
        }
        Path source = projectRoot.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(source) || !Files.isDirectory(source)) {
            throw new IOException("Project root is not a directory: " + source);
        }
        Path tempBase = temporaryBase.toAbsolutePath().normalize();
        if (!Files.isDirectory(tempBase)) {
            throw new IOException("Temporary directory is not a directory: " + tempBase);
        }

        Path temp = Files.createTempDirectory(tempBase, "javafix-diagnostic-").toAbsolutePath().normalize();
        DiagnosticWorkspace workspace = new DiagnosticWorkspace(temp);
        try {
            Files.createDirectory(workspace.baselineRoot);
            Files.createDirectory(workspace.instrumentedRoot);
            long[] copiedBytes = {0};
            copyProject(source, workspace.baselineRoot, temp, maxBytes, copiedBytes);
            copyProject(source, workspace.instrumentedRoot, temp, maxBytes, copiedBytes);
            return workspace;
        } catch (IOException | RuntimeException failure) {
            try {
                deleteRecursively(temp);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public Path baselineRoot() {
        return baselineRoot;
    }

    public Path instrumentedRoot() {
        return instrumentedRoot;
    }

    @Override
    public void close() throws IOException {
        deleteRecursively(temporaryRoot);
    }

    private static void copyProject(Path source, Path destination, Path excludedRoot,
                                    long maxBytes, long[] copiedBytes)
            throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (dir.startsWith(excludedRoot)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (!dir.equals(source) && SKIPPED_DIRECTORIES.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (!dir.equals(source)) {
                    Files.createDirectories(destination.resolve(source.relativize(dir)));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                copiedBytes[0] += attrs.size();
                if (copiedBytes[0] > maxBytes) {
                    throw new IOException("Project exceeds diagnostic copy limit of " + maxBytes + " bytes");
                }
                Path target = destination.resolve(source.relativize(file));
                Files.createDirectories(target.getParent());
                Files.copy(file, target);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
