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

# Runs inside the FE container: streamload.sh <label> <first id> [header]
# Loads 200 rows (id, 'stream') into poc.t through the FE HTTP port.
label=$1
first=$2
header=$3
last=$((first + 199))
seq "$first" "$last" | sed 's/$/,stream/' > /tmp/sl.csv
args=(-s --location-trusted -u "${4:-root}:" -H "Expect:100-continue" -H "column_separator:," -H "label:$label")
if [ -n "$header" ]; then
  args+=(-H "$header")
fi
curl "${args[@]}" -T /tmp/sl.csv -XPUT http://127.0.0.1:8030/api/poc/t/_stream_load
echo
