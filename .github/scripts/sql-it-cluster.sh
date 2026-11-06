#!/usr/bin/env bash
set -euo pipefail

: "${SQL_IT_STATE:?}" "${SQL_IT_REPORTS:?}" "${SQL_IT_PREFIX:?}"
[[ "$SQL_IT_PREFIX" =~ ^sr-it-[a-zA-Z0-9-]+$ ]] || exit 1
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
export SQL_IT_MODE=${SQL_IT_MODE:-shared_nothing}
case "$SQL_IT_MODE" in
    shared_nothing) backend=be ;;
    shared_data) backend=cn ;;
    *) echo "Unknown cluster mode: $SQL_IT_MODE" >&2; exit 2 ;;
esac
mkdir -p "$SQL_IT_STATE" "$SQL_IT_REPORTS"

address() {
    docker inspect --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$SQL_IT_PREFIX-$1"
}

case "${1:?start or stop}" in
start)
    : "${RC_PACKAGE:?}" "${SQL_IT_IMAGE:?}"
    python3 "$script_dir/sql_it.py" prepare --package "$RC_PACKAGE"
    docker network create "$SQL_IT_PREFIX" >/dev/null
    if [[ "$SQL_IT_MODE" == shared_data ]]; then
        docker run --detach --name "$SQL_IT_PREFIX-s3" --network "$SQL_IT_PREFIX" --network-alias s3 \
            --memory 1g --cpus 1 --env RUSTFS_ACCESS_KEY=starrocks \
            --env RUSTFS_SECRET_KEY=starrocks-test-secret --env RUSTFS_CONSOLE_ENABLE=false \
            rustfs/rustfs:1.0.0@sha256:8cc9801755448b71a786705ce76692c77e14936cccd87cf2fc31842e58f4d1ff /data >/dev/null
        endpoint="http://$(address s3):9000"
        curl --fail --silent --show-error --retry 60 --retry-delay 2 --retry-all-errors \
            --connect-timeout 2 --max-time 5 "$endpoint/health"
        curl --fail --silent --show-error --retry 30 --retry-delay 2 --retry-all-errors \
            --connect-timeout 5 --max-time 30 \
            --aws-sigv4 aws:amz:us-east-1:s3 --user starrocks:starrocks-test-secret \
            --header 'x-amz-content-sha256: e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855' \
            --request PUT "$endpoint/starrocks-sql-it"
    fi
    mysql_host=""
    if [[ ",${EXCLUDE_COMPONENTS// /}," != *,mysql,* ]]; then
        docker run --detach --name "$SQL_IT_PREFIX-mysql" --network "$SQL_IT_PREFIX" \
            --memory 1g --cpus 1 --env MYSQL_ROOT_PASSWORD=starrocks-it --env MYSQL_ROOT_HOST=% \
            mysql:8.4@sha256:a81d043930e51745522bd5c430961cf8286f0a23f0a9a2a6e0108f7ea71b0902 >/dev/null
        for attempt in {1..90}; do
            if docker exec "$SQL_IT_PREFIX-mysql" mysql -uroot -pstarrocks-it -e 'SELECT 1' >/dev/null 2>&1; then
                break
            fi
            [[ "$attempt" -lt 90 ]] || { echo "MySQL did not become ready" >&2; exit 1; }
            sleep 2
        done
        mysql_host=$(address mysql)
    fi
    for node in fe "${backend}1" "${backend}2" "${backend}3"; do
        component=be
        launcher=$backend
        [[ "$node" != fe ]] || component=fe
        [[ "$node" != fe ]] || launcher=fe
        docker run --detach --init --name "$SQL_IT_PREFIX-$node" --network "$SQL_IT_PREFIX" \
            --memory 3g --cpus 2 --ulimit nofile=65535:65535 --ulimit nproc=65535:65535 --ulimit core=0 \
            --volume "$SQL_IT_STATE/$node:/opt/starrocks/$component" \
            --workdir "/opt/starrocks/$component" "$SQL_IT_IMAGE" \
            bash "bin/start_$launcher.sh" >/dev/null
    done
    fe_host=$(address fe)
    printf '%s\n' "$fe_host" > "$SQL_IT_STATE/fe-host"
    python3 "$script_dir/sql_it.py" configure --host "$fe_host" --mysql-host "$mysql_host"
    python3 "$script_dir/sql_it.py" ready --host "$fe_host" \
        --backends "$(address "${backend}1")" "$(address "${backend}2")" "$(address "${backend}3")" --register
    ;;
stop)
    for node in fe "${backend}1" "${backend}2" "${backend}3" mysql s3; do
        docker logs --tail 3000 "$SQL_IT_PREFIX-$node" > "$SQL_IT_REPORTS/$node-container.log" 2>&1 || true
        docker inspect --format '{{json .State}}' "$SQL_IT_PREFIX-$node" \
            > "$SQL_IT_REPORTS/$node-state.json" 2>/dev/null || true
        docker rm --force --volumes "$SQL_IT_PREFIX-$node" >/dev/null 2>&1 || true
        if [[ -d "$SQL_IT_STATE/$node/log" ]]; then
            while IFS= read -r -d '' log; do
                tail -c 2097152 "$log" > "$SQL_IT_REPORTS/$node-$(basename "$log")" || true
            done < <(find "$SQL_IT_STATE/$node/log" -maxdepth 1 -type f \( -name '*.log*' -o -name '*.out' \) -print0)
        fi
    done
    docker network rm "$SQL_IT_PREFIX" >/dev/null 2>&1 || true
    ;;
*) exit 2 ;;
esac
