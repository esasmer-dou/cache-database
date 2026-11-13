#!/usr/bin/env python3
"""Linux JMH iteration-envelope RSS sampler. Standard library only; never launches Redis.

VmRSS and VmHWM are approximate /proc status values, not smaps/PSS or exact
peaks. A sampled maximum can miss spikes and is not a guaranteed lower bound
on true RSS. VmHWM and cgroup peak counters are lifetime observations, never
measurement-window peaks. No expensive smaps_rollup reads are performed.
"""

import argparse
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time


class ResourceError(RuntimeError):
    pass


def read_text(path, limit=65536):
    with Path(path).open("r", encoding="utf-8") as stream:
        value = stream.read(limit + 1)
    if len(value) > limit:
        raise ResourceError("input size bound exceeded: " + str(path))
    return value


def stat_fields(value):
    end = value.rfind(")")
    fields = value[end + 1:].split()
    if end < 0 or len(fields) < 20 or not fields[19].isdigit():
        raise ResourceError("malformed /proc PID stat")
    return fields


def identity(proc, pid):
    return int(stat_fields(read_text(proc / str(pid) / "stat"))[19])


def rss_snapshot(proc, pid, start):
    if identity(proc, pid) != start:
        raise ResourceError("PID starttime changed: " + str(pid))
    values = {}
    leader = False
    status = read_text(proc / str(pid) / "status")
    for line in status.splitlines():
        key, _, value = line.partition(":")
        if key in ("VmRSS", "VmHWM", "RssAnon", "RssFile", "RssShmem"):
            words = value.split()
            if len(words) != 2 or words[1] != "kB" or not words[0].isdigit():
                raise ResourceError("invalid /proc memory unit: " + key)
            values[key] = int(words[0]) * 1024
        elif key == "Tgid":
            if value.strip() != str(pid):
                raise ResourceError("RSS target is not a process leader")
            leader = True
    if not leader or not {"VmRSS", "VmHWM"}.issubset(values):
        raise ResourceError("process RSS/HWM unavailable: " + str(pid))
    if identity(proc, pid) != start:
        raise ResourceError("PID reused during RSS read: " + str(pid))
    return values


def unescape_mount(value):
    return re.sub(r"\\([0-7]{3})", lambda match: chr(int(match[1], 8)), value)


def resolve_cgroup(proc, pid):
    """Resolve the named target's namespace/mounts, never substitute /proc/self."""
    memberships = []
    cgroup_record = read_text(proc / str(pid) / "cgroup")
    for row in cgroup_record.splitlines():
        number, controllers, path = row.split(":", 2)
        memberships.append((number, set(controllers.split(",")), path))
    mounts = []
    for row in read_text(proc / str(pid) / "mountinfo").splitlines():
        left, sep, right = row.partition(" - ")
        a, b = left.split(), right.split()
        if sep and len(a) >= 6 and len(b) >= 3 and b[0] in ("cgroup", "cgroup2"):
            mounts.append((b[0], unescape_mount(a[3]), unescape_mount(a[4]), set(b[2].split(","))))

    def locate(controller):
        candidates = []
        legacy = any(controller in controllers for _, controllers, _ in memberships)
        for number, controllers, path in memberships:
            for fs, root, mount, options in mounts:
                unified = fs == "cgroup2" and number == "0"
                if unified and legacy:
                    continue
                if not unified and not (fs == "cgroup" and controller in controllers and controller in options):
                    continue
                if path == "/" or path == root:
                    suffix = ""
                elif root == "/":
                    suffix = path.lstrip("/")
                elif path.startswith(root.rstrip("/") + "/"):
                    suffix = path[len(root):].lstrip("/")
                else:
                    continue
                if ".." in Path(suffix).parts:
                    raise ResourceError("unsafe cgroup membership")
                # /proc/PID/root handles targets in another visible mount namespace.
                target = proc / str(pid) / "root" / mount.lstrip("/") / suffix
                candidates.append((2 if unified else 1, target, path, mount, root))
        if not candidates:
            raise ResourceError("target cgroup controller unavailable: " + controller)
        # Prefer the longest matching mount root; never guess the observer's cgroup.
        return max(candidates, key=lambda item: len(item[4]))

    memory = locate("memory")
    cpu = locate("cpu")
    return {"version": memory[0], "cpu_version": cpu[0], "memory": memory[1], "cpu": cpu[1],
            "membership": memory[2], "mount": memory[3], "mount_root": memory[4], "cgroup_record": cgroup_record}


