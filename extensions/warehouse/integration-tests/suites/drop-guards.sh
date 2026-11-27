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

# Suite "drop-guards": what stops DROP WAREHOUSE. One FE, no compute node processes.
#   - a resource group that names the warehouse in its 'warehouses' property
#   - a node registered in the warehouse
#   - the hints in both error messages work as written
IT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
. "$IT_DIR/lib/host.sh"

last() { tail -1; }
rgw() { # rgw <group>: the 'warehouses' column of a resource group
  sql 1 "SHOW RESOURCE GROUP $1" | awk -F'\t' -v g="$1" '$1=="name"{for(i=1;i<=NF;i++) if($i=="warehouses") c=i} $1==g && c {print "[" $c "]"; exit}'; }

section "namespaces"
start_namespaces || abort "namespaces did not start" "$(cat "$H/ns.log")"

section "one FE with the extension"
mkhome 1; cp "$EXTJAR" "$H/fe1/lib/"; setconf 1 enable_warehouse_extension true
start_clean 1
fe_up 1 || abort "fe1 did not start" "$(tail -20 "$H/fe1/log/fe.out" 2>/dev/null)"
expect "the extension is loaded" "warehouse_extension loaded" "$(grep -h 'warehouse_extension loaded' $H/fe1/log/fe.log | tail -1)"
sql 1 "CREATE WAREHOUSE research" "CREATE WAREHOUSE etl" "CREATE USER alice" >/dev/null
expect "two warehouses exist" "research(replica_number=1) etl(replica_number=1)" "$(names 1)"

section "resource groups that name a warehouse"
OUT=$(sql 1 "CREATE RESOURCE GROUP rg_adhoc TO (user='alice') WITH ('cpu_weight_percent' = '10', 'mem_limit' = '20%', 'warehouses' = 'research')")
expect "CREATE RESOURCE GROUP with 'warehouses' = 'research'" "OK" "$(echo "$OUT" | last)"
OUT=$(sql 1 "CREATE RESOURCE GROUP rg_reports TO (user='alice', query_type in ('select')) WITH ('cpu_weight_percent' = '10', 'mem_limit' = '20%', 'warehouses' = 'etl, research')")
expect "CREATE RESOURCE GROUP with two warehouses" "OK" "$(echo "$OUT" | last)"
OUT=$(sql 1 "CREATE RESOURCE GROUP rg_plain TO (user='alice', source_ip='10.0.0.0/8') WITH ('cpu_weight' = '1', 'mem_limit' = '20%')")
expect "CREATE RESOURCE GROUP without 'warehouses'" "OK" "$(echo "$OUT" | last)"
expect "the FE shows the property of rg_adhoc" "[research]" "$(rgw rg_adhoc)"
expect "the FE shows the property of rg_reports" "[etl,research]" "$(rgw rg_reports)"
expect "the FE refuses an unknown warehouse in a resource group" "Unknown warehouse: nosuch" \
  "$(sql 1 "CREATE RESOURCE GROUP rg_bad TO (user='alice', query_type in ('insert')) WITH ('cpu_weight_percent' = '10', 'mem_limit' = '20%', 'warehouses' = 'nosuch')" | last)"

section "DROP WAREHOUSE is refused"
OUT=$(sql 1 "DROP WAREHOUSE research" | last)
echo "      $OUT"
expect "DROP names both resource groups, in order" "Cannot drop warehouse research: resource group(s) rg_adhoc, rg_reports name it in their 'warehouses' property" "$OUT"
expect "DROP says how to fix it" "ALTER RESOURCE GROUP <group> WITH ('warehouses' = '<warehouses to keep>')" "$OUT"
refute "the group without the property is not named" "rg_plain" "$OUT"
expect "DROP IF EXISTS is refused the same way" "Cannot drop warehouse research: resource group(s) rg_adhoc, rg_reports" "$(sql 1 "DROP WAREHOUSE IF EXISTS research" | last)"
expect "the warehouse is still there" "research(replica_number=1)" "$(names 1)"
expect "a node can still join the warehouse after the refused drop" "OK" "$(sql 1 "ALTER SYSTEM ADD COMPUTE NODE '10.86.0.51:9050' INTO WAREHOUSE research" | last)"
OUT=$(sql 1 "DROP WAREHOUSE research" | last)
echo "      $OUT"
expect "with a node and resource groups, the node is reported" "Cannot drop warehouse research: 1 node(s) are registered in it (10.86.0.51:9050)" "$OUT"
expect "the hint in that message works as written" "OK" "$(sql 1 "ALTER SYSTEM DROP COMPUTE NODE '10.86.0.51:9050' FROM WAREHOUSE research" | last)"
expect "etl is refused too" "Cannot drop warehouse etl: resource group(s) rg_reports name it" "$(sql 1 "DROP WAREHOUSE etl" | last)"

section "following the hint"
expect "ALTER RESOURCE GROUP ... WITH ('warehouses' = 'etl')" "OK" "$(sql 1 "ALTER RESOURCE GROUP rg_reports WITH ('warehouses' = 'etl')" | last)"
expect "rg_reports now names only etl" "[etl]" "$(rgw rg_reports)"
expect "research is still held by rg_adhoc" "Cannot drop warehouse research: resource group(s) rg_adhoc name it" "$(sql 1 "DROP WAREHOUSE research" | last)"
expect "an empty 'warehouses' value is accepted" "OK" "$(sql 1 "ALTER RESOURCE GROUP rg_adhoc WITH ('warehouses' = '')" | last)"
expect "the empty value lifts the limit" "[]" "$(rgw rg_adhoc)"
expect "DROP WAREHOUSE research now works" "OK" "$(sql 1 "DROP WAREHOUSE research" | last)"
expect "research is gone, etl is left" "etl(replica_number=1) " "$(names 1)"
refute "research is gone" "research" "$(names 1)"
expect "DROP RESOURCE GROUP rg_reports" "OK" "$(sql 1 "DROP RESOURCE GROUP rg_reports" | last)"
expect "DROP WAREHOUSE etl works once the group is dropped" "OK" "$(sql 1 "DROP WAREHOUSE etl" | last)"
expect "only the default warehouse is left" "default_warehouse() " "$(names 1)"
refute "no warning or error logged by the extension" "[" "$(grep -h -E ' (WARN|ERROR) .*\[(MultiWarehouseManager|LiveFeEnvironment|LostWarehouseWatch|WarehouseExtension)\.' $H/fe1/log/fe.log | head -3)"
refute "no concurrent-modification error in fe.log" "ConcurrentModification" "$(grep -h -m3 'ConcurrentModification' $H/fe1/log/fe.log)"

finish
