package com.javafix.agent.task;

import java.util.List;

public record TaskRecord(
        String id,
        String title,
        TaskStatus status,
        WorkspaceIdentity workspace,
        String createdAt,
        String updatedAt,
        String lastRunId,
        List<TaskEvent> events) {
    public TaskRecord {
        events = List.copyOf(events);
    }
}
