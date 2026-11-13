#!/usr/bin/env python3
"""Synthetic writer controls; never starts a JVM, sampler, or benchmark."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("jmh_instrumentation", Path(__file__).with_name("write-jmh-instrumentation.py"))
WRITER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(WRITER)


class InstrumentationWriterTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.raw = self.root / "raw.json"
        self.raw.write_text(json.dumps([{"benchmark": "fixture.Case", "mode": "thrpt", "threads": 1,
                                         "params": {"tenant": "true", "cache": "false"}}]), encoding="utf-8")
        self.profiler = "com.reactor.cachedb.benchmarks.redis.JmhResourcePhaseProfiler"
        self.classfile = self.root / (self.profiler.replace(".", "/") + ".class")
        self.classfile.parent.mkdir(parents=True)
        self.classfile.write_bytes(b"synthetic resource helper bytes, not a real class")
        self.collector, self.launcher, self.sidecar_launcher = [self.root / name for name in ("collector.py", "launcher.sh", "sidecar.sh")]
        for path in (self.collector, self.launcher, self.sidecar_launcher):
            path.write_bytes(path.name.encode("ascii"))
        self.command = ["java", "-cp", str(self.root), "org.openjdk.jmh.Main", "fixture.Case",
                        "-prof", self.profiler, "-prof", "gc", "-rff", str(self.raw)]
        self.manifest = {"schema": "JMH_RESOURCES_V1", "command": self.command, "interval_ms": 500,
                         "max_rows_per_file": 100, "max_bytes_per_file": 8192, "max_forks": 128,
                         "redis": "UNAVAILABLE_EXTERNAL_TARGET_NOT_SUPPLIED"}
        self.status = {"schema": "JMH_RESOURCES_V1", "status": "COMPLETE_NOT_ACCEPTED", "command_exit": 0, "error": None}

    def write(self):
        (self.root / "manifest.json").write_text(json.dumps(self.manifest), encoding="utf-8")
        (self.root / "status.json").write_text(json.dumps(self.status), encoding="utf-8")
        WRITER.write_sidecar(self.root, self.collector, self.launcher, self.sidecar_launcher, self.command)
        return json.loads(Path(str(self.raw) + ".instrumentation").read_text(encoding="utf-8"))

    def test_actual_command_order_helper_hash_and_observed_settings(self):
        before = self.raw.read_bytes()
        result = self.write()
        self.assertEqual(hashlib.sha256(before).hexdigest(), result["rawSha256"])
        self.assertEqual(before, self.raw.read_bytes())
        self.assertEqual("fixture.Case|thrpt|threads=1|cache=false,tenant=true", result["cases"][0]["key"])
        contract = result["cases"][0]["contract"]
        self.assertEqual([self.profiler, "gc"], contract["controllerProfilers"])
        self.assertEqual(500, contract["sampler"]["intervalMs"])
        helpers = {item["identity"]: item["sha256"] for item in contract["helpers"]}
        self.assertEqual(hashlib.sha256(self.classfile.read_bytes()).hexdigest(), helpers["profiler:" + self.profiler])
        self.assertEqual(hashlib.sha256(self.collector.read_bytes()).hexdigest(), helpers["resource-collector"])
        self.assertFalse(list(self.root.glob("*.instrumentation.json")))

    def test_failed_collection_cannot_publish_contract(self):
        self.status["status"] = "FAILED_OR_UNAVAILABLE"
        with self.assertRaisesRegex(ValueError, "successful collector command"):
            self.write()
        self.assertFalse(Path(str(self.raw) + ".instrumentation").exists())

    def test_manifest_must_contain_exact_actual_command(self):
        self.manifest["command"] = list(reversed(self.command))
        with self.assertRaisesRegex(ValueError, "successful collector command"):
            self.write()

    def test_missing_active_helper_fails_closed(self):
        self.classfile.unlink()
        with self.assertRaisesRegex(ValueError, "profiler class is absent"):
            self.write()

    def test_existing_contract_is_not_overwritten(self):
        self.write()
        with self.assertRaises(FileExistsError):
            self.write()


if __name__ == "__main__":
    unittest.main()
