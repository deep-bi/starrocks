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

# Suite "replica-drift": a worker group whose replica number in StarMgr is not the stored one. Three FEs, no
# compute node processes.
# An FE that dies in the middle of ALTER WAREHOUSE leaves StarMgr with a number the edit log never got.
# lib/StarMgr.java makes that state on purpose: it changes the worker group in StarMgr, past the FE.
#   1. the tool reads what CREATE and ALTER put into StarMgr
#   2. ALTER WAREHOUSE with the stored value corrects StarMgr at once
#   3. the leader corrects it on its own within a minute; followers change nothing
#   4. the leader is killed right after the drift appears: the new leader corrects it
#   5. an ALTER that committed is left alone
# Every step uses its own wrong number, so a log line shows which step it belongs to.
IT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
. "$IT_DIR/lib/host.sh"

# starmgr <fe> get <worker group> | set <worker group> <replica number>: runs in that FE's namespace
starmgr() {
  local i=$1; shift
  nsx $i "$JAVA_HOME/bin/java" -cp "$FEOUT/lib/*" "$LIB_DIR/StarMgr.java" 127.0.0.1:6090 "$@" 2>/dev/null | tail -1
}
# wait_group <fe> <worker group> <replica number> [seconds]: waits until StarMgr holds that number
wait_group() {
  local n
  for n in $(seq 1 $(( ${4:-100} / 3 ))); do [ "$(starmgr $1 get $2)" = "$3" ] && return 0; sleep 2; done
  return 1
}
CORRECTED='StarMgr was set to the stored value'

section "namespaces"
start_namespaces || abort "namespaces did not start" "$(cat "$H/ns.log")"

section "three FEs, one warehouse"
for i in 1 2 3; do mkhome $i; cp "$EXTJAR" "$H/fe$i/lib/"; setconf $i enable_warehouse_extension true; done
start_clean 1
fe_up 1 || abort "fe1 did not start" "$(tail -20 "$H/fe1/log/fe.out" 2>/dev/null)"
sql 1 "CREATE WAREHOUSE etl PROPERTIES ('replica_number' = '2')" "ALTER SYSTEM ADD COMPUTE NODE '10.86.0.52:9050' INTO WAREHOUSE etl" \
  "ALTER SYSTEM ADD FOLLOWER '10.86.0.2:9010'" "ALTER SYSTEM ADD FOLLOWER '10.86.0.3:9010'" >/dev/null
start_clean 2 --helper 10.86.0.1:9010; start_clean 3 --helper 10.86.0.1:9010; fe_wait 2; fe_wait 3; sleep 5
L=$(leader 1)
G=$(sql $L "SHOW NODES FROM WAREHOUSE etl" | awk -F'\t' '$1=="etl" {print $3; exit}')
[ -n "$G" ] && ok "the warehouse has worker group $G" || abort "cannot read the worker group of the warehouse" "$(sql $L "SHOW NODES FROM WAREHOUSE etl")"
expect "the tool reads the replica number CREATE put into StarMgr" "2" "$(starmgr $L get $G)"
sql $L "ALTER WAREHOUSE etl SET ('replica_number' = '3')" >/dev/null
expect "and the one ALTER put there" "3" "$(starmgr $L get $G)"
sql $L "ALTER WAREHOUSE etl SET ('replica_number' = '2')" >/dev/null
expect "a worker group StarMgr does not know is reported as such" "ERROR" "$(starmgr $L get 999999)"

section "ALTER with the stored value corrects StarMgr"
expect "drift: StarMgr now holds 5" "5" "$(starmgr $L set $G 5)"
expect "the FE still shows the stored value" "etl(replica_number=2)" "$(names $L)"
expect "ALTER WAREHOUSE with the stored value" "OK" "$(sql $L "ALTER WAREHOUSE etl SET ('replica_number' = '2')")"
expect "StarMgr holds the stored value again, at once" "2" "$(starmgr $L get $G)"
expect "the leader logged what it found" "had replica number 5 in StarMgr, the stored value is 2" "$(lines_since_start $L "$CORRECTED" | tail -1)"

section "the leader corrects it on its own"
expect "drift: StarMgr now holds 6" "6" "$(starmgr $L set $G 6)"
wait_group $L $G 2 100 && ok "StarMgr holds the stored value again within the leader's next check" || bad "the leader did not correct StarMgr" "$(starmgr $L get $G)"
expect "the leader logged what it found" "had replica number 6 in StarMgr, the stored value is 2" "$(lines_since_start $L "$CORRECTED" | tail -1)"
for i in 1 2 3; do
  [ "$i" = "$L" ] || expect "follower fe$i changed nothing in StarMgr" "0" "$(count_since_start $i "$CORRECTED")"
done

section "the leader dies right after the drift appears"
# The leader has just run its check, so its next one is most of a minute away: it cannot correct this one.
expect "drift: StarMgr now holds 7" "7" "$(starmgr $L set $G 7)"
kill -9 "$(fe_pid $L)"; sleep 2
O=$([ "$L" = 1 ] && echo 2 || echo 1)
for n in $(seq 1 40); do NL=$(leader $O); [ -n "$NL" ] && [ "$NL" != "$L" ] && break; sleep 2; done
[ -n "$NL" ] && [ "$NL" != "$L" ] && ok "a new leader was elected (fe$NL)" || abort "no new leader"
refute "the old leader did not correct it before it died" "had replica number 7" "$(lines_since_start $L "$CORRECTED")"
expect "the new leader still stores 2" "etl(replica_number=2)" "$(names $NL)"
wait_group $NL $G 2 120 && ok "the new leader set StarMgr back to the stored value" || bad "the new leader did not correct StarMgr" "$(starmgr $NL get $G)"
expect "the new leader logged what it found" "had replica number 7 in StarMgr, the stored value is 2" "$(lines_since_start $NL "$CORRECTED" | tail -1)"
start_clean $L; fe_wait $L

section "an ALTER that committed is left alone"
BEFORE=$(count_since_start $NL "$CORRECTED")
expect "ALTER to another value on the new leader" "OK" "$(sql $NL "ALTER WAREHOUSE etl SET ('replica_number' = '3')")"
sleep 70
expect "after a full check interval StarMgr still holds 3" "3" "$(starmgr $NL get $G)"
expect "and the FE stores 3" "etl(replica_number=3)" "$(names $NL)"
expect "the leader corrected nothing in that time" "$BEFORE" "$(count_since_start $NL "$CORRECTED")"

finish