def counters(value):
    result = {}
    for row in value.splitlines():
        words = row.split()
        if len(words) != 2 or not words[1].isdigit() or words[0] in result:
            raise ResourceError("malformed cgroup counters")
        result[words[0]] = int(words[1])
    return result


def cgroup_snapshot(group):
    memory, cpu = group["memory"], group["cpu"]
    version = group["version"]
    usage_file = "memory.current" if version == 2 else "memory.usage_in_bytes"
    peak_file = "memory.peak" if version == 2 else "memory.max_usage_in_bytes"
    usage = read_text(memory / usage_file).strip()
    if not usage.isdigit():
        raise ResourceError("cgroup memory usage unavailable")
    missing = []

    def optional(name, directory=memory, numeric=False):
        try:
            value = read_text(directory / name).strip()
            if numeric:
                if not value.isdigit():
                    raise ResourceError("invalid cgroup numeric counter: " + name)
                return int(value)
            return counters(value)
        except (FileNotFoundError, PermissionError):
            missing.append(name + ":UNAVAILABLE")
            return None

    peak = optional(peak_file, numeric=True)
    if version == 2:
        events = optional("memory.events")
    else:
        failcnt = optional("memory.failcnt", numeric=True)
        oom = optional("memory.oom_control")
        events = {"memory.failcnt": failcnt, "memory.oom_control": oom}
    memory_stat = optional("memory.stat")
    selected = {key: value for key, value in (memory_stat or {}).items()
                if key in ("anon", "file", "rss", "cache", "total_rss", "total_cache")}
    return {"usage_bytes": int(usage), "lifetime_peak_bytes": peak, "events": events,
            "cpu_stat": optional("cpu.stat", cpu), "memory_stat": selected, "unavailable": missing}


def delta(before, after):
    if before is None or after is None:
        return None
    result = {}
    for key in before.keys() & after.keys():
        if isinstance(before[key], dict) and isinstance(after[key], dict):
            result[key] = delta(before[key], after[key])
        elif isinstance(before[key], int) and isinstance(after[key], int):
            if after[key] < before[key]:
                raise ResourceError("cgroup counter reset during phase: " + key)
            result[key] = after[key] - before[key]
    return result


class BoundedTsv:
    def __init__(self, path, fields, max_rows, max_bytes):
        self.stream = Path(path).open("x", encoding="utf-8", newline="")
        self.fields = fields
        self.max_rows, self.max_bytes = max_rows, max_bytes
        self.rows, self.size = 0, 0
        self._write("\t".join(fields) + "\n")

    def _write(self, value):
        size = len(value.encode("utf-8"))
        if self.size + size > self.max_bytes:
            raise ResourceError("sampler file byte limit exceeded")
        self.stream.write(value)
        self.size += size

    def row(self, values):
        if self.rows >= self.max_rows:
            raise ResourceError("sampler row limit exceeded")
        row = [str(values.get(key, "")) for key in self.fields]
        if any(any(char in value for char in "\t\r\n") for value in row):
            raise ResourceError("unsafe TSV field")
        self._write("\t".join(row) + "\n")
        self.rows += 1

    def close(self):
        self.stream.close()


COMMON = ["epoch_ns", "monotonic_ns", "case", "side", "fork", "benchmark", "mode", "parameters", "phase", "iteration", "sample_kind"]
PROCESS_FIELDS = COMMON + ["role", "pid", "start_ticks", "boot_id", "rss_bytes", "rss_anon_bytes", "rss_file_bytes", "rss_shmem_bytes", "lifetime_vmhwm_bytes"]
CGROUP_FIELDS = COMMON + ["role", "version", "cpu_version", "membership", "memory_path", "usage_bytes", "lifetime_peak_bytes", "events_json", "cpu_stat_json", "memory_stat_json", "unavailable_json"]


