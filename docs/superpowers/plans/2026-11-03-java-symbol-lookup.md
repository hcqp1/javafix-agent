# Java Symbol Lookup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add bounded Java AST lookup for a target type and method, and guide the localization phase to use it before text-search fallback.

**Architecture:** Implement `find_references` as a repository-bounded ProjectTool using the existing JavaParser dependency. It reports target method declarations and syntactic call/reference candidates in stable file order, with source locations, result limits, and parse errors. It does not claim full semantic resolution when JavaParser lacks symbol/type resolution.

**Tech Stack:** Java 17, JavaParser 3.26.3, JUnit 5.

## Global Constraints

- Add no production dependency and no external symbol solver.
- Resolve all optional scope paths under the selected project root.
- Keep existing `search_code` behavior and expose its use as a fallback when parsing fails or symbols remain unresolved.
- Bound files read, per-file size, result count, and final tool output.
- Report syntactic candidates distinctly from declarations; do not claim exact binding for unresolved variable receivers.

---

### Task 1: Implement and test `find_references`

**Files:**
- Create: `src/main/java/com/javafix/agent/tool/FindReferencesTool.java`
- Create: `src/test/java/com/javafix/agent/tool/FindReferencesToolTest.java`
- Modify: `src/main/java/com/javafix/agent/Main.java`

**Interfaces:**
- Tool name: `find_references`.
- Required arguments: `type` (qualified or simple Java type name), `method`.
- Optional arguments: `path` (repository-relative Java file/directory), `max_results` (1–30, default 20).
- Output: declaration/reference candidates with repository-relative file and line, plus parse/skipped-file diagnostics.

- [x] Test class and method declarations, overloads, typed variable calls, `this`/type-qualified calls, method references, parse errors beside valid results, repository scope, result bounds, and path traversal rejection.
- [x] Parse Java 17 source with JavaParser, derive package/nested type names, collect declarations and syntactic reference candidates, and preserve unresolved-reference caveats.
- [x] Register the tool in `Main.createTools` and include it in the localization instructions before `search_code` fallback.

### Task 2: Verify and document

**Files:**
- Modify: `README.md`

- [x] Run `FindReferencesToolTest` and the full Java test suite.
- [x] Document behavior and limitations, including unresolved dynamic/semantic bindings and bounded scan scope.
- [x] Inspect `git diff --check` and ensure the previous benchmark changes remain intact; implementation and review fixes are committed. Full test suite passes.
