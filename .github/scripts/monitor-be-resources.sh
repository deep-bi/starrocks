#!/usr/bin/env bash
set -uo pipefail

log_file=${1:?Resource log path required}
shift
: "${GITHUB_WORKSPACE:?}" "${1:?Container name required}"

sleep_pid=
stop_monitor() {
    if [[ -n "$sleep_pid" ]]; then
        kill "$sleep_pid" 2>/dev/null || true
        wait "$sleep_pid" 2>/dev/null || true
    fi
    exit 0
}
trap stop_monitor TERM INT

while true; do
    {
        printf '\nBE runner resources %s\n' "$(date --utc --iso-8601=seconds)"
        free -h
        df -h "$GITHUB_WORKSPACE"
        df -i "$GITHUB_WORKSPACE"
        vmstat 1 2
        timeout 15s docker stats --no-stream "$@" || true
    } 2>&1 | tee -a "$log_file"
    sleep 60 &
    sleep_pid=$!
    wait "$sleep_pid" || true
    sleep_pid=
done
