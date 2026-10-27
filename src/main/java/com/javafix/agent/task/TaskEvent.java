package com.javafix.agent.task;

import java.util.Map;

public record TaskEvent(String type, String timestamp, Map<String, Object> data) {
    public TaskEvent {
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    public static TaskEvent of(String type, Map<String, Object> data) {
        return new TaskEvent(type, java.time.Instant.now().toString(), data);
    }
}
