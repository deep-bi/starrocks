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

# Suite "lifecycle": a warehouse from CREATE to DROP in real FEs, one FE first, then three. No compute node
# processes.
#   - loading, CREATE / ALTER / DROP WAREHOUSE, node assignment, SET warehouse
#   - restart from the journal, checkpoint, restart from the image
#   - the settings: switched off, a typo, a jar built for another FE version
#   - followers, live replay, leader change, full cluster restart
#   - privileges
#   - clean removal: drop everything, checkpoint, remove the jar, start stock FEs
IT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
. "$IT_DIR/lib/host.sh"

section "namespaces"
start_namespaces && ok "isolated namespaces are up" || abort "namespaces did not start" "$(cat "$H/ns.log")"

section "1 FE: load, statements"
mkhome 1
cp "$EXTJAR" "$H/fe1/lib/"
setconf 1 enable_warehouse_extension true
start_clean 1
fe_up 1 || abort "fe1 did not start" "$(tail -20 "$H/fe1/log/fe.out" 2>/dev/null)"
expect "extension loads in a real FE" "WarehouseManager -> bi.deep.starrocks.extension.warehouse.UserWarehouseManager" \
  "$(grep 'warehouse_extension loaded' $H/fe1/log/fe.log | tail -1)"
expect "FE logs the extension by name" "Loaded extension: warehouse_extension" "$(grep 'Loaded extension' $H/fe1/log/fe.log | tail -1)"

out=$(sql 1 "CREATE WAREHOUSE research COMMENT 'ad hoc research'")
expect "CREATE WAREHOUSE (real StarMgr worker group)" "OK" "$out"
expect "duplicate name keeps the FE error code 5902" "ERROR 5902" "$(sql 1 "CREATE WAREHOUSE research")"
expect "CREATE ... IF NOT EXISTS on an existing warehouse" "OK" "$(sql 1 "CREATE WAREHOUSE IF NOT EXISTS research")"
expect "name differing only by case is rejected" "differs only by case" "$(sql 1 "CREATE WAREHOUSE Research")"
expect "CREATE with replica_number" "OK" "$(sql 1 "CREATE WAREHOUSE etl PROPERTIES ('replica_number' = '2')")"
expect "unknown property is rejected" "Unknown warehouse property" "$(sql 1 "CREATE WAREHOUSE bad PROPERTIES ('size' = 'x1')")"
expect "invalid replica_number is rejected" "Invalid replica_number" "$(sql 1 "CREATE WAREHOUSE bad PROPERTIES ('replica_number' = '0')")"
out=$(sql 1 "SHOW WAREHOUSES")
expect "SHOW WAREHOUSES lists the new warehouses" "research" "$out"
expect "SHOW WAREHOUSES shows the property" "replica_number=2" "$out"
expect "SHOW WAREHOUSES shows the comment" "ad hoc research" "$out"
refute "rejected warehouses were not created" "bad" "$out"

sql 1 "ALTER SYSTEM ADD COMPUTE NODE '10.86.0.51:9050' INTO WAREHOUSE research" "ALTER SYSTEM ADD COMPUTE NODE '10.86.0.52:9050' INTO WAREHOUSE etl" "ALTER SYSTEM ADD COMPUTE NODE '10.86.0.50:9050'" >/dev/null
out=$(sql 1 "SHOW NODES FROM WAREHOUSE research")
expect "SHOW NODES FROM WAREHOUSE lists the node" "10.86.0.51" "$out"
refute "SHOW NODES FROM WAREHOUSE lists only that warehouse" "10.86.0.52" "$out"
expect "SHOW COMPUTE NODES names the warehouse" "research" "$(sql 1 "SHOW COMPUTE NODES" | grep '10.86.0.51')"
out=$(sql 1 "SET warehouse = 'research'" "SELECT @@warehouse" "SET warehouse = 'nosuch'")
expect "SET warehouse selects a user warehouse" "research" "$(echo "$out" | sed -n '/SELECT @@warehouse/,/rows)/p')"
expect "SET warehouse to an unknown name fails" "nosuch not exist" "$out"
expect "DROP is refused while nodes exist" "1 node(s) are registered in it (10.86.0.51:9050)" "$(sql 1 "DROP WAREHOUSE research")"
expect "default_warehouse cannot be dropped (the FE's analyzer refuses first)" "Can't drop the default_warehouse" "$(sql 1 "DROP WAREHOUSE default_warehouse")"
expect "SUSPEND is reported as unsupported" "not supported" "$(sql 1 "SUSPEND WAREHOUSE research")"
expect "ALTER replica_number (real StarMgr update)" "OK" "$(sql 1 "ALTER WAREHOUSE etl SET ('replica_number' = '3')")"
expect "ALTER is visible" "replica_number=3" "$(sql 1 "SHOW WAREHOUSES")"
STATE1=$(names 1)

