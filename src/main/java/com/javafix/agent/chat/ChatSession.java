package com.javafix.agent.chat;

import com.javafix.agent.Main;
import com.javafix.agent.core.AgentLoop;
import com.javafix.agent.llm.LlmClient;
import com.javafix.agent.task.TaskEvent;
import com.javafix.agent.task.TaskRecord;
import com.javafix.agent.task.TaskStatus;
import com.javafix.agent.task.TaskStore;
import com.javafix.agent.task.WorkspaceIdentity;
import com.javafix.agent.task.WorkspaceInspector;
import com.javafix.agent.tool.Tool;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Stateful command-line chat. State is persisted per Bug task, not per process. */
public final class ChatSession {

    private final BufferedReader input;
    private final PrintWriter output;
    private final TaskStore taskStore;
    private final LlmClient llmClient;
    private final WorkspaceInspector workspaceInspector;
    private String selectedProject;
    private String activeTaskId;
    private boolean running = true;

    public ChatSession(BufferedReader input, PrintWriter output, TaskStore taskStore, LlmClient llmClient) {
        this(input, output, taskStore, llmClient, new WorkspaceInspector());
    }

    ChatSession(BufferedReader input, PrintWriter output, TaskStore taskStore, LlmClient llmClient,
                WorkspaceInspector workspaceInspector) {
        this.input = input;
        this.output = output;
        this.taskStore = taskStore;
        this.llmClient = llmClient;
        this.workspaceInspector = workspaceInspector;
    }

    public void run() throws IOException {
        output.println("JavaFixAgent 持续聊天。输入 /help 查看命令；任务和对话保存在用户目录。\n");
        while (running) {
            output.print(prompt());
            output.flush();
            String line = input.readLine();
            if (line == null) break;
            String message = line.strip();
            if (message.isEmpty()) continue;
            if (message.startsWith("/")) {
                dispatch(message);
            } else if (activeTaskId == null) {
                output.println("还没有活动任务。先用 /project <路径> 选择项目，再用 /new <Bug 描述> 创建任务。");
            } else {
                fix(message);
            }
        }
        pauseActiveTask();
        output.println("会话已退出，任务记录已保留。");
    }

    private String prompt() {
        if (activeTaskId == null) return "javafix> ";
        return "[" + activeTaskId.substring(0, 8) + "] javafix> ";
    }

    private void dispatch(String line) throws IOException {
        int split = line.indexOf(' ');
        String command = (split < 0 ? line : line.substring(0, split)).toLowerCase(java.util.Locale.ROOT);
        String argument = split < 0 ? "" : line.substring(split + 1).strip();
        switch (command) {
            case "/help" -> showHelp();
            case "/project" -> selectProject(argument);
            case "/new" -> createTask(argument);
            case "/tasks" -> listTasks();
            case "/resume" -> resumeTask(argument);
            case "/ask" -> ask(argument);
            case "/fix" -> fix(argument);
            case "/exit", "/quit" -> running = false;
            default -> output.println("未知命令：" + command + "。输入 /help 查看命令。");
        }
    }

    private void showHelp() {
        output.println("命令：");
        output.println("  /project <路径>       选择项目目录");
        output.println("  /new <Bug 描述>       创建独立 Bug 任务并开始修复");
        output.println("  /tasks                列出已有任务");
        output.println("  /resume <任务ID>      恢复任务（检查项目/Git 状态）");
        output.println("  /ask <问题>           只读检索并回答代码问题");
        output.println("  /fix <需求>           在当前任务中继续修复；普通文本也视为 /fix");
        output.println("  /exit                 退出并保留任务记录");
    }

    private void selectProject(String pathText) throws IOException {
        if (pathText.isBlank()) {
            output.println("用法：/project <项目目录>");
            return;
        }
        Path path = Path.of(pathText).toAbsolutePath().normalize();
        if (!java.nio.file.Files.isDirectory(path)) {
            output.println("项目目录不存在：" + path);
            return;
        }
        pauseActiveTask();
        selectedProject = path.toRealPath().toString();
        output.println("当前项目已设为：" + selectedProject + "。请用 /new 创建任务，或 /resume 恢复已有任务。");
    }

