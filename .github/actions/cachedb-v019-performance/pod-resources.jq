# Non-secret resource evidence only. CPU sums are regular-container sums, not a shared cgroup quota.
def cpu_milli:
  if type != "string" or (test("^(0|[1-9][0-9]*)(m)?$") | not) then error("Unsupported CPU quantity")
  else (if endswith("m") then rtrimstr("m") | tonumber else tonumber * 1000 end)
  | if . <= 64000 then . else error("CPU quantity exceeds evidence bound") end end;

def storage_mib:
  if type != "string" or (test("^(0|[1-9][0-9]*)(Mi|Gi)$") | not) then error("Storage quantity must use Mi or Gi")
  else capture("^(?<amount>[0-9]+)(?<unit>Mi|Gi)$")
  | ((.amount | tonumber) * (if .unit == "Gi" then 1024 else 1 end))
  | if . <= 1048576 then . else error("Storage quantity exceeds evidence bound") end end;

def snapshot($name; $redis):
  . as $pod
  | [.spec.containers[] | {name, resources:(.resources // {}),
      workspaceMounts:[(.volumeMounts // [])[] | select(.mountPath == "/workspace") |
        {name, mountPath, subPath, readOnly}]}] | sort_by(.name) as $containers
  | ([$containers[] | select(.name == $name)] | if length == 1 then .[0] else null end) as $bench
  | ([$containers[] | select(.name == $redis)] | if length == 1 then .[0] else null end) as $server
  | ([$pod.spec.volumes[]? | select(has("emptyDir")) | {name, emptyDir}] | sort_by(.name)) as $volumes
  | (if ($bench.workspaceMounts | length) == 1 then $bench.workspaceMounts[0].name else null end) as $work
  | ([$volumes[] | select(.name == $work)] | if length == 1 then .[0].emptyDir else null end) as $workDir
  | {uid:$pod.metadata.uid, phase:$pod.status.phase, startTime:$pod.status.startTime,
      deadlineSeconds:$pod.spec.activeDeadlineSeconds, benchmarkContainer:$name, redisContainer:$redis,
      resources:($bench.resources // {}), redisResources:($server.resources // {}),
      benchmarkContainerCpuLimitMilli:(try ($bench.resources.limits.cpu | cpu_milli) catch null),
      benchmarkContainerCpuRequestMilli:(try ($bench.resources.requests.cpu | cpu_milli) catch null),
      redisContainerCpuLimitMilli:(try ($server.resources.limits.cpu | cpu_milli) catch null),
      redisContainerCpuRequestMilli:(try ($server.resources.requests.cpu | cpu_milli) catch null),
      regularContainerCpuLimitSumMilli:(try ([$containers[].resources.limits.cpu | cpu_milli] | add) catch null),
      regularContainerCpuRequestSumMilli:(try ([$containers[].resources.requests.cpu | cpu_milli] | add) catch null),
      workspaceVolume:$work, workspaceEmptyDirMiB:(try ($workDir.sizeLimit | storage_mib) catch null),
      benchmarkEphemeralStorageLimitMiB:(try ($bench.resources.limits["ephemeral-storage"] | storage_mib) catch null),
      allocation:{containers:$containers, emptyDirVolumes:$volumes,
        initContainers:[$pod.spec.initContainers[]? | {name, resources, restartPolicy}],
        overhead:($pod.spec.overhead // {}), podLevelResources:($pod.spec.resources // {})}};

def qualify($containerCpu; $podCpu; $child; $memory):
  ($containerCpu | cpu_milli) as $containerMilli
  | ($podCpu | cpu_milli) as $podMilli
  | if ($child != "1" and $child != "2") or ($containerMilli != 1000 and $containerMilli != 2000)
       or $podMilli < 1000 or $podMilli > 4000 then error("Unsupported explicit CPU control") else . end
  | if (.allocation.containers | length) != 2 or .benchmarkContainer == .redisContainer
       or (.allocation.initContainers | length) != 0 or (.allocation.overhead | length) != 0
       or (.allocation.podLevelResources | length) != 0 then
      error("CPU contract requires the two approved regular containers without additional pod CPU budgets") else . end
  | if .benchmarkContainerCpuLimitMilli != $containerMilli
       or .benchmarkContainerCpuRequestMilli != $containerMilli then
      error("Maven container CPU limit/request differs from the explicit container control") else . end
  | if .redisContainerCpuLimitMilli != 1000 or .redisContainerCpuRequestMilli != 200 then
      error("Redis CPU contract requires limit=1 and request=200m") else . end
  | if .regularContainerCpuLimitSumMilli != $podMilli then
      error("Pod regular-container CPU-limit sum differs from the explicit pod control") else . end
  | if .resources.limits.memory != $memory then error("Maven memory limit differs from approval") else . end
  | if .workspaceEmptyDirMiB == null or .workspaceEmptyDirMiB < 2048 then
      error("PRECONDITION_INSUFFICIENT_STORAGE: /workspace emptyDir sizeLimit must be at least 2Gi (Mi/Gi only)") else . end
  | if .benchmarkEphemeralStorageLimitMiB == null or .benchmarkEphemeralStorageLimitMiB < 3072 then
      error("PRECONDITION_INSUFFICIENT_STORAGE: Maven ephemeral-storage limit must be at least 3Gi (Mi/Gi only)") else . end
  | . + {controls:{childActiveProcessorCount:($child | tonumber),
      approvedBenchmarkContainerCpuLimit:$containerCpu, actualBenchmarkContainerCpuLimit:.resources.limits.cpu,
      actualBenchmarkContainerCpuRequest:.resources.requests.cpu,
      approvedPodRegularContainerCpuLimitSum:$podCpu,
      actualPodRegularContainerCpuLimitSumMilli:.regularContainerCpuLimitSumMilli,
      actualRedisContainerCpuLimit:.redisResources.limits.cpu,
      actualRedisContainerCpuRequest:.redisResources.requests.cpu,
      minimumWorkspaceEmptyDirMiB:2048, minimumBenchmarkEphemeralStorageLimitMiB:3072}};

def same_allocation($before):
  if .uid != $before.uid or .allocation != $before.allocation then
    error("Pod UID or CPU/memory/storage allocation drifted; raw evidence is not qualified") else . end;
