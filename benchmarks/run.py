#!/usr/bin/env python3
"""Run the pinned JavaFixAgent bug benchmark using only Python's standard library."""

from __future__ import annotations

import argparse
import json
import os
import re
import signal
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DATASET = Path(__file__).with_name("dataset.json")
FAILED_SUMMARY = re.compile(r"Tests run:\s*[1-9][0-9]*,\s*Failures:\s*[1-9][0-9]*|Tests run:\s*[1-9][0-9]*,\s*Failures:\s*[0-9]+,\s*Errors:\s*[1-9][0-9]*")
PASSED_SUMMARY = re.compile(r"Tests run:\s*[1-9][0-9]*,\s*Failures:\s*0,\s*Errors:\s*0")
ENVIRONMENT_ERROR = re.compile(
    r"Could not resolve dependencies|Could not transfer artifact|PluginResolutionException|"
    r"Unsupported class file major version|UnsupportedClassVersionError|requires Maven version|"
    r"JAVA_HOME.*(?:not defined|incorrect)|BUILD FAILURE.*(?:compilation|dependency)",
    re.IGNORECASE | re.DOTALL,
)
JAVA_FILE = re.compile(r"(?<![\w.-])([\w./\\$-]+\.java)(?=[:#])")


def classify_test(exit_code: int, output: str) -> str:
    if exit_code == 0:
        return "passed" if PASSED_SUMMARY.search(output) else "environment_failure"
    if FAILED_SUMMARY.search(output):
        return "test_failure"
    if PASSED_SUMMARY.search(output):
        return "environment_failure"
    if ENVIRONMENT_ERROR.search(output) or "Tests run:" not in output:
        return "environment_failure"
    return "test_failure"


def parse_run_journal(path: Path) -> list[str]:
    """Return unique Java source paths in the order JavaFixAgent inspected them."""
    visited: list[str] = []
    if not path.is_file():
        return visited
    for line in path.read_text(encoding="utf-8").splitlines():
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            continue
        if event.get("type") != "step":
            continue
        entry = event.get("entry", "")
        if not entry.startswith("[定位]"):
            continue
        if "调用了工具：read_file" in entry:
            match = re.search(r"参数 path = ([^\r\n]+)", entry)
            if match:
                _append_java_path(visited, match.group(1))
        if "调用了工具：search_code" in entry:
            observation = entry.split("观察：", 1)[-1]
            for match in JAVA_FILE.finditer(observation):
                _append_java_path(visited, match.group(1))
    return visited


def _append_java_path(paths: list[str], value: str) -> None:
    normalized = value.strip().strip("`'\"").replace("\\", "/")
    if normalized.endswith(".java") and normalized not in paths:
        paths.append(normalized)


def localization_metrics(visited_files: list[str], key_files: list[str]) -> dict:
    unique: list[str] = []
    for path in visited_files:
        normalized = path.replace("\\", "/").lstrip("./")
        if normalized.endswith(".java") and normalized not in unique:
            unique.append(normalized)
    keys = {path.replace("\\", "/").lstrip("./") for path in key_files}
    hit = next((index for index, path in enumerate(unique, start=1) if path in keys), None)
    return {"visited_files": unique, "top1": hit is not None and hit <= 1,
            "top3": hit is not None and hit <= 3, "rank": hit}


def stable_result(record: dict) -> dict:
    """Return the stable case outcome fields, excluding run-specific measurements."""
    return {key: record[key] for key in ("dataset_version", "case_id", "status") if key in record}


def status_is_successful(status: str, baseline_only: bool) -> bool:
    return status == "repaired" or (baseline_only and status == "baseline_reproduced")


def _git(repository: Path, *args: str, timeout: int = 60) -> subprocess.CompletedProcess:
    command = ["git", "-c", f"safe.directory={repository}", "-C", str(repository), *args]
    return subprocess.run(command, text=True, encoding="utf-8", errors="replace",
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)


def _maven_command(project: Path, args: list[str]) -> list[str]:
    if os.name == "nt":
        wrapper = project / "mvnw.cmd"
        if wrapper.is_file():
            return ["cmd.exe", "/d", "/c", str(wrapper), *args]
        return ["cmd.exe", "/d", "/c", "mvn", *args]
    else:
        wrapper = project / "mvnw"
        if wrapper.is_file():
            return [str(wrapper), *args]
    return ["mvn", *args]


def _run_process(command: list[str], cwd: Path, timeout_seconds: int) -> dict:
    started = time.monotonic()
    creationflags = subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0
    process = subprocess.Popen(command, cwd=cwd, text=True, encoding="utf-8", errors="replace",
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               start_new_session=os.name != "nt", creationflags=creationflags)
    try:
        output, _ = process.communicate(timeout=timeout_seconds)
        timed_out = False
    except subprocess.TimeoutExpired:
        if os.name == "nt":
            try:
                killed = subprocess.run(["taskkill", "/F", "/T", "/PID", str(process.pid)],
                                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                         timeout=15).returncode == 0
                if not killed:
                    process.kill()
            except (OSError, subprocess.SubprocessError):
                process.kill()
        else:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
        output, _ = process.communicate()
        timed_out = True
    return {"exit_code": process.returncode, "timed_out": timed_out,
            "elapsed_ms": int((time.monotonic() - started) * 1000), "output": output or ""}


