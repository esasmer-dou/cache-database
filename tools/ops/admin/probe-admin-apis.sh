#!/bin/sh
set -eu

for path in \
  /api/health \
  /api/metrics \
  /api/triage \
  /api/services \
  /api/alert-routing \
  /api/alert-routing/history?limit=5 \
  /api/runbooks \
  /api/incidents \
  /api/incident-severity/history?limit=5 \
  /api/failing-signals?limit=3 \
  /api/profile-churn?limit=3 \
  /api/deployment \
  /api/schema/status \
  /api/schema/history?limit=3 \
  /api/schema/ddl \
  /api/profiles \
  /api/registry \
  /api/runtime-profile \
  /api/tuning \
  /api/history?limit=5
do
  echo "=== $path ==="
  wget -qO- "http://127.0.0.1:8080$path" | head -c 400
  echo
  echo
done
