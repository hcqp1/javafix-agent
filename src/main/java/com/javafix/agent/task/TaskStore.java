package com.javafix.agent.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** User-local task metadata and append-only event storage. */
public final class TaskStore {

    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");

    private final Path tasksDirectory;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final WorkspaceInspector workspaceInspector;

    public TaskStore(Path tasksDirectory) {
        this(tasksDirectory, new WorkspaceInspector());
    }

    public TaskStore(Path tasksDirectory, WorkspaceInspector workspaceInspector) {
        this.tasksDirectory = tasksDirectory.toAbsolutePath().normalize();
        this.workspaceInspector = workspaceInspector;
    }

    public synchronized TaskRecord create(Path project, String title) throws IOException {
        ensureDirectory(tasksDirectory);
        WorkspaceIdentity workspace = workspaceInspector.inspect(project);
        String now = java.time.Instant.now().toString();
        String id = UUID.randomUUID().toString();
        TaskMetadata metadata = new TaskMetadata(id, title.strip(), TaskStatus.ACTIVE, workspace, now, now, null);
        Path directory = taskDirectory(id);
        ensureDirectory(directory);
        Files.createFile(directory.resolve("events.jsonl"));
        restrictFile(directory.resolve("events.jsonl"));
        writeMetadata(directory, metadata);
        append(id, TaskEvent.of("workspace_observed", Map.of("reason", "created", "workspace", workspace)));
        return load(id);
    }

    public synchronized List<TaskRecord> list() throws IOException {
        if (Files.notExists(tasksDirectory)) return List.of();
        List<TaskRecord> records = new ArrayList<>();
        try (var children = Files.list(tasksDirectory)) {
            for (Path child : children.filter(Files::isDirectory).toList()) {
                if (Files.exists(child.resolve("task.json"))) {
                    records.add(load(child.getFileName().toString()));
                }
            }
        }
        records.sort(Comparator.comparing(TaskRecord::updatedAt).reversed());
        return List.copyOf(records);
    }

    public synchronized TaskRecord load(String taskId) throws IOException {
        Path directory = taskDirectory(taskId);
        TaskMetadata metadata = mapper.readValue(directory.resolve("task.json").toFile(), TaskMetadata.class);
        List<TaskEvent> events = readEvents(directory.resolve("events.jsonl"));
        TaskStatus status = hasIncompleteTail(directory.resolve("events.jsonl")) || hasUnfinishedRun(events)
                ? TaskStatus.INTERRUPTED : metadata.status();
        return new TaskRecord(metadata.id(), metadata.title(), status, metadata.workspace(), metadata.createdAt(),
                metadata.updatedAt(), metadata.lastRunId(), events);
    }

