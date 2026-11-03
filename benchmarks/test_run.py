import json
import sys
import tempfile
import time
import unittest
from pathlib import Path, PureWindowsPath
from unittest.mock import patch

from run import (classify_test, localization_metrics, parse_run_journal, stable_result,
                 status_is_successful, summarize_test_run, summarize_agent_run,
                 _agent_command, _maven_command, _run_process)


class BenchmarkHelpersTest(unittest.TestCase):
    def test_distinguishes_test_failure_from_environment_failure(self):
        failed = "[ERROR] Tests run: 2, Failures: 1, Errors: 0\nBUILD FAILURE"
        environment = "Could not resolve dependencies\nBUILD FAILURE"
        fork_crash = ("[ERROR] Tests run: 2, Failures: 1, Errors: 0\n"
                      "The forked VM terminated without properly saying goodbye. VM crash or System.exit called?")
        failure_with_dependency_path = (
            "[ERROR] Tests run: 2, Failures: 1, Errors: 0\nBUILD FAILURE\n"
            "See C:\\work\\dependency-tools\\target\\surefire-reports")
        post_test_failure = "Tests run: 2, Failures: 0, Errors: 0\nCheckstyle failed\nBUILD FAILURE"

        self.assertEqual(classify_test(1, failed), "test_failure")
        self.assertEqual(classify_test(1, environment), "environment_failure")
        self.assertEqual(classify_test(1, fork_crash), "environment_failure")
        self.assertEqual(classify_test(1, failure_with_dependency_path), "test_failure")
        self.assertEqual(classify_test(1, post_test_failure), "environment_failure")
        self.assertEqual(classify_test(0, "Tests run: 2, Failures: 0, Errors: 0"), "passed")

    def test_localization_metrics_rank_unique_repository_files(self):
        visited = ["src/main/A.java", "src/main/B.java", "src/main/A.java"]
        metrics = localization_metrics(visited, ["src/main/B.java"])

        self.assertEqual(metrics, {"visited_files": visited[:2], "top1": False, "top3": True, "rank": 2})

    def test_parses_tool_paths_from_run_journal_in_order(self):
        events = [
            {"type": "step", "entry": "[复现] 步骤 1\n调用了工具：read_file\n参数 path = src/main/Ignored.java\n观察：文件"},
            {"type": "step", "entry": "[定位] 步骤 1\n思路：搜索\n调用了工具：search_code\n参数 query = Cache\n观察：src\\main\\A.java:15: cache"},
            {"type": "step", "entry": "[定位] 步骤 2\n思路：读取\n调用了工具：read_file\n参数 path = src/main/B.java\n观察：（src/main/B.java 第 1-10 行）"},
        ]
        with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as directory:
            journal = Path(directory) / "run.jsonl"
            journal.write_text("\n".join(json.dumps(event) for event in events), encoding="utf-8")

            self.assertEqual(parse_run_journal(journal), ["src/main/A.java", "src/main/B.java"])

    def test_result_identity_is_repeatable_and_excludes_wall_clock_fields(self):
        record = {"case_id": "M4", "status": "repaired", "elapsed_ms": 10, "run_id": "abc"}

        result = stable_result(record)

        self.assertEqual(result["case_id"], "M4")
        self.assertEqual(result["status"], "repaired")
        self.assertNotIn("run_id", result)
        self.assertNotIn("elapsed_ms", result)

    def test_baseline_reproduction_is_successful_in_baseline_only_mode(self):
        self.assertTrue(status_is_successful("baseline_reproduced", baseline_only=True))
        self.assertFalse(status_is_successful("baseline_reproduced", baseline_only=False))
        self.assertTrue(status_is_successful("repaired", baseline_only=False))

    def test_rocketmq_cases_disable_old_jacoco_agent_for_jdk17(self):
        dataset = json.loads((Path(__file__).with_name("dataset.json")).read_text(encoding="utf-8"))
        rocketmq_cases = [case for case in dataset["cases"] if case["repository"] == "rocketmq"]

        self.assertEqual({case["id"] for case in rocketmq_cases}, {"M3", "M4"})
        for case in rocketmq_cases:
            self.assertIn("-Djacoco.skip=true", case["test_args"])

    def test_environment_failure_summary_keeps_command_and_spawn_error(self):
        result = summarize_test_run(
            {"status": "environment_failure", "exit_code": None, "elapsed_ms": 0,
             "timed_out": False, "diagnostic": "[WinError 2] The system cannot find the file specified"},
            ["mvn", "test"], Path("D:/temporary/repo"))

        self.assertEqual(result["diagnostic"], "[WinError 2] The system cannot find the file specified")
        self.assertEqual(result["command"], ["mvn", "test"])
        self.assertEqual(result["working_directory"], str(Path("D:/temporary/repo")))

    def test_nonpassing_test_summary_keeps_output_tail(self):
        result = summarize_test_run(
            {"status": "test_failure", "exit_code": 1, "elapsed_ms": 100,
             "timed_out": False, "output": "build output\nExpectedMethodName failed"},
            ["mvn", "test"], Path("D:/temporary/repo"))

        self.assertIn("ExpectedMethodName failed", result["output_tail"])

    def test_agent_failure_summary_keeps_command_and_output_tail(self):
        result = summarize_agent_run(
            {"exit_code": 255, "elapsed_ms": 12, "timed_out": False,
             "output": "Maven could not start Agent main"},
            ["cmd.exe", "/d", "/c", "mvn", "exec:java"], Path("C:/javafix-agent"), include_output=True)

        self.assertEqual(result["exit_code"], 255)
        self.assertEqual(result["command"][-1], "exec:java")
        self.assertEqual(result["working_directory"], str(Path("C:/javafix-agent")))
        self.assertIn("could not start Agent", result["output_tail"])

    def test_windows_maven_launcher_uses_cmd_exe_for_mvn_cmd(self):
        project = Path(__file__).parent / "_no_mvnw_temp"
        with patch("run.os.name", "nt"):
            command = _maven_command(project, ["-B", "test"])

        self.assertEqual(command[:4], ["cmd.exe", "/d", "/c", "mvn"])
        self.assertEqual(command[4:], ["-B", "test"])

    def test_posix_maven_launcher_uses_mvn_executable(self):
        project = Path(__file__).parent / "_no_mvnw_temp"
        with patch("run.os.name", "posix"):
            command = _maven_command(project, ["-B", "test"])

        self.assertEqual(command, ["mvn", "-B", "test"])

    def test_windows_agent_launcher_quotes_project_path_and_uses_cmd_exe(self):
        agent_dir = Path(__file__).parent / "javafix agent"
        worktree = PureWindowsPath("D:/work tree/rocketmq")
        with patch("run.os.name", "nt"):
            command = _agent_command(agent_dir, worktree, "修复路由缺陷")

        self.assertEqual(command[:4], ["cmd.exe", "/d", "/c", "mvn"])
        self.assertEqual(command[4:6], ["-q", "exec:java"])
        self.assertIn('"D:\\\\work tree\\\\rocketmq"', command[-1])
        self.assertIn("修复路由缺陷", command[-1])

    def test_timed_out_process_is_terminated_and_reported(self):
        result = _run_process([sys.executable, "-c", "import time; time.sleep(5)"],
                              Path(__file__).parent, timeout_seconds=0.1)

        self.assertTrue(result["timed_out"])
        self.assertGreaterEqual(result["elapsed_ms"], 100)


if __name__ == "__main__":
    unittest.main()