def _run(command: list[str], cwd: Path, timeout_seconds: int) -> dict:
    try:
        result = _run_process(command, cwd, timeout_seconds)
        result["status"] = "environment_failure" if result["timed_out"] else classify_test(
            result["exit_code"], result["output"])
        return result
    except (OSError, subprocess.SubprocessError) as error:
        return {"exit_code": None, "status": "environment_failure", "timed_out": False,
                "elapsed_ms": 0, "output": "", "diagnostic": str(error)}


def summarize_test_run(result: dict, command: list[str], cwd: Path) -> dict:
    summary = {key: result[key] for key in ("status", "exit_code", "elapsed_ms", "timed_out")}
    summary["command"] = command
    summary["working_directory"] = str(cwd)
    if result.get("diagnostic"):
        summary["diagnostic"] = result["diagnostic"]
    if result["status"] == "environment_failure" and result.get("output"):
        summary["output_tail"] = result["output"][-2_000:]
    return summary


def _agent_metrics(output: str) -> dict:
    values = {}
    model = re.search(r"模型：([^\r\n]+)", output)
    if model:
        values["model"] = model.group(1).strip()
    for key, pattern in {
        "steps": r"步数：([0-9]+)",
        "agent_elapsed_ms": r"耗时：([0-9]+) ms",
        "llm_calls": r"token：调用 ([0-9]+) 次",
        "prompt_tokens": r"token：调用 [0-9]+ 次 / 输入 ([0-9]+)",
        "completion_tokens": r"token：调用 [0-9]+ 次 / 输入 [0-9]+ / 输出 ([0-9]+)",
        "total_tokens": r"token：调用 [0-9]+ 次 / 输入 [0-9]+ / 输出 [0-9]+ / 合计 ([0-9]+) tokens",
    }.items():
        match = re.search(pattern, output)
        if match:
            values[key] = int(match.group(1))
    journal = re.search(r"运行记录：(.+\.jsonl)", output)
    if journal:
        values["journal"] = Path(journal.group(1).strip())
    return values


def _quote_exec_argument(value: str) -> str:
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


def _agent_command(agent_dir: Path, worktree: Path, description: str) -> list[str]:
    exec_args = f"{_quote_exec_argument(str(worktree))} {_quote_exec_argument(description)}"
    return _maven_command(agent_dir, ["-q", "exec:java", f"-Dexec.args={exec_args}"])


