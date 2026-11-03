import json
import tempfile
import unittest
from pathlib import Path

from run import classify_test, localization_metrics, parse_run_journal, stable_result


class BenchmarkHelpersTest(unittest.TestCase):
    def test_distinguishes_test_failure_from_environment_failure(self):
        failed = "[ERROR] Tests run: 2, Failures: 1, Errors: 0\nBUILD FAILURE"
        environment = "Could not resolve dependencies\nBUILD FAILURE"
        post_test_failure = "Tests run: 2, Failures: 0, Errors: 0\nCheckstyle failed\nBUILD FAILURE"

        self.assertEqual(classify_test(1, failed), "test_failure")
        self.assertEqual(classify_test(1, environment), "environment_failure")
        self.assertEqual(classify_test(1, post_test_failure), "environment_failure")
        self.assertEqual(classify_test(0, "Tests run: 2, Failures: 0, Errors: 0"), "passed")

    def test_localization_metrics_rank_unique_repository_files(self):
        visited = ["src/main/A.java", "src/main/B.java", "src/main/A.java"]
        metrics = localization_metrics(visited, ["src/main/B.java"])

        self.assertEqual(metrics, {"visited_files": visited[:2], "top1": False, "top3": True, "rank": 2})

    def test_parses_tool_paths_from_run_journal_in_order(self):
        events = [
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


if __name__ == "__main__":
    unittest.main()
