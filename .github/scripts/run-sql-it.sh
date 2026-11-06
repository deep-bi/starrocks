#!/usr/bin/env bash
set -euo pipefail

: "${SQL_IT_STATE:?}" "${SQL_IT_REPORTS:?}" "${EXCLUDE_COMPONENTS?}" "${EXCLUDE_DATASETS?}"
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
phase=${1:?concurrent or sequential}
export SQL_IT_MODE=${SQL_IT_MODE:-shared_nothing}
case "$SQL_IT_MODE" in
    shared_nothing) cluster=native ;;
    shared_data) cluster=cloud ;;
    *) exit 2 ;;
esac
case "$phase" in
    concurrent) attributes='!sequential'; workers=2 ;;
    sequential) attributes=sequential; workers=1 ;;
    *) exit 2 ;;
esac

python3 "$script_dir/sql_it.py" collect --phase "$phase" 2>&1 | tee "$SQL_IT_REPORTS/$phase-collection.log"
python3 "$script_dir/sql_it.py" ready --host "$(cat "$SQL_IT_STATE/fe-host")"
export NOSE_XUNITMP_FILE="$SQL_IT_REPORTS/$phase.xml"
cd "$script_dir/../../test"
result=0
python3 run.py -v -C "$cluster" -a "$attributes" -c "$workers" \
    --config "$SQL_IT_STATE/sr.conf" --require-all --log_filtered \
    --exclude-components "$EXCLUDE_COMPONENTS" --exclude-datasets "$EXCLUDE_DATASETS" \
    2>&1 | tee "$SQL_IT_REPORTS/$phase.log" || result=1
python3 "$script_dir/sql_it.py" report --phase "$phase" || result=1
python3 "$script_dir/sql_it.py" ready --host "$(cat "$SQL_IT_STATE/fe-host")" || result=1
exit "$result"
