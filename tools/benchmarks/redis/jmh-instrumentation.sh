#!/bin/sh
# Run once per completed cell, outside the measured JVM and resource sampler.
jmh_instrumented_run() {
  instrumentation_case=$1
  instrumentation_side=$2
  jmh_resources_run "$@" || return $?
  shift 2
  "$resources_python" -B "$script_dir/write-jmh-instrumentation.py" \
    --resources "$resources_output/resources/$instrumentation_case-$instrumentation_side" \
    --collector "$resources_output/resource-tools/collect-jmh-resources.py" \
    --launcher "$script_dir/jmh-resources.sh" --sidecar-launcher "$script_dir/jmh-instrumentation.sh" -- "$@"
}
