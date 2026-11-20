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

# Runs inside the FE container: 300 queries on the research warehouse in one session, as root.
# Prints the mean time per query in microseconds.
f=/tmp/timeq.sql
echo "SET warehouse = 'research';" > "$f"
for i in $(seq 1 300); do
  echo "SELECT sum(id) FROM poc.t;" >> "$f"
done
start=$(date +%s%N)
mysql -h127.0.0.1 -P9030 -uroot < "$f" > /dev/null
end=$(date +%s%N)
echo $(( (end - start) / 300000 ))
