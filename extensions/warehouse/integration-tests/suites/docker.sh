#!/bin/bash
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Suite "docker": the extension on the official StarRocks image with real compute nodes and object storage.
# MinIO, 1 FE with the jar, 1 compute node in default_warehouse (172.30.86.21) and 1 in the research
# warehouse (172.30.86.22).
#   - routing of queries, INSERT and Stream Load
#   - a node of either warehouse going down and coming back
#   - checkpoint and FE restart with live nodes
#   - privileges and the USAGE gate, and the switch that turns the gate off
#   - alter, drop, re-create
#   - build and unit tests inside the image, against the image's own jars
# Evidence for routing is the research node's own counters: nothing but research sessions ever reaches it.
IT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
. "$IT_DIR/lib/docker.sh"

metric() { # metric <last ip octet> <scan|load|publish>
  d exec whext-fe bash -c "curl -s -m 5 http://172.30.86.$1:8040/metrics" | awk -v k="$2" '
    k=="scan" && /^starrocks_be_query_scan_rows /{print $2}
    k=="load" && /^starrocks_be_load_rows /{print $2}
    k=="publish" && /^rpc_server_8060_starrocks_lake_service_publish_version_count /{print $2}'; }
grew() { # grew <name> <before> <after> <expected delta or "any">
  local delta=$(( ${3:-0} - ${2:-0} ))
  if [ -z "$2" ] || [ -z "$3" ]; then bad "$1 (counter unreadable)" "before=$2 after=$3"
  elif [ "$4" = "any" ] && [ "$delta" -gt 0 ]; then ok "$1 (+$delta)"
  elif [ "$4" != "any" ] && [ "$delta" -eq "$4" ]; then ok "$1 (+$delta)"
  else bad "$1 (wanted +$4)" "before=$2 after=$3"; fi; }
sl() { d exec whext-fe bash /tmp/streamload.sh "$@" | tr -d '\n' | tr -s ' '; }
state_of() { q -e "SHOW WAREHOUSES" | awk -F'|' -v w="$1" '{gsub(/ /,"",$3); gsub(/ /,"",$4); gsub(/ /,"",$5)} $3==w {print $4" nodes="$5}'; }

trap docker_cleanup EXIT

section "images"
# Pulled here, without the two-minute limit the other docker calls run under.
for img in "$IMAGE" cgr.dev/chainguard/minio:latest; do
  if ! docker image inspect "$img" >/dev/null 2>&1; then
    info "pulling $img"
    docker pull -q "$img" >/dev/null || abort "cannot pull $img"
  fi
done

section "setup on $IMAGE"
out=$(bash "$IT_DIR/docker/setup.sh" 2>&1)
expect "extension loads on the official image" "WarehouseManager -> bi.deep.starrocks.extension.warehouse.UserWarehouseManager" "$out"
waitalive 2 && ok "both compute nodes are alive" || abort "compute nodes not alive" "$(alive)"
expect "the FE in the image is $FE_VERSION" "$FE_VERSION" "$(q -N -e 'SELECT current_version()')"
expect "research is AVAILABLE with one node" "AVAILABLE nodes=1" "$(state_of research)"
expect "the research node is in its warehouse" "172.30.86.22=true(research)" "$(alive)"
d cp -q "$IT_DIR/docker/streamload.sh" whext-fe:/tmp/streamload.sh
q -e "CREATE DATABASE poc; CREATE TABLE poc.t (id BIGINT, v STRING) DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 4;" >/dev/null

section "routing"
L=$(metric 22 load); S=$(metric 22 scan)
q -e "INSERT INTO poc.t SELECT generate_series, concat('row', generate_series) FROM TABLE(generate_series(1, 1000));" >/dev/null; sleep 1
expect "query on default_warehouse returns the data" "500500" "$(q -N -e 'SELECT sum(id) FROM poc.t')"; sleep 1
grew "default_warehouse work does not reach the research node (load)" "$L" "$(metric 22 load)" 0
grew "default_warehouse work does not reach the research node (scan)" "$S" "$(metric 22 scan)" 0

S=$(metric 22 scan)
expect "query after SET warehouse = research" "500500" "$(q -N -e "SET warehouse = 'research'; SELECT sum(id) FROM poc.t;")"; sleep 1
grew "that query scanned on the research node" "$S" "$(metric 22 scan)" 1000
S=$(metric 22 scan)
expect "query with the SET_VAR hint" "375250" "$(q -N -e "SELECT /*+ SET_VAR(warehouse='research') */ sum(id) FROM poc.t WHERE id > 500;")"; sleep 1
grew "the hinted query scanned on the research node" "$S" "$(metric 22 scan)" any

L=$(metric 22 load); P=$(metric 22 publish)
q -e "SET warehouse = 'research'; INSERT INTO poc.t SELECT generate_series, 'from research' FROM TABLE(generate_series(1001, 1500));" >/dev/null; sleep 1
grew "INSERT under research loaded on the research node" "$L" "$(metric 22 load)" 500
grew "its publish ran on the research node" "$P" "$(metric 22 publish)" 1
expect "rows written by research are visible from default" "1125750" "$(q -N -e 'SELECT sum(id) FROM poc.t')"

L=$(metric 22 load)
expect "Stream Load with header warehouse:research" '"Status": "Success"' "$(sl sl_research_1 2001 'warehouse:research')"; sleep 1
grew "that Stream Load ran on the research node" "$L" "$(metric 22 load)" 200
L=$(metric 22 load)
expect "Stream Load without the header" '"Status": "Success"' "$(sl sl_default_1 3001)"; sleep 1
grew "the plain Stream Load did not reach the research node" "$L" "$(metric 22 load)" 0
expect "Stream Load to an unknown warehouse fails" "nosuch not exist" "$(sl sl_bad_1 4001 'warehouse:nosuch')"

section "research node down"
d stop -t 5 whext-cn-research >/dev/null; waitalive 1
expect "research is UNAVAILABLE" "UNAVAILABLE nodes=1" "$(state_of research)"
expect "query on research fails, no fallback" "Warehouse research is not available" "$(q -e "SET warehouse = 'research'; SELECT sum(id) FROM poc.t;")"
expect "insert on research fails, no fallback" "Warehouse research is not available" "$(q -e "SET warehouse = 'research'; INSERT INTO poc.t VALUES (9001, 'x');")"
expect "default_warehouse is not affected" "2165950" "$(q -N -e 'SELECT sum(id) FROM poc.t')"
d start whext-cn-research >/dev/null; waitalive 2 && ok "research node is back" || bad "research node did not come back" "$(alive)"
# StarMgr needs a few seconds to register a restarted node again; default_warehouse has the same window.
for n in $(seq 1 60); do out=$(q -N -e "SET warehouse = 'research'; SELECT sum(id) FROM poc.t;"); echo "$out" | grep -q 2165950 && break; sleep 0.5; done
expect "research works again within 30 s of the node coming back" "2165950" "$out"

section "default node down"
d stop -t 5 whext-cn-default >/dev/null; waitalive 1
expect "research still answers queries" "2165950" "$(q -N -e "SET warehouse = 'research'; SELECT sum(id) FROM poc.t;")"
q -e "SET warehouse = 'research'; INSERT INTO poc.t VALUES (9002, 'research while default down');" >/dev/null
expect "research still takes inserts" "2174952" "$(q -N -e "SET warehouse = 'research'; SELECT sum(id) FROM poc.t;")"
expect "research can create and fill a table" "3" "$(q -N -e "SET warehouse = 'research'; CREATE TABLE poc.t2 (id BIGINT) DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 2; INSERT INTO poc.t2 VALUES (1), (2); SELECT sum(id) FROM poc.t2;")"
expect "a default_warehouse session fails cleanly" "Warehouse default_warehouse is not available" "$(q -e 'SELECT sum(id) FROM poc.t')"
d start whext-cn-default >/dev/null; waitalive 2 && ok "default node is back" || bad "default node did not come back" "$(alive)"

section "checkpoint and FE restart with live nodes"
q -e "ALTER SYSTEM CREATE IMAGE" >/dev/null
for n in $(seq 1 40); do d exec whext-fe bash -c 'ls /data/deploy/starrocks/fe/meta/image/v2/ 2>/dev/null | grep -q "^image\.[0-9]"' && break; sleep 3; done
expect "the image holds the warehouse under the extension's label" "1" "$(d exec whext-fe bash -c 'cd /data/deploy/starrocks/fe/meta/image/v2 && grep -a -o "\"clazz\":\"bi.deep.UserWarehouse\"" $(ls image.[0-9]* | sort -V | tail -1) | wc -l')"
d restart -t 10 whext-fe >/dev/null
fe_sql_up
waitalive 2 && ok "nodes are alive after the FE restart" || bad "nodes not alive after FE restart" "$(alive)"
expect "node assignment survived the restart" "172.30.86.22=true(research)" "$(alive)"
S=$(metric 22 scan)
expect "research answers after the restart" "2174952" "$(q -N -e "SET warehouse = 'research'; SELECT sum(id) FROM poc.t;")"; sleep 1
grew "and still scans on the research node" "$S" "$(metric 22 scan)" any

section "privileges and the gate"
q -e "CREATE USER loader; GRANT INSERT, SELECT ON TABLE poc.t TO USER loader;" >/dev/null
expect "a user without any warehouse grant can use default_warehouse" "default_warehouse" "$(as loader "SELECT @@warehouse, count(*) > 0 FROM poc.t;")"
expect "SET warehouse needs USAGE" "ERROR 5203" "$(as loader "SET warehouse = 'research';")"
expect "the SET_VAR hint on SELECT needs USAGE" "ERROR 5203" "$(as loader "SELECT /*+ SET_VAR(warehouse='research') */ sum(id) FROM poc.t;")"
L=$(metric 22 load)
out=$(sl sl_loader_1 5001 'warehouse:research' loader); sleep 1
expect "gate: Stream Load with the header is refused without USAGE" "Access denied" "$out"
grew "gate: the refused Stream Load loaded nothing on the research node" "$L" "$(metric 22 load)" 0
L=$(metric 22 load)
out=$(as loader "INSERT /*+ SET_VAR(warehouse='research') */ INTO poc.t VALUES (9100, 'hint insert');"); sleep 1
expect "gate: INSERT with the hint is refused without USAGE" "ERROR 5203" "$out"
grew "gate: the refused INSERT loaded nothing on the research node" "$L" "$(metric 22 load)" 0
q -e "ALTER USER loader SET PROPERTIES ('session.warehouse' = 'research');" >/dev/null
expect "gate: a default warehouse set on the user is refused without USAGE" "ERROR 5203" "$(as loader "SELECT sum(id) FROM poc.t;")"
q -e "ALTER USER loader SET PROPERTIES ('session.warehouse' = 'default_warehouse');" >/dev/null
expect "the user is back on default_warehouse" "default_warehouse" "$(as loader "SELECT @@warehouse, count(*) > 0 FROM poc.t;")"

q -e "GRANT USAGE ON WAREHOUSE research TO USER loader;" >/dev/null
expect "with USAGE: SET warehouse works" "research" "$(as loader "SET warehouse = 'research'; SELECT @@warehouse;")"
L=$(metric 22 load)
expect "with USAGE: Stream Load with the header works" '"Status": "Success"' "$(sl sl_loader_2 5201 'warehouse:research' loader)"; sleep 1
grew "with USAGE: that Stream Load ran on the research node" "$L" "$(metric 22 load)" 200
L=$(metric 22 load)
as loader "INSERT /*+ SET_VAR(warehouse='research') */ INTO poc.t VALUES (9101, 'hint insert');" >/dev/null; sleep 1
grew "with USAGE: INSERT with the hint ran on the research node" "$L" "$(metric 22 load)" 1
q -e "REVOKE USAGE ON WAREHOUSE research FROM USER loader;" >/dev/null
expect "after REVOKE the INSERT with the hint is refused again" "ERROR 5203" "$(as loader "INSERT /*+ SET_VAR(warehouse='research') */ INTO poc.t VALUES (9102, 'x');")"
expect "root is never refused" "research" "$(q -N -e "SET warehouse = 'research'; SELECT @@warehouse, count(*) > 0 FROM poc.t;")"

section "the switch that turns the check off, and what the check costs"
d cp -q "$IT_DIR/docker/timeq.sh" whext-fe:/tmp/timeq.sh
d exec whext-fe bash /tmp/timeq.sh >/dev/null
ON1=$(d exec whext-fe bash /tmp/timeq.sh); ON2=$(d exec whext-fe bash /tmp/timeq.sh)
d exec whext-fe bash -c 'echo "warehouse_extension_check_usage = false" >> /data/deploy/starrocks/fe/conf/fe.conf'
fe_restart
expect "switch off: the FE reports the setting at start" "warehouse_extension_check_usage=false" "$(d exec whext-fe bash -c 'grep "warehouse_extension loaded" /data/deploy/starrocks/fe/log/fe.log | tail -1')"
L=$(metric 22 load)
as loader "INSERT /*+ SET_VAR(warehouse='research') */ INTO poc.t VALUES (9103, 'switch off');" >/dev/null; sleep 1
grew "switch off: INSERT with the hint goes through, as on a stock FE" "$L" "$(metric 22 load)" 1
d exec whext-fe bash /tmp/timeq.sh >/dev/null
OFF1=$(d exec whext-fe bash /tmp/timeq.sh); OFF2=$(d exec whext-fe bash /tmp/timeq.sh)
d exec whext-fe sed -i '/warehouse_extension_check_usage/d' /data/deploy/starrocks/fe/conf/fe.conf
fe_restart
expect "switch back on: INSERT with the hint is refused again" "ERROR 5203" "$(as loader "INSERT /*+ SET_VAR(warehouse='research') */ INTO poc.t VALUES (9104, 'x');")"
info "microseconds per query on research (300 queries as root, 2 runs each): check on $ON1 / $ON2, check off $OFF1 / $OFF2"
# Only log header lines count, and only those from threads that do not serve a user request.
expect "background work raised no access-denied error" "0" "$(d exec whext-fe bash -c 'grep "Access denied" /data/deploy/starrocks/fe/log/fe.log | grep "^[0-9-]* [0-9:.Z]* [A-Z]* (" | grep -vc "(starrocks-mysql-nio-pool-\|(starrocks-http-\|(nioEventLoopGroup" ')"

section "alter, drop, re-create"
expect "ALTER replica_number on a live warehouse" "replica_number=2" "$(q -e "ALTER WAREHOUSE research SET ('replica_number' = '2'); SHOW WAREHOUSES;")"
expect "queries still work after the ALTER" "research" "$(q -N -e "SET warehouse = 'research'; SELECT @@warehouse, count(*) > 0 FROM poc.t;")"
expect "DROP is refused while the node is there" "1 node(s) are registered in it (172.30.86.22:9050)" "$(q -e 'DROP WAREHOUSE research')"
q -e "ALTER SYSTEM DROP COMPUTE NODE '172.30.86.22:9050' FROM WAREHOUSE research; DROP WAREHOUSE research;" >/dev/null
[ -z "$(state_of research)" ] && ok "SHOW WAREHOUSES no longer lists it" || bad "research still listed" "$(state_of research)"
expect "SET warehouse to the dropped name fails" "research not exist" "$(q -e "SET warehouse = 'research'")"
q -e "CREATE WAREHOUSE research2; ALTER SYSTEM ADD COMPUTE NODE '172.30.86.22:9050' INTO WAREHOUSE research2;" >/dev/null
waitalive 2 && ok "the same node joined the re-created warehouse" || bad "node did not join research2" "$(alive)"
S=$(metric 22 scan)
q -e "SET warehouse = 'research2'; SELECT sum(id) FROM poc.t;" >/dev/null; sleep 1
grew "queries on the re-created warehouse scan on that node" "$S" "$(metric 22 scan)" any
expect "no journal entry was skipped by the FE" "0" "$(d exec whext-fe bash -c 'grep -c "SKIP JOURNAL" /data/deploy/starrocks/fe/log/fe.log')"

section "build and unit tests inside the image, against its own jars"
if [ -z "$PROJECT_DIR" ] || [ ! -d "$PROJECT_DIR/src" ]; then
  skip "build inside the image: no source tree given (--project)"
elif ! collect_testlibs "$SUITE_DIR/testlibs"; then
  skip "build inside the image: JUnit or JMockit jars not found in ~/.m2 or the Gradle cache"
else
  d rm -f whext-build >/dev/null 2>&1
  d run -d --name whext-build --memory 3g -e STARROCKS_HOME=/work/test-home --entrypoint sleep "$IMAGE" infinity >/dev/null
  d exec whext-build mkdir -p /work/test-home/conf
  d cp -q "$PROJECT_DIR/src" whext-build:/work/src
  d cp -q "$SUITE_DIR/testlibs" whext-build:/work/testlibs
  d cp -q "$IT_DIR/docker/RunTests.java" whext-build:/work/RunTests.java
  d cp -q "$IT_DIR/docker/inbuild.sh" whext-build:/work/inbuild.sh
  out=$(timeout 500 docker exec whext-build bash /work/inbuild.sh 2>&1); rc=$?
  echo "$out" | grep -E '^(RESULT|FAIL|fe-core|javac|main classes|test classes)' | sed 's/^/      /'
  [ $rc -eq 0 ] && ok "sources compile and all unit tests pass against the image's jars" || bad "build inside the image failed (rc=$rc)" "$(echo "$out" | tail -8)"
  T=$(echo "$out" | sed -n -E 's/^RESULT .*tests=([0-9]+).*/\1/p' | awk '{s+=$1} END {print s+0}')
  info "unit tests run inside the image: $T"
fi

finish
