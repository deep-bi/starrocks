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

# Integration tests of the Warehouse Extension for StarRocks: real FEs, real statements, one PASS or FAIL line
# per check. See README.md next to this file.
set -u
IT_DIR="$(cd "$(dirname "$0")" && pwd)"
HOST_SUITES="lifecycle lost-metadata drop-guards replica-drift"
ALL_SUITES="$HOST_SUITES docker"

usage() {
  cat <<EOF
Usage: run.sh [options] [suite ...]

Suites:
  lifecycle       3 FEs on this host: statements, replay, checkpoint, leader change, settings, jar removal
  lost-metadata   3 FEs on this host: the alarm for lost warehouse metadata and the repair
  drop-guards     1 FE on this host: what stops DROP WAREHOUSE
  replica-drift   3 FEs on this host: a worker group whose replica number in StarMgr is not the stored one
  docker          official image with compute nodes and MinIO: routing, node loss, the USAGE gate
  host            the four host suites (the default)
  all             the host suites and docker

Options:
  --fe-dist DIR   fe directory of a StarRocks distribution, used by the host suites;
                  default: output/fe of this source tree                              (or FE_DIST)
  --jar FILE      extension jar to test; default: the newest of the module's target
                  directory and output/fe/lib                                         (or EXT_JAR)
  --project DIR   the extension module; default: the parent of this directory         (or PROJECT_DIR)
  --image NAME    image for the docker suite; default: starrocks/allin1-ubuntu:<FE version of the jar>
  --work DIR      where FE homes and logs go; default: a new directory under \${TMPDIR:-/tmp}
  --keep          keep FE homes and logs of suites that passed
  --list          print the suite names
  -h, --help      this text

Exit status: 0 when every check passed, 1 when a check failed, 2 when the run could not start.
EOF
}

die() { echo "run.sh: $*" >&2; exit 2; }
abs_dir() { (cd "$1" 2>/dev/null && pwd); }

# prop_from_jar <jar> <entry name suffix> <key>: one value from a properties file inside a jar
prop_from_jar() {
  python3 - "$1" "$2" "$3" <<'PY'
import sys, zipfile
jar, suffix, key = sys.argv[1:4]
with zipfile.ZipFile(jar) as z:
    for name in z.namelist():
        if name.endswith(suffix):
            for line in z.read(name).decode().splitlines():
                if line.startswith(key + "="):
                    print(line.split("=", 1)[1].strip())
                    sys.exit(0)
sys.exit(1)
PY
}

# ---------------------------------------------------------------- arguments

PROJECT_DIR=${PROJECT_DIR:-}
FE_DIST=${FE_DIST:-}
EXT_JAR=${EXT_JAR:-}
IMAGE=${IMAGE:-}
WORK_DIR=${WORK_DIR:-}
KEEP=${KEEP:-0}
requested=""
while [ $# -gt 0 ]; do
  case "$1" in
    --fe-dist|--jar|--project|--image|--work)
      [ $# -ge 2 ] || die "$1 needs a value"
      case "$1" in
        --fe-dist) FE_DIST=$2 ;;
        --jar)     EXT_JAR=$2 ;;
        --project) PROJECT_DIR=$2 ;;
        --image)   IMAGE=$2 ;;
        --work)    WORK_DIR=$2 ;;
      esac
      shift 2 ;;
    --keep) KEEP=1; shift ;;
    --list) echo $ALL_SUITES | tr ' ' '\n'; exit 0 ;;
    -h|--help) usage; exit 0 ;;
    host) requested="$requested $HOST_SUITES"; shift ;;
    all)  requested="$requested $ALL_SUITES"; shift ;;
    lifecycle|lost-metadata|drop-guards|replica-drift|docker) requested="$requested $1"; shift ;;
    *) usage >&2; die "unknown argument: $1" ;;
  esac
done
[ -n "$requested" ] || requested=$HOST_SUITES
# Each suite once, in the fixed order.
suites=""
for s in $ALL_SUITES; do
  case " $requested " in *" $s "*) suites="$suites $s" ;; esac
done
need_host=0; need_docker=0
for s in $suites; do
  if [ "$s" = docker ]; then need_docker=1; else need_host=1; fi
done

# ---------------------------------------------------------------- what to test

command -v python3 >/dev/null || die "missing command: python3"

# This directory is extensions/warehouse/integration-tests of the StarRocks source tree: the module is its
# parent, and the tree's root is where build.sh puts the FE distribution.
if [ -z "$PROJECT_DIR" ] && [ -f "$IT_DIR/../pom.xml" ]; then
  PROJECT_DIR=$IT_DIR/..
fi
if [ -n "$PROJECT_DIR" ]; then
  PROJECT_DIR=$(abs_dir "$PROJECT_DIR") || die "--project is not a directory"
fi
REPO_DIR=
if [ -f "$IT_DIR/../../../build.sh" ] && [ -f "$IT_DIR/../../../fe/pom.xml" ]; then
  REPO_DIR=$(abs_dir "$IT_DIR/../../..")
fi
if [ -z "$EXT_JAR" ]; then
  # Maven writes the jar to the module's target directory, and build.sh copies it into the distribution.
  # The newest one wins.
  for candidate in "$PROJECT_DIR"/target/starrocks-warehouse*ext.jar "$REPO_DIR"/output/fe/lib/starrocks-warehouse*ext.jar; do
    [ -f "$candidate" ] || continue
    if [ -z "$EXT_JAR" ] || [ "$candidate" -nt "$EXT_JAR" ]; then EXT_JAR=$candidate; fi
  done
