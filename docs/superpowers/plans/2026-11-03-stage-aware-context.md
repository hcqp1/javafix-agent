# Stage-Aware Context Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep AgentLoop prompts bounded and focused on the active repair phase while retaining earlier evidence and same-task continuity.

**Architecture:** Add a stateless context assembler that selects prior phase conclusions, recent active-phase actions, recent failures, relevant same-task observations, and recent failed repair summaries. It ranks evidence using recency and identifier overlap, caps each entry and the total context, and receives only the current task's data. Replace AgentLoop's full transcript replay with this assembled context.

**Tech Stack:** Java 17, JUnit 5; no new dependency or storage layer.

## Global Constraints

- Never load another task's events; use only the same-task context already supplied by `ChatSession`.
- Keep the user's current bug description in every prompt.
- Preserve the active phase's recent tool results, previous phase conclusions, and the last two failed repair summaries.
- Bound prompt-history context to 10,000 characters and one entry to 1,800 characters.

---

### Task 1: Add bounded phase-aware context assembly

**Files:**
- Create: `src/main/java/com/javafix/agent/core/PromptContextAssembler.java`
- Create: `src/test/java/com/javafix/agent/core/PromptContextAssemblerTest.java`
- Modify: `src/main/java/com/javafix/agent/core/AgentLoop.java`

**Interfaces:**
- `String assemble(Phase phase, String symptom, List<String> transcript, List<String> priorTaskContext, List<String> failedAttempts)`.
- Output includes selected evidence sections; input data is read-only and no context is retained between tasks.

- [x] Test phase-specific inclusion, failure prioritization, lexical relevance, total/per-entry bounds, and task isolation.
- [x] Implement deterministic selection, relevance-ranked rendering, and explicit truncation markers.
- [x] Use assembled context in `AgentLoop.buildPrompt` instead of replaying all transcript and prior-task events.

### Task 2: Verify and document

**Files:**
- Modify: `README.md`

- [x] Run focused tests and the full Java test suite (207 tests, 0 failures/errors, 8 skipped).
- [x] Document phase selection, context limits, and same-task isolation.
- [x] Run `git diff --check`, complete independent code review, commit, and push the stage.
