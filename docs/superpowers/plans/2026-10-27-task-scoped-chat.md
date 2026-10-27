# JavaFixAgent Task-Scoped Chat Implementation Plan

> **For agentic workers:** This plan is being executed inline in the current session, task by task. Keep each step test-first and verify the focused test before continuing.

**Goal:** Add a persistent CLI chat whose Bug tasks keep isolated conversation histories and can be resumed safely without silently rolling back project files.

**Architecture:** Add a user-home configuration store and task repository backed by JSON plus append-only JSONL events. Keep `AgentLoop` as the existing repair engine, passing only the selected task's prior messages and outcomes into a new repair run. Add a REPL controller for task/project commands, read-only questions, repair requests, and workspace drift confirmation.

**Tech Stack:** Java 17, existing Jackson dependency, JDK `Console`/NIO/ProcessBuilder, JUnit 5.

## Global Constraints

- Configuration and task data live under `~/.javafix-agent/`, never inside a project repository.
- API keys are never written to task events, displayed in logs, or committed.
- Each Bug has a stable task ID; new tasks never load other tasks' conversation history.
- Resuming a task never performs checkout, reset, stash, or automatic file restoration.
- A changed workspace must be shown and explicitly accepted before any modifying repair tool can run.
- Existing one-shot CLI invocation remains available for compatibility.
- No concurrent task execution, Web UI, remote sync, or automatic worktree creation in this release.

---

### Task 1: Persist model configuration in the user directory

**Files:**
- Create: `src/main/java/com/javafix/agent/config/ModelConfig.java`
- Create: `src/main/java/com/javafix/agent/config/ModelConfigStore.java`
- Test: `src/test/java/com/javafix/agent/config/ModelConfigStoreTest.java`

**Interfaces:**
- `ModelConfig(String baseUrl, String apiKey, String model)` validates nonblank values.
- `ModelConfigStore(Path configFile).load()` returns `Optional<ModelConfig>`.
- `ModelConfigStore(Path configFile).save(ModelConfig)` writes JSON without printing secrets and applies owner-only POSIX permissions where supported.
- `ModelConfigStore.fromEnvironment(ModelConfig saved, Map<String,String> environment)` overlays nonblank `JAVAFIX_LLM_BASE_URL`, `JAVAFIX_LLM_API_KEY`, and `JAVAFIX_LLM_MODEL` values on saved values.

- [ ] Write tests for round-trip JSON, missing config, environment overrides, and secret not being returned by `toString()`.
- [ ] Run `mvn -q -Dtest=ModelConfigStoreTest test`; confirm failures are due to missing config types/behavior.
- [ ] Implement the config record and store using the existing Jackson dependency.
- [ ] Re-run the focused test and then the full `mvn test` suite.

### Task 2: Add task metadata, workspace inspection, and append-only events

**Files:**
- Create: `src/main/java/com/javafix/agent/task/TaskStatus.java`
- Create: `src/main/java/com/javafix/agent/task/WorkspaceIdentity.java`
- Create: `src/main/java/com/javafix/agent/task/WorkspaceInspector.java`
- Create: `src/main/java/com/javafix/agent/task/TaskRecord.java`
- Create: `src/main/java/com/javafix/agent/task/TaskStore.java`
- Test: `src/test/java/com/javafix/agent/task/WorkspaceInspectorTest.java`
- Test: `src/test/java/com/javafix/agent/task/TaskStoreTest.java`

**Interfaces:**
- `WorkspaceInspector.inspect(Path project)` returns canonical project path plus optional Git root, branch, HEAD, and a digest of `git status --porcelain`.
- `TaskStore(Path tasksDirectory).create(Path project, String title)` creates a stable task directory and initial metadata.
- `TaskStore.list()` returns task summaries sorted by `updatedAt` descending.
- `TaskStore.load(String taskId)` loads metadata and valid complete JSONL events; ignores only a malformed final partial line and reports the task as interrupted.
- `TaskStore.append(String taskId, TaskEvent event)` synchronously appends one JSON event and updates task metadata.
- `TaskStore.updateStatus(String taskId, TaskStatus status, WorkspaceIdentity workspace)` persists lifecycle and workspace observations.

- [ ] Write tests for Git and non-Git project identity, task isolation, event append/reload, stable IDs, and truncated final JSONL line recovery.
- [ ] Run each focused test class and confirm the expected missing-type failures.
- [ ] Implement the workspace inspector and task store; never invoke mutating Git commands.
- [ ] Re-run focused tests and the full `mvn test` suite.

