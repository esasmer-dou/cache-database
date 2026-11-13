#!/usr/bin/env python3
"""Deterministic fake-proc/cgroup contracts; run explicitly in EmaTech, not on the workstation."""
import csv
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location("resources", Path(__file__).with_name("collect-jmh-resources.py"))
R = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(R)


class Contracts(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="jmh-resource-contract-")
        self.root = Path(self.temporary.name)
        self.proc = self.root / "proc"
        (self.proc / "sys/kernel/random").mkdir(parents=True)
        (self.proc / "sys/kernel/random/boot_id").write_text("test-boot\n")
        self.pid, self.start = 123, 987654
        self.target = self.proc / str(self.pid)
        self.target.mkdir()
        self.stat = "123 (java ) with spaces) S 1 123 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 987654 20\n"
        (self.target / "stat").write_text(self.stat)
        (self.target / "status").write_text("Tgid:\t123\nVmRSS:\t100 kB\nVmHWM:\t900 kB\nRssAnon:\t80 kB\nRssFile:\t20 kB\n")
        self.cgroot = self.root / "target-root"
        self.cgroot.mkdir()
        (self.target / "root").symlink_to(self.cgroot, target_is_directory=True)
        self.group = self.cgroot / "sys/fs/cgroup"
        self.group.mkdir(parents=True)
        (self.target / "cgroup").write_text("0::/\n")
        (self.target / "mountinfo").write_text("1 0 0:1 /pod/container /sys/fs/cgroup ro - cgroup2 cgroup rw\n")
        self.put_v2(self.group)
        self.collector = None

    def tearDown(self):
        if self.collector:
            self.collector.close(1, "test cleanup")
        self.temporary.cleanup()

    def put_v2(self, path):
        path.mkdir(parents=True, exist_ok=True)
        for key, value in {"memory.current": "1024000", "memory.peak": "9000000", "memory.events": "high 1\noom 0\noom_kill 0\n",
                           "memory.stat": "anon 100\nfile 200\n", "cpu.stat": "nr_periods 10\nnr_throttled 2\nthrottled_usec 5\n"}.items():
            (path / key).write_text(value + "\n")

    def make_collector(self, **kwargs):
        self.collector = R.Collector(self.root / "output", "count", "baseline", proc=self.proc, **kwargs)
        return self.collector

    def event(self, sequence, kind, phase="MEASUREMENT", iteration=1, warmups=0):
        return f"{sequence}\t{kind}\t123\t987654\t{phase}\t{iteration}\tBench.count\tThroughput\ttenant=true\t{warmups}\t1\n"

    def feed(self, sequence, kind, **kwargs):
        c = self.collector
        c.event(c.events / "123-987654.events", self.event(sequence, kind, **kwargs))

    def test_identity_and_units(self):
        self.assertEqual(R.identity(self.proc, self.pid), self.start)
        values = R.rss_snapshot(self.proc, self.pid, self.start)
        self.assertEqual(values["VmRSS"], 102400)
        self.assertEqual(values["VmHWM"], 921600)
        self.assertNotIn("RssShmem", values)

    def test_pid_reuse_fails(self):
        (self.target / "stat").write_text(self.stat.replace("987654", "987655"))
        with self.assertRaisesRegex(R.ResourceError, "starttime"):
            R.rss_snapshot(self.proc, self.pid, self.start)

    def test_missing_rss_and_bad_units_fail(self):
        for value in ("VmRSS: 100 MB\nVmHWM: 900 kB\n", "Name: java\n"):
            (self.target / "status").write_text(value)
            with self.assertRaises(R.ResourceError):
                R.rss_snapshot(self.proc, self.pid, self.start)

    def test_v2_target_namespace_resolution(self):
        group = R.resolve_cgroup(self.proc, self.pid)
        self.assertEqual(group["version"], 2)
        self.assertEqual(group["memory"], self.target / "root/sys/fs/cgroup")
        values = R.cgroup_snapshot(group)
        self.assertEqual(values["usage_bytes"], 1024000)
        self.assertEqual(values["lifetime_peak_bytes"], 9000000)
        self.assertEqual(values["cpu_stat"]["throttled_usec"], 5)

    def test_v1_and_hybrid_use_actual_controller(self):
        (self.target / "cgroup").write_text("0::/\n5:memory:/pod\n4:cpu,cpuacct:/pod\n")
        (self.target / "mountinfo").write_text(
            "1 0 0:1 / /sys/fs/cgroup ro - cgroup2 cgroup rw\n"
            "2 0 0:2 / /sys/fs/cgroup/memory rw - cgroup cgroup rw,memory\n"
            "3 0 0:3 / /sys/fs/cgroup/cpu rw - cgroup cgroup rw,cpu,cpuacct\n")
        memory = self.group / "memory/pod"
        cpu = self.group / "cpu/pod"
        memory.mkdir(parents=True)
        cpu.mkdir(parents=True)
        for key, value in {"memory.usage_in_bytes": "1234", "memory.max_usage_in_bytes": "5678", "memory.failcnt": "2",
                           "memory.oom_control": "oom_kill_disable 0\nunder_oom 0\noom_kill 1", "memory.stat": "rss 900\ncache 334"}.items():
            (memory / key).write_text(value + "\n")
        (cpu / "cpu.stat").write_text("nr_throttled 3\nthrottled_time 1000\n")
        group = R.resolve_cgroup(self.proc, self.pid)
        values = R.cgroup_snapshot(group)
        self.assertEqual(group["version"], 1)
        self.assertEqual(values["usage_bytes"], 1234)
        self.assertEqual(values["events"]["memory.failcnt"], 2)
        self.assertEqual(values["cpu_stat"]["throttled_time"], 1000)

    def test_unavailable_peak_is_not_zero_or_rss(self):
        (self.group / "memory.peak").unlink()
        values = R.cgroup_snapshot(R.resolve_cgroup(self.proc, self.pid))
        self.assertIsNone(values["lifetime_peak_bytes"])
        self.assertIn("memory.peak:UNAVAILABLE", values["unavailable"])

    def test_phase_sampled_peak_separate_from_hwm_and_boundaries(self):
        c = self.make_collector()
        self.feed(1, "START", phase="WARMUP", warmups=1)
        self.feed(2, "END", phase="WARMUP", warmups=1)
        self.feed(3, "START", warmups=1)
        c.sample("periodic")
        (self.group / "memory.events").write_text("high 3\noom 0\noom_kill 0\n")
        (self.target / "status").write_text("Tgid: 123\nVmRSS: 200 kB\nVmHWM: 900 kB\n")
        self.feed(4, "END", warmups=1)
        c.require_complete()
        c.close(0)
        self.collector = None
        with (c.output / "phases.tsv").open() as stream:
            rows = list(csv.DictReader(stream, delimiter="\t"))
        self.assertEqual(rows[0]["sampled_peak_rss_bytes"], "UNAVAILABLE_NO_PERIODIC_SAMPLE")
        self.assertEqual(rows[1]["sampled_peak_rss_bytes"], "102400")
        self.assertEqual(rows[1]["boundary_peak_rss_bytes"], "204800")
        self.assertEqual(rows[1]["lifetime_vmhwm_max_bytes"], "921600")
        self.assertEqual(json.loads(rows[1]["cgroup_events_delta_json"])["high"], 2)
        manifest = json.loads((c.output / "manifest.json").read_text())
        self.assertEqual(manifest["redis"], "UNAVAILABLE_EXTERNAL_TARGET_NOT_SUPPLIED")
        self.assertIn("APPROXIMATE", manifest["rss"])
        self.assertIn("NOT_GUARANTEED_TRUE_RSS_LOWER_BOUND", manifest["sampled_peak"])
        self.assertIn("APPROXIMATE_LIFETIME", manifest["vmhwm"])
        self.assertIn("NOT_COLLECTED", manifest["smaps_rollup"])

    def test_unbalanced_duplicate_and_missing_phases_fail(self):
        c = self.make_collector()
        with self.assertRaisesRegex(R.ResourceError, "unbalanced"):
            self.feed(1, "END")
        self.feed(1, "START")
        with self.assertRaisesRegex(R.ResourceError, "sequence"):
            self.feed(1, "START")
        with self.assertRaisesRegex(R.ResourceError, "incomplete"):
            c.require_complete()

    def test_row_and_byte_limits_fail_closed(self):
        table = R.BoundedTsv(self.root / "small.tsv", ["x"], 1, 10)
        try:
            table.row({"x": "one"})
            with self.assertRaisesRegex(R.ResourceError, "row limit"):
                table.row({"x": "two"})
        finally:
            table.close()
        table = R.BoundedTsv(self.root / "bytes.tsv", ["x"], 100, 4)
        try:
            with self.assertRaisesRegex(R.ResourceError, "byte limit"):
                table.row({"x": "long"})
        finally:
            table.close()
        self.assertLessEqual((self.root / "bytes.tsv").stat().st_size, 4)

    def test_migration_and_counter_reset_fail(self):
        self.make_collector()
        self.feed(1, "START")
        (self.target / "cgroup").write_text("0::/other\n")
        with self.assertRaisesRegex(R.ResourceError, "migrated"):
            self.collector.sample("periodic")
        with self.assertRaisesRegex(R.ResourceError, "reset"):
            R.delta({"oom": 1}, {"oom": 0})

    def test_foreign_phase_process_is_rejected(self):
        c = self.make_collector()
        c.expected_session = 999
        with self.assertRaisesRegex(R.ResourceError, "owned JMH fork session"):
            self.feed(1, "START")

    def test_owned_fork_session_is_accepted(self):
        c = self.make_collector()
        c.expected_session = 999
        (self.target / "stat").write_text(self.stat.replace("S 1 123 3 ", "S 999 999 999 "))
        self.feed(1, "START")
        self.feed(2, "END")
        c.require_complete()

    def test_no_process_sweep_or_du_and_runner_hooks(self):
        scripts = Path(__file__).parent
        collector = scripts.joinpath("collect-jmh-resources.py").read_text()
        self.assertNotIn('glob("[0-9]', collector)
        self.assertNotIn('"du"', collector)
        helper = scripts.joinpath("jmh-instrumentation.sh").read_text()
        collector_call = 'jmh_resources_run "$@" || return $?'
        writer_call = '"$resources_python" -B "$script_dir/write-jmh-instrumentation.py"'
        self.assertIn("jmh_instrumented_run() {", helper)
        self.assertIn(collector_call, helper)
        self.assertIn(writer_call, helper)
        self.assertLess(helper.index(collector_call), helper.index(writer_call))
        self.assertIn('--resources "$resources_output/resources/$instrumentation_case-$instrumentation_side"', helper)
        self.assertIn('--sidecar-launcher "$script_dir/jmh-instrumentation.sh" -- "$@"', helper)
        for runner in ("run-paired.sh", "run-features-paired.sh", "run-coalescing-paired.sh"):
            source = scripts.joinpath(runner).read_text()
            self.assertIn("jmh_resources_init", source)
            self.assertIn('. "$script_dir/jmh-instrumentation.sh"', source)
            self.assertIn("jmh_instrumented_run ", source)
            self.assertIn('"$resources_profiler" -prof gc', source)

    def test_owned_group_cleanup(self):
        child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)"], start_new_session=True)
        R.stop_group(child)
        self.assertIsNotNone(child.returncode)
        with self.assertRaises(ProcessLookupError):
            os.kill(child.pid, 0)

    def test_ci_callers_prepare_and_export_before_paired_runners(self):
        root = Path(__file__).resolve().parents[3]
        for caller in (".github/workflows/performance-gate.yml",
                       ".github/actions/cachedb-v019-performance/action.yml"):
            with self.subTest(caller=caller):
                source = root.joinpath(caller).read_text()
                first_run = source.index("sh tools/benchmarks/redis/run-")
                self.assertLess(source.index("prepare-jmh-resources.sh"), first_run)
                self.assertLess(source.index("CACHEDB_BENCH_RESOURCE_CLASSES="), first_run)
                self.assertIn("python3", source)
                self.assertIn("resource-selfcheck", source)

    def test_timeout_records_failure_and_cleans_owned_command(self):
        c = self.make_collector()
        for pid in (124, 125):
            target = self.proc / str(pid)
            target.mkdir()
            (target / "stat").write_text(self.stat.replace("123", str(pid)))
            (target / "status").write_text(f"Tgid: {pid}\nVmRSS: 100 kB\nVmHWM: 900 kB\n")
            (target / "cgroup").write_text("0::/\n")
            (target / "mountinfo").write_text((self.target / "mountinfo").read_text())
            (target / "root").symlink_to(self.cgroot, target_is_directory=True)
        args = SimpleNamespace(output=c.output, case="count", side="baseline", interval_ms=250,
                               max_samples=100, max_bytes=65536, timeout_seconds=1, command=["never-executed"])
        with patch.object(R, "Collector", return_value=c), patch.object(R.os, "getpid", return_value=124), \
                patch.object(R.subprocess, "Popen") as launch, patch.object(R, "stop_group") as cleanup, \
                patch.object(R.time, "monotonic", side_effect=[0, 0, 2]), patch.dict(os.environ, {}, clear=True):
            launch.return_value.pid = 125
            self.assertEqual(R.run(args), 1)
            cleanup.assert_called_once_with(launch.return_value)
        self.collector = None
        status = json.loads((c.output / "status.json").read_text())
        self.assertEqual(status["status"], "FAILED_OR_UNAVAILABLE")
        self.assertIn("timeout", status["error"])

    def test_observer_separate_and_fixed_sample_io_budget(self):
        c = self.make_collector()
        for role, pid in (("observer", 124), ("controller", 125)):
            target = self.proc / str(pid)
            target.mkdir()
            (target / "stat").write_text(self.stat.replace("123", str(pid)))
            # Deliberately much larger than the fork; it must not inflate fork RSS summary.
            (target / "status").write_text(f"Tgid: {pid}\nVmRSS: 5000 kB\nVmHWM: 9000 kB\n")
            (target / "cgroup").write_text("0::/\n")
            (target / "mountinfo").write_text((self.target / "mountinfo").read_text())
            (target / "root").symlink_to(self.cgroot, target_is_directory=True)
            c.register(role, pid, self.start)
        self.feed(1, "START")
        with patch.object(R, "read_text", wraps=R.read_text) as reads:
            c.sample("periodic")
            # Three process identities/statuses, one target membership, five cgroup files.
            self.assertLessEqual(reads.call_count, 16)
        self.feed(2, "END")
        c.close(0)
        self.collector = None
        with (c.output / "process.tsv").open() as stream:
            rows = list(csv.DictReader(stream, delimiter="\t"))
        self.assertEqual({row["role"] for row in rows}, {"fork", "controller", "observer"})
        with (c.output / "phases.tsv").open() as stream:
            summary = list(csv.DictReader(stream, delimiter="\t"))
        self.assertEqual(summary[0]["sampled_peak_rss_bytes"], "102400")
        status = json.loads((c.output / "status.json").read_text())
        self.assertGreaterEqual(status["observer_process_cpu_ns"], 0)
        self.assertIn("NOT_SUBTRACTED", status["overhead_scope"])


if __name__ == "__main__":
    unittest.main()
