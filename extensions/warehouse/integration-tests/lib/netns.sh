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

# Runs inside `unshare -rn`: a private bridge 10.86.0.0/24 with one network namespace per FE.
# Nothing here is reachable from the host network or the LAN.
# Usage: netns.sh <directory for the pid files>
set -e
DIR=$1
ip link set lo up
ip link add br0 type bridge
ip addr add 10.86.0.254/24 dev br0
ip link set br0 up
for i in 1 2 3; do
  unshare -n sleep infinity &
  P=$!
  sleep 0.3
  ip link add veth$i type veth peer name eth0 netns $P
  ip link set veth$i master br0 up
  nsenter -t $P -n sh -c "ip link set lo up; ip addr add 10.86.0.$i/24 dev eth0; ip link set eth0 up"
  echo $P > "$DIR/ns$i.pid"
done
# Written last: the caller waits for this file, so all three FE namespaces exist by then.
echo $$ > "$DIR/ns0.pid"
echo "namespaces ready"
exec sleep infinity