    private void createTask(String description) throws IOException {
        if (selectedProject == null) {
            output.println("请先用 /project <路径> 选择项目。");
            return;
        }
        if (description.isBlank()) {
            output.println("用法：/new <Bug 描述>");
            return;
        }
        pauseActiveTask();
        TaskRecord task = taskStore.create(Path.of(selectedProject), description);
        activeTaskId = task.id();
        output.println("已创建任务 " + task.id() + "：" + task.title());
        fix(description);
    }

    private void listTasks() throws IOException {
        List<TaskRecord> tasks = taskStore.list();
        if (tasks.isEmpty()) {
            output.println("还没有保存的任务。");
            return;
        }
        for (TaskRecord task : tasks) {
            output.printf("%s  %-11s  %s  [%s]%n", task.id(), task.status(), task.title(),
                    task.workspace().projectPath());
        }
    }

    private void resumeTask(String id) throws IOException {
        if (id.isBlank()) {
            output.println("用法：/resume <任务ID>");
            return;
        }
        TaskRecord task;
        try {
            task = taskStore.load(resolveTaskId(id));
        } catch (IOException e) {
            output.println("无法恢复任务：" + e.getMessage());
            return;
        }
        Path project = Path.of(task.workspace().projectPath());
        if (!java.nio.file.Files.isDirectory(project)) {
            output.println("任务项目目录不存在：" + project + "。请先恢复该目录后再重试。");
            return;
        }
        WorkspaceIdentity current = workspaceInspector.inspect(project);
        List<String> differences = task.workspace().differences(current);
        if (!differences.isEmpty()) {
            output.println("恢复前发现工作区变化：" + String.join("、", differences));
            output.println("记录状态：" + task.workspace().statusSummary() + "；当前状态：" + current.statusSummary());
            output.print("继续使用当前工作区？输入 continue 或 cancel：");
            output.flush();
            String choice = input.readLine();
            if (choice == null || !choice.strip().equalsIgnoreCase("continue")) {
                output.println("已取消恢复；没有更改项目文件。");
                return;
            }
        }
        pauseActiveTask();
        selectedProject = current.projectPath();
        activeTaskId = task.id();
        taskStore.append(task.id(), TaskEvent.of("workspace_observed", Map.of(
                "reason", "resumed", "workspace", current)));
        taskStore.updateStatus(task.id(), TaskStatus.ACTIVE, current);
        output.println("已恢复任务 " + task.id() + "：" + task.title());
    }

    private String resolveTaskId(String prefix) throws IOException {
        List<TaskRecord> matches = taskStore.list().stream().filter(task -> task.id().startsWith(prefix)).toList();
        if (matches.size() != 1) {
            throw new IOException(matches.isEmpty() ? "找不到任务 ID：" + prefix : "任务 ID 前缀不唯一：" + prefix);
        }
        return matches.get(0).id();
    }

    private void ask(String question) throws IOException {
        if (!requireActiveTask("/ask")) return;
        if (question.isBlank()) {
            output.println("用法：/ask <问题>");
            return;
        }
        TaskRecord task = taskStore.load(activeTaskId);
        List<String> context = taskContext(task.events());
        taskStore.append(activeTaskId, TaskEvent.of("user_message", Map.of("mode", "ask", "content", question)));
        output.println("只读检索中…");
        String answer = new CodeQuestionHandler(llmClient, Main.createTools(Path.of(selectedProject)), selectedProject)
                .answer(question, context);
        taskStore.append(activeTaskId, TaskEvent.of("assistant_message", Map.of("mode", "ask", "content", answer)));
        output.println("\n" + answer + "\n");
    }