fi
[ -n "$EXT_JAR" ] && [ -f "$EXT_JAR" ] || die "extension jar not found. Build it with ./build.sh --fe, or pass --jar"
if [ -z "$FE_DIST" ] && [ -n "$REPO_DIR" ] && [ -f "$REPO_DIR/output/fe/bin/start_fe.sh" ]; then
  FE_DIST=$REPO_DIR/output/fe
fi
EXT_JAR=$(abs_dir "$(dirname "$EXT_JAR")")/$(basename "$EXT_JAR")
FE_VERSION=$(prop_from_jar "$EXT_JAR" build-info.properties fe.version) \
  || die "$EXT_JAR has no build-info.properties with fe.version; is it the extension jar?"

# ---------------------------------------------------------------- what the suites need

if [ $need_host = 1 ]; then
  for c in unshare nsenter setsid ip curl md5sum; do
    command -v $c >/dev/null || die "missing command: $c"
  done
  [ -n "$FE_DIST" ] || die "the host suites need an FE $FE_VERSION distribution. Build one with ./build.sh --fe, or pass --fe-dist"
  FE_DIST=$(abs_dir "$FE_DIST") || die "--fe-dist is not a directory"
  for f in bin/start_fe.sh bin/stop_fe.sh conf/fe.conf; do
    [ -f "$FE_DIST/$f" ] || die "$FE_DIST does not look like an FE directory: $f is missing"
  done
  ls "$FE_DIST"/lib/mariadb-java-client-*.jar >/dev/null 2>&1 \
    || die "$FE_DIST/lib has no mariadb-java-client jar; the suites use it as their SQL client"
  fe_core=$(ls "$FE_DIST"/lib/fe-core-*.jar 2>/dev/null | head -1)
  [ -n "$fe_core" ] || die "$FE_DIST/lib has no fe-core jar"
  dist_version=$(prop_from_jar "$fe_core" com.starrocks/fe-core/pom.properties version) \
    || die "cannot read the FE version from $fe_core"
  [ "$dist_version" = "$FE_VERSION" ] \
    || die "the jar was built for FE $FE_VERSION, but $FE_DIST is FE $dist_version. Rebuild the jar or pass another --fe-dist"
  if [ -z "${JAVA_HOME:-}" ]; then
    for c in /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/java-17-openjdk; do
      if [ -x "$c/bin/java" ]; then JAVA_HOME=$c; break; fi
    done
  fi
  [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ] || die "set JAVA_HOME to a JDK 17"
  "$JAVA_HOME/bin/java" -version 2>&1 | grep -q 'version "17' \
    || echo "run.sh: warning: JAVA_HOME is not a JDK 17; the suites were only run with JDK 17" >&2
  unshare -rn true 2>/dev/null \
    || die "cannot create a user and network namespace (unshare -rn). Unprivileged user namespaces are disabled on this host"
fi
if [ $need_docker = 1 ]; then
  command -v docker >/dev/null || die "missing command: docker"
  docker info >/dev/null 2>&1 || die "docker is not running, or this user may not use it"
  [ -n "$IMAGE" ] || IMAGE=starrocks/allin1-ubuntu:$FE_VERSION
fi

if [ -z "$WORK_DIR" ]; then
  WORK_DIR=$(mktemp -d "${TMPDIR:-/tmp}/warehouse-ext-it.XXXXXX") || die "cannot create a work directory"
else
  mkdir -p "$WORK_DIR" || die "cannot create $WORK_DIR"
  WORK_DIR=$(abs_dir "$WORK_DIR")
fi
[ -n "$WORK_DIR" ] && [ -d "$WORK_DIR" ] || die "no work directory"
for s in $suites; do
  [ ! -e "$WORK_DIR/$s" ] || die "$WORK_DIR/$s exists already. Pass an empty --work directory, or remove it"
done

export FE_DIST EXT_JAR FE_VERSION IMAGE PROJECT_DIR KEEP
export JAVA_HOME=${JAVA_HOME:-}

echo "extension jar : $EXT_JAR (built for FE $FE_VERSION)"
[ $need_host = 1 ]   && echo "FE distribution: $FE_DIST"
[ $need_docker = 1 ] && echo "image         : $IMAGE"
echo "work directory: $WORK_DIR"
echo "suites        :$suites"

# ---------------------------------------------------------------- run

summary=""
failed=0
for s in $suites; do
  dir=$WORK_DIR/$s
  mkdir -p "$dir"
  echo
  echo "##### suite $s"
  started=$SECONDS
  SUITE_DIR=$dir bash "$IT_DIR/suites/$s.sh" 2>&1 | tee "$dir/suite.log"
  rc=${PIPESTATUS[0]}
  took=$((SECONDS - started))
  result=$(grep '^RESULT:' "$dir/suite.log" | tail -1 | sed 's/^RESULT: //')
  if [ -z "$result" ]; then
    result="did not finish (exit status $rc)"
    rc=1
  fi
  [ "$rc" -eq 0 ] || failed=1
  summary="$summary$(printf '%-14s %-36s %4d s' "$s" "$result" "$took")"$'\n'
  # A suite that passed leaves nothing worth keeping but its log.
  if [ "$rc" -eq 0 ] && [ "$KEEP" != 1 ]; then
    rm -rf "$dir/fe1" "$dir/fe2" "$dir/fe3" "$dir/jars" "$dir/testlibs"
  fi
done

echo
echo "##### summary"
printf '%s' "$summary"
echo "logs: $WORK_DIR/<suite>/suite.log"
if [ $failed = 1 ]; then
  echo "FE homes and logs of failed suites are kept in $WORK_DIR"
  exit 1
fi
exit 0