### Task 3: Pass task-scoped history into repair runs

**Files:**
- Modify: `src/main/java/com/javafix/agent/core/AgentLoop.java`
- Test: `src/test/java/com/javafix/agent/AgentLoopTest.java`

**Interfaces:**
- Add `run(String symptom, List<String> priorTaskContext)`; keep `run(String symptom)` delegating with an empty context for compatibility.
- Prior context is clearly labeled as earlier user requests and outcomes from this task only; tool/action history remains bounded and is not allowed to imply unverified code state.

- [ ] Add a test proving prior task context appears in the LLM prompt while a default run contains no prior-task context.
- [ ] Run the focused test and verify it fails because the overload does not exist.
- [ ] Add the overload and prompt section without changing existing phase behavior.
- [ ] Run focused and full tests.

### Task 4: Implement read-only code Q&A

**Files:**
- Create: `src/main/java/com/javafix/agent/chat/CodeQuestionHandler.java`
- Test: `src/test/java/com/javafix/agent/chat/CodeQuestionHandlerTest.java`

**Interfaces:**
- `CodeQuestionHandler(LlmClient client, List<Tool> readOnlyTools)` answers a question using a dedicated prompt and only `read_file` / `search_code` capability descriptions; it never invokes AgentLoop or exposes write, shell, or test-running tools.
- If no source excerpts are available, it tells the model to state uncertainty and ask for a file/path rather than claim it inspected code.

- [ ] Define tests that verify the question prompt labels the request as read-only and excludes mutating tool names.
- [ ] Run the focused test and observe the expected missing-handler failure.
- [ ] Implement the minimal read-only question handler using the existing LlmClient abstraction.
- [ ] Re-run focused tests and full suite.

### Task 5: Add the continuous CLI session and task commands

**Files:**
- Create: `src/main/java/com/javafix/agent/chat/ChatSession.java`
- Test: `src/test/java/com/javafix/agent/chat/ChatSessionTest.java`
- Modify: `src/main/java/com/javafix/agent/Main.java`
- Modify: `src/test/java/com/javafix/agent/MainTest.java`

**Interfaces:**
- `ChatSession.run(Reader input, PrintWriter output)` provides testable REPL behavior without hard-wiring `System.in`.
- Commands: `/project <path>`, `/new <Bug>`, `/tasks`, `/resume <id>`, `/ask <question>`, `/fix <request>`, `/help`, `/exit`.
- `/new` pauses the current task and creates an isolated task context; `/resume` loads only its events and compares its recorded workspace identity with the selected current workspace.
- If workspace identity differs, display a summary and accept only explicit `continue` or `cancel`; no repair tools run before acceptance.
- API config wizard uses `Console.readPassword()` when available; if terminal password input is unavailable, fail with a clear instruction to set environment variables or rerun in an interactive terminal.
- Main with no positional arguments enters chat mode; legacy `<project> <Bug>` invocation remains unchanged.

- [ ] Write REPL tests for new task isolation, task listing/resume, `/ask` versus `/fix`, workspace drift confirmation, and `/exit` persistence.
- [ ] Run focused tests and confirm expected failures before implementation.
- [ ] Implement the testable REPL and connect it to config, task store, AgentLoop, and existing tools.
- [ ] Run focused tests and `mvn test`.

### Task 6: Document setup, commands, persistence, and safety limits

**Files:**
- Modify: `README.md`
- Modify: `src/test/java/com/javafix/agent/MainTest.java` if startup usage changed.

- [ ] Update quick start with initial model setup, subsequent no-key CLI launch, command reference, task storage location, and old one-shot invocation.
- [ ] State plainly that conversation resume does not restore files and that drift must be explicitly accepted.
- [ ] Review README commands and implementation behavior for consistency.
- [ ] Run `mvn test` and inspect `git diff --check` plus `git status --short`.

## Self-review checklist

- Spec goals are covered by Tasks 1–6; non-goals are enforced by Main/ChatSession and no Git mutation API is introduced.
- The plan preserves existing one-shot behavior and requires no new dependencies.
- Every new storage/config path is under the user home; each task directory is separate.
- The read-only Q&A scope is deliberately constrained; source retrieval must not call modifying tools.
- Resume is gated on workspace drift confirmation; records are never deleted by task switching.

## Execution note

The implementation is in place and `mvn -q -DskipTests compile` succeeds. Automated tests were not added or run in this pass. The accepted behavior and manual verification scenarios are listed in the approved spec's acceptance criteria.