section "1 FE: restart from the journal, checkpoint, restart from the image"
restart 1
expect "state after restart (journal replay, no image)" "$STATE1" "$(names 1)"
expect "node assignment after restart" "research" "$(sql 1 "SHOW COMPUTE NODES" | grep '10.86.0.51')"
checkpoint 1 1 && ok "checkpoint finished" || bad "checkpoint did not finish"
expect "image holds both warehouses under the extension's label" "2" "$(labels_in_image 1)"
sql 1 "ALTER WAREHOUSE etl SET ('replica_number' = '1')" "CREATE WAREHOUSE tmp_wh" "DROP WAREHOUSE tmp_wh" "CREATE WAREHOUSE bi_wh COMMENT 'dashboards'" >/dev/null
STATE2=$(names 1)
restart 1
expect "state after restart (image + later journal)" "$STATE2" "$(names 1)"
expect "restart read the image" "1" "$(count_since_start 1 'start load image' | sed 's/^[1-9][0-9]*$/1/')"
expect "no journal entry was skipped" "0" "$(count_since_start 1 'SKIP JOURNAL')"

section "1 FE: setting off, typo, wrong FE version"
fe_stop 1; sleep 2; setconf 1 enable_warehouse_extension false; start_clean 1; fe_wait 1
expect "setting off: existing warehouses are still there" "$STATE2" "$(names 1)"
expect "setting off: CREATE is refused" "Creating warehouses is disabled. Set enable_warehouse_extension = true" "$(sql 1 "CREATE WAREHOUSE blocked")"
expect "setting off: IF NOT EXISTS on an existing warehouse is a no-op" "OK" "$(sql 1 "CREATE WAREHOUSE IF NOT EXISTS research")"
expect "setting off: ALTER still works" "OK" "$(sql 1 "ALTER WAREHOUSE etl SET ('replica_number' = '2')")"
fe_stop 1; sleep 2; setconf 1 enable_warehouse_extension ture; start_clean 1; sleep 12
fe_alive 1 && bad "typo in the setting: FE kept running" || ok "typo in the setting stops the FE"
expect "typo: the reason is in fe.out" "Invalid value for enable_warehouse_extension in fe.conf: 'ture'" "$(tail -20 $H/fe1/log/fe.out)"
setconf 1 enable_warehouse_extension true

# A copy of the jar that claims to be built for another FE version.
OTHER=0.0.0
OTHER_JAR_NAME=starrocks-warehouse-$OTHER-ext.jar
mkdir -p "$H/jars"; cp "$EXTJAR" "$H/jars/$OTHER_JAR_NAME"
python3 - "$H/jars/$OTHER_JAR_NAME" "$FE_VERSION" "$OTHER" <<'PY'
import sys, zipfile, os
p, real, other = sys.argv[1], sys.argv[2].encode(), sys.argv[3].encode()
tmp = p + ".tmp"
with zipfile.ZipFile(p) as zin, zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        data = zin.read(item.filename)
        if item.filename.endswith("build-info.properties"):
            data = data.replace(b"fe.version=" + real, b"fe.version=" + other)
        zout.writestr(item, data)
os.replace(tmp, p)
PY
mv "$H/fe1/lib/$EXTJAR_NAME" "$H/jars/"; cp "$H/jars/$OTHER_JAR_NAME" "$H/fe1/lib/"
start_clean 1; sleep 12
fe_alive 1 && bad "jar for another FE version: FE kept running" || ok "jar built for another FE version stops the FE"
expect "version mismatch: the reason is in fe.out" "was built for StarRocks FE $OTHER but this FE is $FE_VERSION" "$(tail -20 $H/fe1/log/fe.out)"
setconf 1 warehouse_extension_skip_version_check true; start_clean 1; fe_wait 1
expect "skip_version_check lets the same jar load" "bi_wh" "$(names 1)"
fe_stop 1; sleep 2; setconf 1 warehouse_extension_skip_version_check --remove
rm -f "$H/fe1/lib/$OTHER_JAR_NAME"; cp "$EXTJAR" "$H/fe1/lib/"
start_clean 1; fe_wait 1
STATE3=$(names 1)

