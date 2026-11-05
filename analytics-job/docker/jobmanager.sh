#!/usr/bin/env bash
# Starts the analytics job, resuming from the newest completed checkpoint when there is one.
#
# Without high availability, a restarted JobManager would start the job afresh: empty state, read from the start of
# the topic. Checkpoints are kept when the job stops, so the newest one with a _metadata file (complete) carries the
# Kafka offsets and every instrument's candles and last sequence.
#
# Each start runs under a new job ID, so every earlier job's directory is deleted here, except the one resumed from.
# That keeps at most two on disk: the checkpoint resumed from, and the running job's own. The state backend writes
# full checkpoints, so no directory depends on another's files.
set -euo pipefail

checkpoints=${CHECKPOINT_DIR:-/opt/flink/checkpoints}
job_class=com.mia_princz.mse.analytics.AnalyticsJob

latest=$(find "$checkpoints" -mindepth 3 -maxdepth 3 -path '*/chk-*/_metadata' -printf '%T@ %h\n' 2>/dev/null |
  sort -n | tail -n 1 | cut -d' ' -f2-)
keep=${latest%/chk-*}

for job in "$checkpoints"/*; do
  if [ -d "$job" ] && [ "$job" != "$keep" ] && [[ $(basename "$job") =~ ^[0-9a-f]{32}$ ]]; then
    echo "Removing checkpoints of an earlier run: $job"
    rm -rf -- "$job"
  fi
done

if [ -n "$latest" ]; then
  echo "Resuming the analytics job from checkpoint $latest"
  exec /docker-entrypoint.sh standalone-job --job-classname "$job_class" --fromSavepoint "$latest"
fi
echo "No completed checkpoint in $checkpoints; starting the analytics job from the beginning"
exec /docker-entrypoint.sh standalone-job --job-classname "$job_class"
