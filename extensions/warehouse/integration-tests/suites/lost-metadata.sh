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

# Suite "lost-metadata": the alarm for lost warehouse metadata, and the repair. Three FEs, no compute node
# processes.
#   1. healthy cluster: the gauge is published and reads 0
#   2. fe3 loses the jar and writes the checkpoint image: the image has no warehouses
#   3. fe2 (which has the jar) restarts from that image: alarm in its log, gauge above 0
#   4. repair from fe1, which still holds the warehouses in memory, then a clean checkpoint
IT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
. "$IT_DIR/lib/host.sh"

GAUGE=starrocks_fe_warehouse_extension_orphaned_nodes
metric() { nsx 0 curl -s -m 5 "http://10.86.0.$1:8030/metrics" | awk -v g="$GAUGE" '$1==g {print $2}'; }
wait_metric() { # wait_metric <fe> <value> [seconds]
  for n in $(seq 1 "${3:-100}"); do [ "$(metric $1)" = "$2" ] && return 0; sleep 1; done; return 1; }

section "namespaces"
start_namespaces || abort "namespaces did not start" "$(cat "$H/ns.log")"

section "healthy cluster"
for i in 1 2 3; do mkhome $i; cp "$EXTJAR" "$H/fe$i/lib/"; setconf $i enable_warehouse_extension true; done
start_clean 1
fe_up 1 || abort "fe1 did not start" "$(tail -20 "$H/fe1/log/fe.out" 2>/dev/null)"
sql 1 "CREATE WAREHOUSE research COMMENT 'ad hoc research'" "CREATE WAREHOUSE etl PROPERTIES ('replica_number' = '2')" \
  "ALTER SYSTEM ADD COMPUTE NODE '10.86.0.51:9050' INTO WAREHOUSE research" "ALTER SYSTEM ADD COMPUTE NODE '10.86.0.52:9050' INTO WAREHOUSE etl" \
  "ALTER SYSTEM ADD COMPUTE NODE '10.86.0.50:9050'" "ALTER SYSTEM ADD FOLLOWER '10.86.0.2:9010'" "ALTER SYSTEM ADD FOLLOWER '10.86.0.3:9010'" >/dev/null
start_clean 2 --helper 10.86.0.1:9010; start_clean 3 --helper 10.86.0.1:9010; fe_wait 2; fe_wait 3; sleep 5
HEALTHY=$(names 1)
expect "three FEs agree on the warehouses" "$HEALTHY" "$(names 2)$(names 3)"
for i in 1 2 3; do wait_metric $i 0 100 && ok "fe$i publishes the gauge, value 0" || bad "fe$i gauge" "$(metric $i)"; done
expect "the gauge has a description on the metrics endpoint" "warehouse metadata was lost" "$(nsx 0 curl -s -m 5 http://10.86.0.1:8030/metrics | grep "HELP $GAUGE")"

section "fe3 loses the jar and writes the checkpoint"
fe_stop 3; sleep 2; rm -f "$H/fe3/lib/$EXTJAR_NAME"; start_clean 3; fe_wait 3
expect "fe3 without the jar shows only the default warehouse" "default_warehouse() " "$(names 3)"
POISONED=no
for t in 1 2 3 4 5 6 7 8; do
  before=$(newest_image 1)
  # an ALTER that changes nothing still writes an edit-log entry, so there is something to checkpoint
  sql 1 "ALTER WAREHOUSE etl SET ('replica_number' = '2')" "ALTER SYSTEM CREATE IMAGE" >/dev/null
  for n in $(seq 1 60); do [ "$(newest_image 1)" != "$before" ] && break; sleep 3; done
  sleep 3
  worker=$(grep -h 'select worker' "$H/fe1/log/fe.log" | grep global_state | tail -1 | sed -E 's/.*select worker: 10\.86\.0\.([0-9]).*/fe\1/')
  echo "      checkpoint $t: $(newest_image 1) written by $worker, warehouses in it: $(labels_in_image 1)"
  [ "$(labels_in_image 1)" = 0 ] && { POISONED=yes; break; }
done
[ "$POISONED" = yes ] && ok "the FE without the jar wrote an image with no warehouses" || bad "could not get fe3 to write the checkpoint in 8 tries"
sleep 8
expect "fe2 received that image" "0" "$(labels_in_image 2)"
expect "the leader fe1 still lists the warehouses (memory only)" "$HEALTHY" "$(names 1)"
expect "fe1 raises no alarm: its own view is intact" "0" "$(metric 1)"

section "fe2 restarts from that image"
restart 2
expect "fe2 lost the warehouses" "default_warehouse() " "$(names 2)"
wait_metric 2 2 100 && ok "fe2 gauge shows 2 orphaned nodes" || bad "fe2 gauge" "$(metric 2)"
ALARM=$(lines_since_start 2 'Warehouse metadata is missing on this FE' | tail -1)
expect "fe2 logs the alarm at ERROR level" " ERROR " "$ALARM"
expect "the alarm names both nodes" "10.86.0.51:9050" "$ALARM"
expect "the alarm names both nodes (second)" "10.86.0.52:9050" "$ALARM"
expect "the alarm counts both lost warehouses" "2 node(s) belong to 2 unknown warehouse(s)" "$ALARM"
for w in research etl; do
  id=$(sql 1 "SHOW WAREHOUSES" | awk -F'\t' -v w="$w" '$2==w {print $1}')
  expect "the alarm gives the id of $w, as fe1 still lists it" "Warehouse id $id, worker group " "$ALARM"
done
echo "      $(echo "$ALARM" | sed -E 's/^[0-9-]+ [0-9:.+]+ //' | cut -c1-400)"

section "repair from the FE that still has them"
cp "$EXTJAR" "$H/fe3/lib/"; restart 3
wait_metric 3 2 100 && ok "fe3, jar restored, also raises the alarm" || bad "fe3 gauge" "$(metric 3)"
expect "ALTER with unchanged values on the leader" "OK" "$(sql 1 "ALTER WAREHOUSE research SET ('replica_number' = '1')" "ALTER WAREHOUSE etl SET ('replica_number' = '2')" | tail -1)"
sleep 6
expect "fe2 has the warehouses back" "$HEALTHY" "$(names 2)"
expect "fe3 has the warehouses back" "$HEALTHY" "$(names 3)"
wait_metric 2 0 100 && ok "fe2 gauge is back to 0" || bad "fe2 gauge after repair" "$(metric 2)"
wait_metric 3 0 100 && ok "fe3 gauge is back to 0" || bad "fe3 gauge after repair" "$(metric 3)"
expect "fe2 logs the all-clear" "Every node belongs to a known warehouse again" "$(lines_since_start 2 'Every node belongs' | tail -1)"
checkpoint 1 1 && ok "checkpoint with the jar on every FE" || bad "checkpoint after repair did not finish"
sleep 8
for i in 1 2 3; do expect "fe$i image holds both warehouses again" "2" "$(labels_in_image $i)"; done
restart 1; sleep 5
expect "fe1, the last holder, restarts and still has them" "$HEALTHY" "$(names 1)"
for i in 1 2 3; do expect "fe$i gauge is 0 at the end" "0" "$(wait_metric $i 0 100; metric $i)"; done

finish
