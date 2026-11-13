#!/usr/bin/env python3
"""Bind actual controller instrumentation to a completed, unchanged JMH raw file."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
import zipfile


def digest(data):
    return hashlib.sha256(data).hexdigest()


def read_json(path, limit):
    path = Path(path)
    if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= limit:
        raise ValueError(f"Missing, linked or oversized instrumentation input: {path}")
    return json.loads(path.read_text(encoding="utf-8-sig"))


def case_key(row):
    params = row.get("params", {})
    if not isinstance(params, dict):
        raise ValueError("Invalid JMH case parameters")
    for name, value in params.items():
        if not re.fullmatch(r"[A-Za-z0-9_.-]{1,128}", name) or not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_.:/+ -]{0,1024}", value):
            raise ValueError("Ambiguous JMH case parameter")
    benchmark, mode, threads = row["benchmark"], row["mode"], row["threads"]
    if not isinstance(benchmark, str) or not re.fullmatch(r"[A-Za-z0-9_.$]{1,1024}", benchmark) or mode not in ("thrpt", "sample") or type(threads) is not int or threads <= 0:
        raise ValueError("Invalid JMH case identity")
    return f"{benchmark}|{mode}|threads={threads}|" + ",".join(f"{name}={params[name]}" for name in sorted(params))


def one_option(command, name):
    positions = [index for index, value in enumerate(command) if value == name]
    if len(positions) != 1 or positions[0] + 1 >= len(command):
        raise ValueError(f"Missing or duplicate controller option: {name}")
    return command[positions[0] + 1]


def profiler_bytes(classpath, name):
    entry = name.replace(".", "/") + ".class"
    # Classpath order is JVM resolution order, not sorted helper paths.
    for path in classpath.split(":"):
        candidate = Path(path)
        if candidate.is_dir() and (candidate / entry).is_file():
            return (candidate / entry).read_bytes()
        if candidate.is_file() and zipfile.is_zipfile(candidate):
            with zipfile.ZipFile(candidate) as archive:
                if entry in archive.namelist():
                    return archive.read(entry)
    raise ValueError(f"Active profiler class is absent from controller classpath: {name}")


def write_sidecar(resources, collector, launcher, sidecar_launcher, command):
    manifest = read_json(Path(resources) / "manifest.json", 4 * 1024 * 1024)
    status = read_json(Path(resources) / "status.json", 1024 * 1024)
    if manifest.get("schema") != "JMH_RESOURCES_V1" or manifest.get("command") != command or status.get("schema") != "JMH_RESOURCES_V1" or status.get("status") != "COMPLETE_NOT_ACCEPTED" or status.get("command_exit") != 0 or status.get("error") is not None:
        raise ValueError("Instrumentation requires the actual successful collector command")
    main = command.index("org.openjdk.jmh.Main")
    options = command[main + 1:]
    raw = Path(one_option(options, "-rff"))
    rows = read_json(raw, 64 * 1024 * 1024)
    if not isinstance(rows, list) or not 1 <= len(rows) <= 256:
        raise ValueError("Invalid JMH raw case array")
    profilers = [options[index + 1] for index, value in enumerate(options) if value == "-prof" and index + 1 < len(options)]
    if not profilers or len(profilers) > 16 or options[-1] == "-prof":
        raise ValueError("Invalid controller profiler list")
    classpath = one_option(command[:main], "-cp")
    helpers = [{"identity": name, "sha256": digest(Path(path).read_bytes())} for name, path in (
        ("resource-collector", collector), ("resource-launcher", launcher),
        ("sidecar-writer", __file__), ("sidecar-launcher", sidecar_launcher))]
    for profiler in profilers:
        name = profiler.split(":", 1)[0]
        if name != "gc":
            helpers.append({"identity": "profiler:" + name, "sha256": digest(profiler_bytes(classpath, name))})
    sampler = {}
    for source, target in (("interval_ms", "intervalMs"), ("max_rows_per_file", "maxRowsPerFile"),
                           ("max_bytes_per_file", "maxBytesPerFile"), ("max_forks", "maxForks")):
        value = manifest[source]
        if type(value) is not int or value <= 0:
            raise ValueError(f"Invalid actual sampler setting: {source}")
        sampler[target] = value
    sampler["redisTarget"] = manifest["redis"]
    if sampler["redisTarget"] not in ("UNAVAILABLE_EXTERNAL_TARGET_NOT_SUPPLIED", "EXPLICIT_LOCAL_PID_TARGET_OPERATOR_ENDPOINT_BINDING_NOT_VERIFIED"):
        raise ValueError("Unknown actual Redis sampling target mode")
    contract = {"controllerProfilers": profilers, "helpers": sorted(helpers, key=lambda item: item["identity"]), "sampler": sampler}
    keys = [case_key(row) for row in rows]
    if len(set(keys)) != len(keys):
        raise ValueError("Duplicate JMH instrumentation case")
    document = {"schema": "JMH_INSTRUMENTATION_V1", "rawSha256": digest(raw.read_bytes()),
                "cases": [{"key": key, "contract": contract} for key in keys]}
    # Exclusive creation leaves original raw evidence untouched and never replaces an earlier contract.
    with Path(str(raw) + ".instrumentation").open("x", encoding="utf-8", newline="\n") as output:
        json.dump(document, output, indent=2, allow_nan=False)
        output.write("\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("resources", "collector", "launcher", "sidecar-launcher"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    try:
        write_sidecar(args.resources, args.collector, args.launcher, args.sidecar_launcher, command)
    except (OSError, ValueError, KeyError, TypeError, IndexError, zipfile.BadZipFile) as error:
        print("JMH_INSTRUMENTATION_UNAVAILABLE: " + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
