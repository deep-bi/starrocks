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

# Helpers for the suites that run real FEs on this host, each in its own network namespace. Source this file.
#
# Set by run.sh:
#   SUITE_DIR   work directory of the suite: FE homes, pid files, logs
#   FE_DIST     the fe directory of a StarRocks distribution; only read, never changed
#   EXT_JAR     the extension jar under test
#   FE_VERSION  the FE version that jar was built for
#   JAVA_HOME   a JDK 17
LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$LIB_DIR/checks.sh"

H=$SUITE_DIR
FEOUT=$FE_DIST
EXTJAR=$EXT_JAR
EXTJAR_NAME=$(basename "$EXT_JAR")
LABEL='"clazz":"bi.deep.warehouse"'
JDBC_JAR=$(ls "$FEOUT"/lib/mariadb-java-client-*.jar 2>/dev/null | head -1)
export JAVA_HOME

# ---------------------------------------------------------------- namespaces

# Namespace 0 holds the bridge and is where the SQL client and curl run. Namespaces 1 to 3 hold one FE each,
# at 10.86.0.1 to 10.86.0.3. Compute nodes are only registered by address (10.86.0.50 and up), never started.
start_namespaces() {
  local i n
  for i in 0 1 2 3; do : > "$H/ns$i.pid"; done
  # From here on, whatever ends the suite also stops its FEs and removes the namespaces.
  trap stop_all EXIT
  setsid unshare -rn bash "$LIB_DIR/netns.sh" "$H" > "$H/ns.log" 2>&1 &
  for n in $(seq 1 50); do [ -s "$H/ns0.pid" ] && break; sleep 0.2; done
  [ -s "$H/ns0.pid" ]
}

nsx() { local i=$1; shift; nsenter -t "$(cat "$H/ns$i.pid")" -U -n --preserve-credentials "$@"; }

# ---------------------------------------------------------------- SQL

# sql <fe> <statement>...: runs the statements on one connection and prints each result as TSV.
# An error prints as "ERROR <code>: <message>". WAIT=<seconds> retries the connection, SQLUSER picks the user.
sql() {
  local i=$1; shift
  nsx 0 "$JAVA_HOME/bin/java" -Dwait=${WAIT:-0} -Duser=${SQLUSER:-root} -cp "$JDBC_JAR" "$LIB_DIR/Sql.java" \
    10.86.0.$i 9030 "$@" 2>&1 | grep -v '^SLF4J\|^WARNING\|^\[ WARN\]'
}

# ---------------------------------------------------------------- FE homes and processes

# setconf <fe> <key> <value>: sets one fe.conf key. A value of --remove deletes the key.
setconf() {
  local f=$H/fe$1/conf/fe.conf
  sed -i "/^[[:space:]]*$2[[:space:]]*=/d" "$f"
  [ "$3" = "--remove" ] || echo "$2 = $3" >> "$f"
}