section "3 FEs: followers, checkpoint, leader change"
for i in 2 3; do mkhome $i; cp "$EXTJAR" "$H/fe$i/lib/"; setconf $i enable_warehouse_extension true; done
sql 1 "ALTER SYSTEM ADD FOLLOWER '10.86.0.2:9010'" "ALTER SYSTEM ADD FOLLOWER '10.86.0.3:9010'" >/dev/null
start_clean 2 --helper 10.86.0.1:9010; start_clean 3 --helper 10.86.0.1:9010
fe_wait 2; fe_wait 3; sleep 6
expect "follower fe2 has the leader's warehouses (image download + replay)" "$STATE3" "$(names 2)"
expect "follower fe3 has the leader's warehouses" "$STATE3" "$(names 3)"
expect "DDL sent to a follower is executed" "OK" "$(sql 2 "CREATE WAREHOUSE from_follower COMMENT 'sent to fe2'")"
sql 1 "ALTER WAREHOUSE research SET ('replica_number' = '2')" >/dev/null; sleep 4
STATE4=$(names 1)
expect "live replay on fe2" "$STATE4" "$(names 2)"
expect "live replay on fe3" "$STATE4" "$(names 3)"
expect "the follower's view includes its own statement" "from_follower" "$STATE4"
checkpoint 1 1 && ok "checkpoint in the 3-FE cluster" || bad "3-FE checkpoint did not finish"
sleep 8
N=$(echo "$STATE4" | tr ' ' '\n' | grep -c '(')
N=$((N-1))
for i in 1 2 3; do expect "fe$i image holds all $N user warehouses" "$N" "$(labels_in_image $i)"; done
M1=$(md5sum < "$H/fe1/meta/image/v2/$(newest_image 1)"); M2=$(md5sum < "$H/fe2/meta/image/v2/$(newest_image 2)"); M3=$(md5sum < "$H/fe3/meta/image/v2/$(newest_image 3)")
[ "$M1" = "$M2" ] && [ "$M2" = "$M3" ] && ok "the three FEs hold the same image" || bad "images differ" "$M1 $M2 $M3"

L=$(leader 1); info "leader is fe$L, killing it"
kill -9 "$(fe_pid $L)"; sleep 2
O=$([ "$L" = 1 ] && echo 2 || echo 1)
for n in $(seq 1 40); do NL=$(leader $O); [ -n "$NL" ] && [ "$NL" != "$L" ] && break; sleep 2; done
[ -n "$NL" ] && [ "$NL" != "$L" ] && ok "a new leader was elected (fe$NL)" || bad "no new leader"
expect "the new leader has every warehouse" "$STATE4" "$(names $NL)"
expect "CREATE on the new leader" "OK" "$(sql $NL "CREATE WAREHOUSE after_failover")"
expect "DROP on the new leader" "OK" "$(sql $NL "DROP WAREHOUSE bi_wh")"
STATE5=$(names $NL)
start_clean $L; fe_wait $L; sleep 6
expect "the old leader rejoins and catches up" "$STATE5" "$(names $L)"
for i in 1 2 3; do fe_stop $i; done; sleep 3
start_all 1 2 3; sleep 6
for i in 1 2 3; do expect "fe$i after a full cluster restart" "$STATE5" "$(names $i)"; done
for i in 1 2 3; do expect "fe$i skipped no journal entry" "0" "$(count_since_start $i 'SKIP JOURNAL')"; done

section "privileges"
L=$(leader 1)
sql $L "CREATE USER analyst" "GRANT USAGE ON WAREHOUSE research TO USER analyst" >/dev/null
out=$(SQLUSER=analyst sql $L "SET warehouse = 'research'" "SELECT @@warehouse" "SET warehouse = 'etl'")
expect "USAGE lets a user select the warehouse" "research" "$(echo "$out" | sed -n '/SELECT @@warehouse/,/rows)/p')"
expect "without USAGE the warehouse is refused" "ERROR 5203" "$out"

section "clean removal: drop everything, checkpoint, remove the jar"
sql $L "ALTER SYSTEM DROP COMPUTE NODE '10.86.0.51:9050' FROM WAREHOUSE research" "ALTER SYSTEM DROP COMPUTE NODE '10.86.0.52:9050' FROM WAREHOUSE etl" >/dev/null
for w in $(sql $L "SHOW WAREHOUSES" | awk -F'\t' '$1 ~ /^[1-9][0-9]*$/ {print $2}'); do sql $L "DROP WAREHOUSE $w" >/dev/null; done
expect "all user warehouses dropped" "default_warehouse() " "$(names $L)"
checkpoint $L $L && ok "checkpoint after the drops" || bad "checkpoint after the drops did not finish"
sleep 8
for i in 1 2 3; do expect "fe$i image has no extension data left" "0" "$(labels_in_image $i)"; done
for i in 1 2 3; do fe_stop $i; done; sleep 3
for i in 1 2 3; do rm -f "$H/fe$i/lib/$EXTJAR_NAME"; setconf $i enable_warehouse_extension --remove; done
start_all 1 2 3; sleep 5
L=$(leader 1)
expect "stock FEs start from that image" "default_warehouse" "$(names $L)"
expect "the stock manager is back" "Multi-Warehouse is not implemented" "$(sql $L "CREATE WAREHOUSE x")"
for i in 1 2 3; do
  expect "fe$i (no jar) skipped no journal entry" "0" "$(count_since_start $i 'SKIP JOURNAL')"
  expect "fe$i (no jar) skipped nothing in the image" "0" "$(count_since_start $i 'will skip the rest')"
  expect "fe$i (no jar) met no unknown subtype" "0" "$(count_since_start $i 'unknown subtype')"
done

finish
