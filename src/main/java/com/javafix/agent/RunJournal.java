package com.javafix.agent;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 每完成一步就追加记录；进程意外退出时，已完成步骤仍可查看。 */
final class RunJournal {

    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");

    private final Path path;
    private final ObjectMapper json = new ObjectMapper();

    private RunJournal(Path path) {
        this.path = path;
    }

    static RunJournal create(Path runsDirectory, Path project, String task) throws IOException {
        boolean posix = supportsPosix(runsDirectory);
        if (posix) {
            Files.createDirectories(runsDirectory,
                    PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
            Files.setPosixFilePermissions(runsDirectory, DIRECTORY_PERMISSIONS);
        } else {
            Files.createDirectories(runsDirectory);
        }
        Path path = runsDirectory.resolve(UUID.randomUUID() + ".jsonl");
        if (posix) {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS));
            Files.setPosixFilePermissions(path, FILE_PERMISSIONS);
        } else {
            Files.createFile(path);
        }
        RunJournal journal = new RunJournal(path);
        journal.write(Map.of("type", "start", "project", project.toString(), "task", task));
        return journal;
    }

    private static boolean supportsPosix(Path path) throws IOException {
        Path existing = path.toAbsolutePath().normalize();
        while (Files.notExists(existing)) {
            existing = existing.getParent();
        }
        return Files.getFileStore(existing).supportsFileAttributeView(PosixFileAttributeView.class);
    }

    Path path() {
        return path;
    }

    synchronized void appendStep(String entry) {
        try {
            write(Map.of("type", "step", "entry", entry));
        } catch (IOException e) {
            throw new UncheckedIOException("无法写入运行记录 " + path, e);
        }
    }

    synchronized void finish(String status) {
        try {
            write(Map.of("type", "finish", "status", status));
        } catch (IOException e) {
            throw new UncheckedIOException("无法写入运行结束状态 " + path, e);
        }
    }

    private void write(Map<String, String> event) throws IOException {
        Files.writeString(path, json.writeValueAsString(event) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND, StandardOpenOption.SYNC);
    }
}