# mkhome <fe>: builds an FE home in the suite directory. bin and conf are copied, everything else is a symlink
# into the distribution.
mkhome() {
  local i=$1 d=$H/fe$1 f
  mkdir -p "$d/lib" "$d/meta" "$d/log"
  cp -r "$FEOUT/bin" "$FEOUT/conf" "$d/"
  rm -f "$d/bin/fe.pid"
  for f in "$FEOUT"/lib/*; do
    # The suites decide which FE gets the extension jar, so a copy shipped inside the distribution is left out.
    case "$(basename "$f")" in starrocks-warehouse*ext.jar) continue ;; esac
    ln -sfn "$f" "$d/lib/$(basename "$f")"
  done
  for f in webroot spark-dpp hive-udf; do
    [ -e "$FEOUT/$f" ] && ln -sfn "$FEOUT/$f" "$d/$f"
  done
  sed -i 's/-Xmx8192m/-Xmx2048m/' "$d/conf/fe.conf"
  echo >> "$d/conf/fe.conf"
  echo "# --- integration test ---" >> "$d/conf/fe.conf"
  setconf $i priority_networks 10.86.0.0/24
  setconf $i run_mode shared_data
  setconf $i cloud_native_meta_port 6090
  setconf $i enable_load_volume_from_conf false
}

fe_start() { local i=$1; shift; ( cd "$H/fe$i" && nsx $i setsid bin/start_fe.sh --daemon "$@" ) >/dev/null 2>&1; }
fe_stop()  { local i=$1; ( cd "$H/fe$i" && nsx $i bin/stop_fe.sh ) >/dev/null 2>&1; }
fe_pid()   { cat "$H/fe$1/bin/fe.pid" 2>/dev/null; }
fe_alive() { local p=$(fe_pid $1); [ -n "$p" ] && kill -0 $p 2>/dev/null; }
# fe_wait <fe> [seconds]: returns once the FE accepts SQL connections, or after the timeout.
fe_wait()  { WAIT=${2:-150} sql $1 "SHOW FRONTENDS" >/dev/null 2>&1; }
# fe_up <fe> [seconds]: like fe_wait, but tells whether the FE answered.
fe_up()    { ! WAIT=${2:-150} sql $1 "SHOW FRONTENDS" | grep -q 'CONNECT FAILED'; }

# A stale pid file makes start_fe.sh refuse to start, so it is removed when the process is gone.
start_clean() { local i=$1; shift; fe_alive $i || rm -f "$H/fe$i/bin/fe.pid"; fe_start $i "$@"; }
restart()     { fe_stop $1; sleep 2; start_clean $1; fe_wait $1; }

# True when the FE wrote its pid file and that process is gone.
fe_exited() { local p=$(fe_pid $1); [ -n "$p" ] && ! kill -0 "$p" 2>/dev/null; }

# start_all <fe>...: starts the FEs at the same time and waits until each accepts SQL connections.
# When all FEs of a cluster start together, one can be elected leader and lose the election again a moment
# later. A stock FE exits in that case: StarMgr gives up in onBecomeLeader. In production a process supervisor
# starts it again, so this does the same, once, and says so.
start_all() {
  local i n again
  for i in "$@"; do start_clean $i; done
  for i in "$@"; do
    again=0
    for n in $(seq 1 40); do
      fe_up $i 5 && break
      if [ $again = 0 ] && fe_exited $i; then
        info "fe$i exited while starting, after losing a leader election; starting it once more"
        start_clean $i
        again=1
      fi
    done
  done
}

# True when the pid in fe.pid is a process started from this suite's FE home.
fe_is_ours() {
  local p=$(fe_pid $1)
  [ -n "$p" ] && [ -r "/proc/$p/cmdline" ] && tr '\0' ' ' < "/proc/$p/cmdline" | grep -qF -- "$H/fe$1/"
}

# Stops every FE of the suite and removes the namespaces. Runs when the suite ends, also after a failure or
# Ctrl-C. Processes are found by the pids the suite recorded, never by name.
stop_all() {
  local i p any=0
  for i in 1 2 3; do
    if [ -d "$H/fe$i" ] && fe_alive $i; then fe_stop $i; any=1; fi
  done
  [ $any = 1 ] && sleep 3
  for i in 1 2 3; do
    fe_alive $i || continue
    if fe_is_ours $i; then
      kill -9 "$(fe_pid $i)" 2>/dev/null
    else
      echo "WARN  process $(fe_pid $i) from fe$i/bin/fe.pid is alive but does not look like this suite's FE; not killed"
    fi
  done
  for i in 1 2 3 0; do
    p=$(cat "$H/ns$i.pid" 2>/dev/null)
    [ -n "$p" ] && kill "$p" 2>/dev/null
  done
  return 0
}

# ---------------------------------------------------------------- reading FE state

leader()   { sql $1 "SHOW FRONTENDS" | awk -F'\t' '$8=="LEADER"{print $3}' | sed 's/10.86.0.//'; }
# names <fe>: "name(properties) " for every warehouse the FE lists, in the FE's order
names()    { sql $1 "SHOW WAREHOUSES" | awk -F'\t' 'NR>2 && $2!="" && $1 ~ /^[0-9]+$/ {printf "%s(%s) ", $2, $13}'; }
newest_image() { ls "$H/fe$1/meta/image/v2/" 2>/dev/null | grep '^image\.[0-9]' | sort -V | tail -1; }
# labels_in_image <fe>: how many extension warehouses the FE's newest image holds
labels_in_image() {
  local f=$H/fe$1/meta/image/v2/$(newest_image $1)
  [ -f "$f" ] && grep -a -F -o "$LABEL" "$f" | wc -l || echo "no-image"
}
# checkpoint <fe to ask> <fe to watch>: asks for an image and waits until the watched FE has a new one
checkpoint() {
  local n before=$(newest_image $2)
  sql $1 "ALTER SYSTEM CREATE IMAGE" >/dev/null
  for n in $(seq 1 50); do [ "$(newest_image $2)" != "$before" ] && return 0; sleep 3; done
  return 1
}
# The part of fe.log written since the FE's last start.
log_since_start() {
  local log=$H/fe$1/log/fe.log
  local from=$(grep -n 'start to load extensions' "$log" | tail -1 | cut -d: -f1)
  tail -n +"${from:-1}" "$log"
}
# count_since_start <fe> <pattern>: number of matching lines since the FE's last start
count_since_start() { log_since_start $1 | grep -c -- "$2"; }
# lines_since_start <fe> <pattern>: the matching lines themselves
lines_since_start() { log_since_start $1 | grep -- "$2"; }
