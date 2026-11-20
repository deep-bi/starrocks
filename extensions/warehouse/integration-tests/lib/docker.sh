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

# Helpers for the suite that runs on the official StarRocks image. Source this file.
#
# Set by run.sh:
#   SUITE_DIR    work directory of the suite: generated configs, the MinIO keys, logs
#   EXT_JAR      the extension jar under test
#   FE_VERSION   the FE version that jar was built for
#   IMAGE        the StarRocks all-in-one image to run
#   PROJECT_DIR  the extension's source tree; optional, used for the build inside the image
LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$LIB_DIR/checks.sh"

# Every container and the network carry this prefix. Addresses are fixed in 172.30.86.0/24:
#   .5 MinIO, .10 FE, .21 compute node in default_warehouse, .22 compute node in the research warehouse
d() { timeout 120 docker "$@"; }
q() { d exec -i whext-fe mysql -h127.0.0.1 -P9030 -uroot --force --table "$@" 2>&1; }
# as <user> <statements>: the same, as another user
as() { local user=$1; shift; d exec -i whext-fe mysql -h127.0.0.1 -P9030 -u"$user" --force --table -e "$1" 2>&1; }

# "ip=alive(warehouse)" for every compute node
alive() { q -e "SHOW COMPUTE NODES\G" | awk '/^ *IP:/{ip=$2} /^ *Alive:/{a=$2} /^ *WarehouseName:/{printf "%s=%s(%s) ", ip, a, $2}'; }
nalive() { q -e "SHOW COMPUTE NODES\G" | grep -c '^ *Alive: true'; }
# waitalive <n>: waits until exactly n compute nodes are alive
waitalive() { local n; for n in $(seq 1 40); do [ "$(nalive)" = "$1" ] && return 0; sleep 3; done; return 1; }

fe_sql_up() { local n; for n in $(seq 1 60); do d exec whext-fe mysql -h127.0.0.1 -P9030 -uroot -e 'show frontends' >/dev/null 2>&1 && return 0; sleep 3; done; return 1; }
fe_restart() { d restart -t 10 whext-fe >/dev/null; fe_sql_up; waitalive 2; }

# Removes everything the suite created. Runs when the suite ends, also after a failure or Ctrl-C.
docker_cleanup() {
  if [ "$FAIL" -gt 0 ] || [ "${KEEP:-0}" = 1 ]; then
    d cp -q whext-fe:/data/deploy/starrocks/fe/log/fe.log "$SUITE_DIR/fe.log" >/dev/null 2>&1
  fi
  d rm -f whext-build whext-fe whext-cn-default whext-cn-research whext-minio >/dev/null 2>&1
  d network rm whext-net >/dev/null 2>&1
  rm -f "$SUITE_DIR/minio.env"
  return 0
}

# collect_testlibs <dir>: copies the JUnit and JMockit jars the unit tests need from the local Maven repository
# or the Gradle cache. The versions are the ones fe/pom.xml manages (junit.version, junit-platform.version,
# jmockit). Returns non-zero when a jar is missing.
collect_testlibs() {
  local dest=$1 name found
  mkdir -p "$dest"
  for name in apiguardian-api-1.1.2 jmockit-1.49.4 junit-jupiter-api-5.8.2 junit-jupiter-engine-5.8.2 \
      junit-jupiter-params-5.8.2 junit-platform-commons-1.8.2 junit-platform-engine-1.8.2 \
      junit-platform-launcher-1.8.2 opentest4j-1.2.0; do
    found=$(find "$HOME/.m2/repository" "${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1" \
      -name "$name.jar" 2>/dev/null | head -1)
    [ -n "$found" ] || return 1
    cp "$found" "$dest/"
  done
}