def compact(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"))


class Collector:
    def __init__(self, output, case, side, interval_ms=250, max_rows=24000, max_bytes=16777216, proc=Path("/proc")):
        self.output, self.proc = Path(output), Path(proc)
        self.case, self.side, self.interval = case, side, interval_ms / 1000
        self.output.mkdir(parents=True, exist_ok=False)
        self.events = self.output / "events"
        self.events.mkdir()
        self.process = BoundedTsv(self.output / "process.tsv", PROCESS_FIELDS, max_rows, max_bytes)
        self.cgroup = BoundedTsv(self.output / "cgroup.tsv", CGROUP_FIELDS, max_rows, max_bytes)
        self.summary = BoundedTsv(self.output / "phases.tsv", ["case", "side", "fork", "benchmark", "mode", "parameters", "phase", "iteration",
            "periodic_samples", "sampled_peak_rss_bytes", "boundary_peak_rss_bytes", "lifetime_vmhwm_max_bytes",
            "cgroup_sampled_peak_usage_bytes", "cgroup_lifetime_peak_max_bytes", "cgroup_events_delta_json", "cgroup_cpu_delta_json"], max_rows, max_bytes)
        self.targets, self.readers, self.sequences = {}, {}, {}
        self.active = None
        self.completed = 0
        self.progress = {}
        self.expected_session = None
        self.unavailable = set()
        self.observer_started_ns = time.monotonic_ns()
        self.observer_cpu_started_ns = time.process_time_ns()
        self.boot_id = read_text(self.proc / "sys/kernel/random/boot_id", 128).strip()
        self.manifest = {"schema": "JMH_RESOURCES_V1", "case": case, "side": side, "boot_id": self.boot_id,
            "clock_ticks_per_second": os.sysconf("SC_CLK_TCK"), "interval_ms": interval_ms,
            "max_rows_per_file": max_rows, "max_bytes_per_file": max_bytes, "max_forks": 128,
            "scope": "JMH_PROFILER_ITERATION_ENVELOPE_NOT_EXACT_TIMED_REGION",
            "rss": "PROC_STATUS_VMRSS_APPROXIMATE_NOT_SMAPS_PSS_OR_EXACT_PEAK",
            "sampled_peak": "MAX_OF_APPROXIMATE_PERIODIC_SAMPLES_CAN_MISS_SPIKES_NOT_GUARANTEED_TRUE_RSS_LOWER_BOUND",
            "vmhwm": "PROC_STATUS_APPROXIMATE_LIFETIME_HIGH_WATERMARK_NOT_PHASE_PEAK",
            "smaps_rollup": "NOT_COLLECTED_EXPENSIVE_SEPARATE_DIAGNOSTIC_ONLY",
            "cgroup_peak": "LIFETIME_OR_SINCE_EXTERNAL_RESET_NEVER_RESET_BY_COLLECTOR_NOT_PHASE_PEAK",
            "cgroup_scope": "TARGET_CGROUP_INCLUSIVE_SHARED_CHARGES_NOT_PROCESS_RSS",
            "allocation": "GC_B_PER_OP_IS_NOT_RSS", "redis": "UNAVAILABLE_EXTERNAL_TARGET_NOT_SUPPLIED",
            "targets": {}}

    def register(self, key, pid, start):
        if key not in self.targets:
            rss_snapshot(self.proc, pid, start)
            group = resolve_cgroup(self.proc, pid)
            self.targets[key] = (pid, start, group)
            self.manifest["targets"][key] = {"pid": pid, "start_ticks": start,
                "cgroup_version": group["version"], "cpu_cgroup_version": group["cpu_version"], "cgroup_membership": group["membership"],
                "memory_path": str(group["memory"]), "cpu_path": str(group["cpu"])}
        elif self.targets[key][:2] != (pid, start):
            raise ResourceError("target identity changed")

    def sample(self, kind):
        state = self.active
        if state is None:
            return
        common = {key: state[key] for key in ("fork", "benchmark", "mode", "parameters", "phase", "iteration")}
        common.update(epoch_ns=time.time_ns(), monotonic_ns=time.monotonic_ns(), case=self.case, side=self.side, sample_kind=kind)
        fork_group = None
        for role in ("fork", "controller", "observer", "redis"):
            key = state["fork"] if role == "fork" else role
            if key not in self.targets:
                continue
            pid, start, group = self.targets[key]
            values = rss_snapshot(self.proc, pid, start)
            self.process.row(dict(common, role=role, pid=pid, start_ticks=start, boot_id=self.boot_id,
                rss_bytes=values["VmRSS"], lifetime_vmhwm_bytes=values["VmHWM"],
                rss_anon_bytes=values.get("RssAnon", "UNAVAILABLE"), rss_file_bytes=values.get("RssFile", "UNAVAILABLE"),
                rss_shmem_bytes=values.get("RssShmem", "UNAVAILABLE")))
            if role == "fork":
                peak_key = "sampled_peak_rss_bytes" if kind == "periodic" else "boundary_peak_rss_bytes"
                state[peak_key] = max(state[peak_key], values["VmRSS"])
                state["lifetime_vmhwm_max_bytes"] = max(state["lifetime_vmhwm_max_bytes"], values["VmHWM"])
            # Controller/observer cgroups are recorded in the manifest; avoid rereading shared counters.
            if role not in ("fork", "redis"):
                continue
            if read_text(self.proc / str(pid) / "cgroup") != group["cgroup_record"]:
                raise ResourceError("target migrated between cgroups: " + str(pid))
            current = cgroup_snapshot(group)
            self.unavailable.update(role + ":" + field for field in current["unavailable"])
            self.cgroup.row(dict(common, role=role + "_cgroup", version=group["version"], cpu_version=group["cpu_version"], membership=group["membership"],
                memory_path=str(group["memory"]), usage_bytes=current["usage_bytes"],
                lifetime_peak_bytes=current["lifetime_peak_bytes"] if current["lifetime_peak_bytes"] is not None else "UNAVAILABLE",
                events_json=compact(current["events"]), cpu_stat_json=compact(current["cpu_stat"]),
                memory_stat_json=compact(current["memory_stat"]), unavailable_json=compact(current["unavailable"])))
            if role == "fork":
                fork_group = current
        if kind == "periodic":
            state["periodic_samples"] += 1
            state["cgroup_sampled_peak_usage_bytes"] = max(state["cgroup_sampled_peak_usage_bytes"], fork_group["usage_bytes"])
        if fork_group["lifetime_peak_bytes"] is not None:
            state["cgroup_lifetime_peak_max_bytes"] = max(state["cgroup_lifetime_peak_max_bytes"], fork_group["lifetime_peak_bytes"])
        return fork_group

    def event(self, filename, row):
        words = row.rstrip("\n").split("\t")
        if len(words) != 11:
            raise ResourceError("invalid phase marker schema")
        sequence, kind, pid, start, phase, iteration, benchmark, mode, parameters, warmups, measurements = words
        if not all(value.isdigit() for value in (sequence, pid, start, iteration, warmups, measurements)):
            raise ResourceError("invalid phase marker identity")
        pid, start, sequence, iteration = map(int, (pid, start, sequence, iteration))
        warmups, measurements = int(warmups), int(measurements)
        fork = str(pid) + "-" + str(start)
        if filename.name != fork + ".events" or pid <= 0 or start <= 0 or iteration < 1 or not 0 <= warmups <= 128 or not 1 <= measurements <= 128:
            raise ResourceError("phase filename/identity mismatch")
        if sequence != self.sequences.get(fork, 0) + 1 or sequence > 512 or phase not in ("WARMUP", "MEASUREMENT"):
            raise ResourceError("invalid phase sequence")
        if self.expected_session is not None:
            fields = stat_fields(read_text(self.proc / str(pid) / "stat"))
            if pid == self.expected_session or int(fields[2]) != self.expected_session or int(fields[3]) != self.expected_session:
                raise ResourceError("phase PID does not belong to the owned JMH fork session")
        self.register(fork, pid, start)
        contract = (benchmark, mode, parameters, warmups, measurements)
        progress = self.progress.setdefault(fork, {"contract": contract, "WARMUP": 0, "MEASUREMENT": 0})
        if progress["contract"] != contract:
            raise ResourceError("fork benchmark/iteration contract changed")
        if kind == "START":
            if self.active is not None:
                raise ResourceError("overlapping fork/iteration phases")
            expected = warmups if phase == "WARMUP" else measurements
            if iteration != progress[phase] + 1 or iteration > expected or (phase == "WARMUP" and progress["MEASUREMENT"]) or (phase == "MEASUREMENT" and progress["WARMUP"] != warmups):
                raise ResourceError("out-of-order or excess JMH phase")
            self.active = {"fork": fork, "benchmark": benchmark, "mode": mode, "parameters": parameters, "phase": phase, "iteration": iteration,
                "periodic_samples": 0, "sampled_peak_rss_bytes": 0, "boundary_peak_rss_bytes": 0,
                "lifetime_vmhwm_max_bytes": 0, "cgroup_sampled_peak_usage_bytes": 0, "cgroup_lifetime_peak_max_bytes": 0}
            self.active["before_cgroup"] = self.sample("boundary_before")
        elif kind == "END":
            if self.active is None or any(self.active[key] != value for key, value in
                    (("fork", fork), ("phase", phase), ("iteration", iteration), ("benchmark", benchmark), ("mode", mode), ("parameters", parameters))):
                raise ResourceError("unbalanced phase end")
            state = self.active
            after = self.sample("boundary_after")
            before_events, after_events = state["before_cgroup"]["events"], after["events"]
            if "memory.failcnt" in (before_events or {}):
                # v1 under_oom/oom_kill_disable are gauges, not cumulative event counters.
                def legacy_events(events):
                    return {"memory.failcnt": events["memory.failcnt"],
                            "oom_kill": (events.get("memory.oom_control") or {}).get("oom_kill")}
                before_events, after_events = legacy_events(before_events), legacy_events(after_events)
            state["cgroup_events_delta_json"] = compact(delta(before_events, after_events))
            state["cgroup_cpu_delta_json"] = compact(delta(state["before_cgroup"]["cpu_stat"], after["cpu_stat"]))
            if not state["periodic_samples"]:
                state["sampled_peak_rss_bytes"] = "UNAVAILABLE_NO_PERIODIC_SAMPLE"
                state["cgroup_sampled_peak_usage_bytes"] = "UNAVAILABLE_NO_PERIODIC_SAMPLE"
            if not state["cgroup_lifetime_peak_max_bytes"]:
                state["cgroup_lifetime_peak_max_bytes"] = "UNAVAILABLE"
            self.summary.row(dict(state, case=self.case, side=self.side))
            self.active = None
            self.completed += 1
            progress[phase] += 1
        else:
            raise ResourceError("unknown phase event")
        self.sequences[fork] = sequence
        ack = self.events / (fork + ".ack")
        temporary = ack.with_suffix(".tmp")
        with temporary.open("w", encoding="ascii") as stream:
            stream.write(str(sequence) + "\n")
        temporary.replace(ack)

    def drain(self):
        files = sorted(self.events.glob("*.events"))
        if len(files) > 128:
            raise ResourceError("fork/event file count limit exceeded")
        for path in files:
            if path.is_symlink() or not path.is_file() or path.stat().st_size > 131072:
                raise ResourceError("unsafe or oversized event file")
            if path not in self.readers:
                self.readers[path] = path.open("r", encoding="utf-8")
            stream = self.readers[path]
            while True:
                position = stream.tell()
                row = stream.readline(4097)
                if not row:
                    break
                if len(row) > 4096:
                    raise ResourceError("phase marker line limit exceeded")
                if not row.endswith("\n"):
                    stream.seek(position)
                    break
                self.event(path, row)

    def close(self, exit_code, error=None):
        for reader in self.readers.values():
            reader.close()
        for stream in (self.process, self.cgroup, self.summary):
            stream.close()
        (self.output / "manifest.json").write_text(json.dumps(self.manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        status = {"schema": "JMH_RESOURCES_V1", "status": "COMPLETE_NOT_ACCEPTED" if not error and exit_code == 0 else "FAILED_OR_UNAVAILABLE",
                  "command_exit": exit_code, "completed_phases": self.completed, "error": str(error)[:2048] if error else None,
                  "observer_process_cpu_ns": time.process_time_ns() - self.observer_cpu_started_ns,
                  "observer_elapsed_ns": time.monotonic_ns() - self.observer_started_ns,
                  "process_sample_rows": self.process.rows, "cgroup_sample_rows": self.cgroup.rows,
                  "unavailable_metrics": sorted(self.unavailable), "redis_availability": self.manifest["redis"],
                  "overhead_scope": "OBSERVER_ONLY_NOT_JAVA_MARKER_COST_NOT_SUBTRACTED_FROM_CGROUP_OR_TIMING"}
        (self.output / "status.json").write_text(json.dumps(status, indent=2) + "\n", encoding="utf-8")

    def require_complete(self):
        if self.active is not None or not self.progress:
            raise ResourceError("missing or incomplete resource phases")
        for progress in self.progress.values():
            _, _, _, warmups, measurements = progress["contract"]
            if progress["WARMUP"] != warmups or progress["MEASUREMENT"] != measurements:
                raise ResourceError("fork did not complete its declared warmup/measurement phases")


def stop_group(child):
    try:
        os.killpg(child.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    else:
        # Keep the controller PID unreaped until the last group signal; do not target a recycled PID.
        time.sleep(1)
    try:
        os.killpg(child.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    child.wait(timeout=5)


def run(args):
    collector = Collector(args.output, args.case, args.side, args.interval_ms, args.max_samples, args.max_bytes)
    child = None
    error, exit_code = None, 1
    previous = {}
    try:
        def interrupted(number, frame):
            raise ResourceError("sampler interrupted by signal " + str(number))
        for number in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
            previous[number] = signal.signal(number, interrupted)
        collector.register("observer", os.getpid(), identity(collector.proc, os.getpid()))
        redis_pid = os.environ.get("CACHEDB_BENCH_REDIS_PID")
        redis_start = os.environ.get("CACHEDB_BENCH_REDIS_START_TICKS")
        if bool(redis_pid) != bool(redis_start):
            raise ResourceError("explicit Redis PID and start ticks must be supplied together")
        if redis_pid:
            if int(redis_pid) == os.getpid():
                raise ResourceError("Redis target must not be the resource observer")
            collector.register("redis", int(redis_pid), int(redis_start))
            collector.manifest["redis"] = "EXPLICIT_LOCAL_PID_TARGET_OPERATOR_ENDPOINT_BINDING_NOT_VERIFIED"
        environment = dict(os.environ, CACHEDB_BENCH_RESOURCE_EVENTS=str(collector.events.resolve()))
        collector.manifest["command"] = args.command
        child = subprocess.Popen(args.command, env=environment, start_new_session=True)
        collector.register("controller", child.pid, identity(collector.proc, child.pid))
        collector.expected_session = child.pid
        deadline, next_sample = time.monotonic() + args.timeout_seconds, time.monotonic()
        while True:
            collector.drain()
            now = time.monotonic()
            if now >= deadline:
                raise ResourceError("resource collection command timeout")
            if now >= next_sample:
                collector.sample("periodic")
                next_sample = now + collector.interval
            # waitid WNOWAIT preserves the leader PID for safe process-group cleanup.
            if os.waitid(os.P_PID, child.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT) is not None:
                collector.drain()
                collector.require_complete()
                result = os.waitid(os.P_PID, child.pid, os.WEXITED | os.WNOWAIT)
                exit_code = result.si_status if result.si_code == os.CLD_EXITED else -result.si_status
                if exit_code:
                    raise ResourceError("benchmark controller failed: " + str(exit_code))
                break
            time.sleep(min(0.05, collector.interval))
    except (ResourceError, OSError, ValueError) as failure:
        error = failure
        print("RESOURCE_COLLECTION_FAILED: " + str(failure), file=sys.stderr)
    finally:
        # Ignore repeated termination requests while cleaning up the owned process group.
        for number in previous:
            signal.signal(number, signal.SIG_IGN)
        try:
            if child is not None:
                stop_group(child)
        except (OSError, subprocess.SubprocessError) as failure:
            error = error or ResourceError("owned process cleanup unavailable: " + str(failure))
            exit_code = 1
        finally:
            collector.close(exit_code, error)
            for number, handler in previous.items():
                signal.signal(number, handler)
    return 0 if error is None and exit_code == 0 else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True)
    parser.add_argument("--case", required=True)
    parser.add_argument("--side", required=True)
    parser.add_argument("--interval-ms", type=int, default=250)
    parser.add_argument("--max-samples", type=int, default=24000, help="row cap for EACH TSV, including controller/observer/boundaries")
    parser.add_argument("--max-bytes", type=int, default=16777216, help="byte cap for EACH TSV")
    parser.add_argument("--timeout-seconds", type=int, default=1200)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if args.command[:1] == ["--"]:
        args.command = args.command[1:]
    if not args.command or not 100 <= args.interval_ms <= 5000 or not 1 <= args.max_samples <= 100000 or not 4096 <= args.max_bytes <= 67108864 or not 1 <= args.timeout_seconds <= 7200:
        parser.error("invalid command, interval, sample, byte or timeout bound")
    for label in (args.case, args.side):
        if not re.fullmatch(r"[A-Za-z0-9_.-]{1,128}", label):
            parser.error("case/side must be bounded simple labels")
    try:
        return run(args)
    except (ResourceError, OSError, ValueError) as error:
        print("RESOURCE_COLLECTION_UNAVAILABLE: " + str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