    public synchronized void append(String taskId, TaskEvent event) throws IOException {
        Path directory = taskDirectory(taskId);
        TaskMetadata metadata = readMetadata(directory);
        Path eventsFile = directory.resolve("events.jsonl");
        discardIncompleteTail(eventsFile);
        Files.writeString(eventsFile, mapper.writeValueAsString(event) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
        restrictFile(eventsFile);
        writeMetadata(directory, new TaskMetadata(metadata.id(), metadata.title(), metadata.status(),
                metadata.workspace(), metadata.createdAt(), java.time.Instant.now().toString(), metadata.lastRunId()));
    }

    public synchronized void updateStatus(String taskId, TaskStatus status, WorkspaceIdentity workspace)
            throws IOException {
        Path directory = taskDirectory(taskId);
        TaskMetadata metadata = readMetadata(directory);
        WorkspaceIdentity nextWorkspace = workspace == null ? metadata.workspace() : workspace;
        writeMetadata(directory, new TaskMetadata(metadata.id(), metadata.title(), status, nextWorkspace,
                metadata.createdAt(), java.time.Instant.now().toString(), metadata.lastRunId()));
        append(taskId, TaskEvent.of("task_status_changed", Map.of("status", status.name())));
    }

    public synchronized void setLastRunId(String taskId, String runId) throws IOException {
        Path directory = taskDirectory(taskId);
        TaskMetadata metadata = readMetadata(directory);
        writeMetadata(directory, new TaskMetadata(metadata.id(), metadata.title(), metadata.status(),
                metadata.workspace(), metadata.createdAt(), java.time.Instant.now().toString(), runId));
    }

    private List<TaskEvent> readEvents(Path path) throws IOException {
        if (Files.notExists(path)) return List.of();
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        List<TaskEvent> events = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) continue;
            try {
                events.add(mapper.readValue(line, TaskEvent.class));
            } catch (JsonProcessingException e) {
                if (i == lines.size() - 1) break;
                throw new IOException("任务事件文件中间存在损坏记录：" + path + " 第 " + (i + 1) + " 行", e);
            }
        }
        return List.copyOf(events);
    }

    private boolean hasIncompleteTail(Path path) throws IOException {
        if (Files.notExists(path)) return false;
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.isEmpty()) return false;
        String last = lines.get(lines.size() - 1);
        if (last.isBlank()) return false;
        try {
            mapper.readTree(last);
            return false;
        } catch (JsonProcessingException e) {
            return true;
        }
    }

    private static boolean hasUnfinishedRun(List<TaskEvent> events) {
        boolean started = false;
        for (TaskEvent event : events) {
            if ("agent_run_started".equals(event.type())) started = true;
            if ("agent_run_finished".equals(event.type())) started = false;
        }
        return started;
    }

    private void discardIncompleteTail(Path path) throws IOException {
        if (!hasIncompleteTail(path)) return;
        byte[] content = Files.readAllBytes(path);
        int lastNewline = -1;
        int start = content.length > 0 && content[content.length - 1] == '\n'
                ? content.length - 2 : content.length - 1;
        for (int i = start; i >= 0; i--) {
            if (content[i] == '\n') {
                lastNewline = i;
                break;
            }
        }
        try (var channel = java.nio.channels.FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.truncate(lastNewline + 1L);
            channel.force(true);
        }
    }

    private TaskMetadata readMetadata(Path directory) throws IOException {
        return mapper.readValue(directory.resolve("task.json").toFile(), TaskMetadata.class);
    }

    private void writeMetadata(Path directory, TaskMetadata metadata) throws IOException {
        Path target = directory.resolve("task.json");
        Path temporary = directory.resolve("task.json.tmp");
        mapper.writeValue(temporary.toFile(), metadata);
        restrictFile(temporary);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
        restrictFile(target);
    }

    private Path taskDirectory(String taskId) throws IOException {
        if (taskId == null || !taskId.matches("[0-9a-fA-F-]{36}")) {
            throw new IOException("无效任务 ID");
        }
        Path directory = tasksDirectory.resolve(taskId).normalize();
        if (!directory.getParent().equals(tasksDirectory)) throw new IOException("无效任务 ID");
        return directory;
    }

    private static void ensureDirectory(Path directory) throws IOException {
        if (supportsPosix(directory)) {
            Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
            Files.setPosixFilePermissions(directory, DIRECTORY_PERMISSIONS);
        } else {
            Files.createDirectories(directory);
        }
    }

    private static boolean supportsPosix(Path path) throws IOException {
        Path existing = path;
        while (existing != null && Files.notExists(existing)) existing = existing.getParent();
        return existing != null && Files.getFileStore(existing).supportsFileAttributeView(PosixFileAttributeView.class);
    }

    private static void restrictFile(Path file) throws IOException {
        if (Files.getFileAttributeView(file, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(file, FILE_PERMISSIONS);
        }
    }

    private record TaskMetadata(String id, String title, TaskStatus status, WorkspaceIdentity workspace,
                                String createdAt, String updatedAt, String lastRunId) {
    }
}