    private void fix(String request) throws IOException {
        if (!requireActiveTask("/fix")) return;
        if (request.isBlank()) {
            output.println("用法：/fix <修复需求>");
            return;
        }
        TaskRecord task = taskStore.load(activeTaskId);
        WorkspaceIdentity current = workspaceInspector.inspect(Path.of(selectedProject));
        List<String> differences = task.workspace().differences(current);
        if (!differences.isEmpty()) {
            output.println("工作区自上次记录后发生变化：" + String.join("、", differences));
            output.println("当前状态：" + current.statusSummary());
            output.print("确认在当前工作区继续？输入 continue 或 cancel：");
            output.flush();
            String choice = input.readLine();
            if (choice == null || !choice.strip().equalsIgnoreCase("continue")) {
                output.println("已取消本轮操作。");
                return;
            }
        }
        List<String> context = taskContext(task.events());
        taskStore.append(activeTaskId, TaskEvent.of("user_message", Map.of("mode", "fix", "content", request)));
        taskStore.append(activeTaskId, TaskEvent.of("agent_run_started", Map.of("mode", "fix")));
        taskStore.updateStatus(activeTaskId, TaskStatus.ACTIVE, current);

        AgentLoop loop = new AgentLoop(llmClient, Main.createTools(Path.of(selectedProject)));
        loop.onProgress(line -> output.println("[" + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                + "] " + line));
        loop.onRecord(entry -> appendUnchecked(TaskEvent.of("agent_step", Map.of("entry", entry))));
        output.println("项目：" + selectedProject + "\n正在处理任务 " + activeTaskId + "…");
        try {
            String answer = loop.run(request, context);
            taskStore.append(activeTaskId, TaskEvent.of("agent_run_finished", Map.of("answer", answer)));
            WorkspaceIdentity after = workspaceInspector.inspect(Path.of(selectedProject));
            taskStore.append(activeTaskId, TaskEvent.of("workspace_observed", Map.of("reason", "run_finished",
                    "workspace", after)));
            taskStore.updateStatus(activeTaskId, TaskStatus.COMPLETED, after);
            output.println("\n=== Agent 的结论 ===\n" + answer + "\n");
        } catch (RuntimeException | IOException e) {
            try {
                taskStore.updateStatus(activeTaskId, TaskStatus.INTERRUPTED,
                        workspaceInspector.inspect(Path.of(selectedProject)));
            } catch (IOException statusError) {
                e.addSuppressed(statusError);
            }
            output.println("本轮未正常完成，已保存已记录的步骤：" + e.getMessage());
        }
    }

    private boolean requireActiveTask(String command) {
        if (activeTaskId == null) {
            output.println(command + " 需要活动任务。请先执行 /project 后 /new，或使用 /resume。");
            return false;
        }
        return true;
    }

    private void pauseActiveTask() throws IOException {
        if (activeTaskId == null) return;
        WorkspaceIdentity workspace = null;
        try {
            workspace = workspaceInspector.inspect(Path.of(selectedProject));
        } catch (Exception ignored) {
            // Pausing a conversation must not discard the durable task log if its project disappeared.
        }
        taskStore.updateStatus(activeTaskId, TaskStatus.PAUSED, workspace);
        activeTaskId = null;
    }

    private List<String> taskContext(List<TaskEvent> events) {
        List<String> context = new ArrayList<>();
        for (TaskEvent event : events) {
            Object content = event.data().get("content");
            if ("user_message".equals(event.type()) && content instanceof String text) {
                context.add("用户此前请求：" + trim(text, 1200));
            } else if ("assistant_message".equals(event.type()) && content instanceof String text) {
                context.add("只读问答结果：" + trim(text, 1800));
            } else if ("agent_run_finished".equals(event.type())) {
                Object answer = event.data().get("answer");
                if (answer instanceof String text) context.add("此前修复轮次结论：" + trim(text, 2000));
            }
        }
        if (context.size() > 12) return List.copyOf(context.subList(context.size() - 12, context.size()));
        return List.copyOf(context);
    }

    private static String trim(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit) + "…";
    }

    private void appendUnchecked(TaskEvent event) {
        try {
            taskStore.append(activeTaskId, event);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