def run_case(case: dict, dataset_version: str, repos_dir: Path, agent_dir: Path, work_dir: Path,
             timeout_seconds: int, baseline_only: bool = False) -> dict:
    record = {"dataset_version": dataset_version, "case_id": case["id"], "name": case["name"],
              "status": "environment_failure", "upstream": case["upstream"],
              "reference": case["reference"], "bug_revision": case["bug_revision"],
              "fix_revision": case["fix_revision"], "started_at": datetime.now(timezone.utc).isoformat()}
    repository = (repos_dir / case["repository"]).resolve()
    if not repository.is_dir():
        record["diagnostic"] = f"repository directory not found: {case['repository']}"
        return record

    try:
        identity = _git(repository, "rev-parse", "--show-toplevel")
        revision = _git(repository, "cat-file", "-e", f"{case['bug_revision']}^{{commit}}")
    except (OSError, subprocess.SubprocessError) as error:
        record["diagnostic"] = str(error)
        return record
    if identity.returncode or revision.returncode:
        record["diagnostic"] = (identity.stdout if identity.returncode else revision.stdout).strip()
        return record

    try:
        work_dir.mkdir(parents=True, exist_ok=True)
    except OSError as error:
        record["diagnostic"] = str(error)
        return record
    with tempfile.TemporaryDirectory(prefix=f"{case['id'].lower()}-", dir=work_dir) as temporary:
        worktree = Path(temporary) / "repo"
        try:
            added = _git(repository, "worktree", "add", "--detach", str(worktree), case["bug_revision"], timeout=180)
        except (OSError, subprocess.SubprocessError) as error:
            record["diagnostic"] = str(error)
            return record
        if added.returncode:
            record["diagnostic"] = added.stdout.strip()
            return record
        try:
            fixture = _git(repository, "show", f"{case['fix_revision']}:{case['fixture']}", timeout=60)
            if fixture.returncode:
                record["diagnostic"] = f"cannot read regression fixture: {fixture.stdout.strip()}"
                return record
            fixture_path = worktree / case["fixture"]
            fixture_path.parent.mkdir(parents=True, exist_ok=True)
            fixture_path.write_text(fixture.stdout, encoding="utf-8", newline="")

            baseline_command = _maven_command(worktree, case["test_args"])
            baseline = _run(baseline_command, worktree, timeout_seconds)
            record["baseline"] = summarize_test_run(baseline, baseline_command, worktree)
            record["baseline_signature_matched"] = case["expected_failure"] in baseline["output"]
            if baseline["status"] == "environment_failure":
                record["diagnostic"] = "baseline test could not produce a test result"
                detail = baseline.get("diagnostic") or record["baseline"].get("output_tail")
                if detail:
                    record["diagnostic"] += f": {detail[-1_000:]}"
                return record
            if baseline["status"] != "test_failure" or not record["baseline_signature_matched"]:
                record["status"] = "baseline_not_reproduced"
                record["diagnostic"] = "the pinned baseline did not show the expected failing test"
                return record
            if baseline_only:
                record["status"] = "baseline_reproduced"
                return record

            command = _agent_command(agent_dir, worktree, case["description"])
            agent_started = time.monotonic()
            agent = _run_process(command, agent_dir, timeout_seconds)
            agent_output = agent["output"]
            record["agent_process_exit_code"] = agent["exit_code"]
            if agent["timed_out"]:
                record["status"] = "environment_failure"
                record["diagnostic"] = "JavaFixAgent process timed out"
                return record
            metrics = _agent_metrics(agent_output)
            journal = metrics.pop("journal", None)
            record.update(metrics)
            if "=== Agent 的结论 ===" not in agent_output:
                record["status"] = "environment_failure"
                record["diagnostic"] = "JavaFixAgent did not start a repair run; check Maven and model configuration"
                return record
            if journal:
                record["localization"] = localization_metrics(
                    parse_run_journal(journal), case["key_files"])
            record["agent_wall_elapsed_ms"] = int((time.monotonic() - agent_started) * 1000)

            final_command = _maven_command(worktree, case["test_args"])
            final = _run(final_command, worktree, timeout_seconds)
            record["final_test"] = summarize_test_run(final, final_command, worktree)
            if final["status"] == "passed":
                record["status"] = "repaired"
            elif final["status"] == "test_failure":
                record["status"] = "repair_failed"
            else:
                record["status"] = "environment_failure"
                record["diagnostic"] = "post-repair test could not produce a test result"
                detail = final.get("diagnostic") or record["final_test"].get("output_tail")
                if detail:
                    record["diagnostic"] += f": {detail[-1_000:]}"
            return record
        except (OSError, subprocess.SubprocessError) as error:
            record["diagnostic"] = str(error)
            return record
        finally:
            try:
                removed = _git(repository, "worktree", "remove", "--force", str(worktree), timeout=180)
                if removed.returncode:
                    record["cleanup_warning"] = removed.stdout.strip()
            except (OSError, subprocess.SubprocessError) as error:
                record["cleanup_warning"] = str(error)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repos-dir", type=Path, required=True,
                        help="directory containing the local pinned repositories (for example D:\\yangben\\_repos)")
    parser.add_argument("--case", help="run one case ID, such as M4; default runs every case")
    parser.add_argument("--agent-dir", type=Path, default=ROOT,
                        help="JavaFixAgent checkout containing pom.xml; default is this checkout")
    parser.add_argument("--work-dir", type=Path, default=Path(__file__).with_name("worktrees"),
                        help="parent directory for disposable worktrees; default benchmarks/worktrees")
    parser.add_argument("--output", type=Path, default=Path("benchmarks/results/results.jsonl"),
                        help="append machine-readable JSONL results here")
    parser.add_argument("--timeout-seconds", type=int, default=3600,
                        help="timeout for each Maven or agent process; default 3600")
    parser.add_argument("--baseline-only", action="store_true",
                        help="verify the pinned failing test without invoking the model")
    args = parser.parse_args(argv)
    dataset = json.loads(DATASET.read_text(encoding="utf-8"))
    cases = dataset["cases"]
    if args.case:
        cases = [case for case in cases if case["id"].casefold() == args.case.casefold()]
        if not cases:
            parser.error(f"unknown case ID: {args.case}")
    if not (args.agent_dir / "pom.xml").is_file():
        parser.error(f"JavaFixAgent pom.xml not found under {args.agent_dir}")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    failed = False
    with args.output.open("a", encoding="utf-8", newline="\n") as output:
        for case in cases:
            try:
                record = run_case(case, dataset["version"], args.repos_dir.resolve(), args.agent_dir.resolve(),
                                  args.work_dir.resolve(), args.timeout_seconds, args.baseline_only)
            except (OSError, subprocess.SubprocessError) as error:
                record = {"dataset_version": dataset["version"], "case_id": case["id"],
                          "name": case["name"], "status": "environment_failure", "diagnostic": str(error)}
            record["finished_at"] = datetime.now(timezone.utc).isoformat()
            record["identity"] = stable_result(record)
            output.write(json.dumps(record, ensure_ascii=False) + "\n")
            output.flush()
            print(f"{case['id']}: {record['status']}" +
                  (f" — {record['diagnostic']}" if record.get("diagnostic") else ""))
            failed |= not status_is_successful(record["status"], args.baseline_only)
    print(f"JSONL results: {args.output.resolve()}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
