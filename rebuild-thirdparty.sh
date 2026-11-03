#!/bin/bash
# Refreshes thirdparty components inside the pre-built thirdparty that ships in
# the starrocks/dev-env-ubuntu:4.1.6 image, without re-running the full
# thirdparty/build-thirdparty.sh. build.sh copies from that pre-built tree, so a
# version bump in thirdparty/vars-*.sh alone does not reach the build output.
#
# Usage (inside the starrocks/dev-env-ubuntu:4.1.6 container):
#   ./rebuild-thirdparty.sh [target]
#   ./build.sh --fe --be -j$(nproc)
#
# With no target, refreshes every registered component. Run with an unknown
# target to list them.
#
# To add another target: write a rebuild_<name>() function below and add
# "<name>:description" to TARGET_DESCRIPTIONS.

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m'

info()  { echo -e "${GREEN}[INFO]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; }

TARGET_DESCRIPTIONS=(
    "pprof:Install the pprof binary pinned in thirdparty/vars-<arch>.sh (CN profile page)"
)

usage() {
    echo "Usage: $0 [target]"
    echo ""
    echo "Targets:"
    printf "  %-10s %s\n" "all" "Refresh every target below (default)"
    for entry in "${TARGET_DESCRIPTIONS[@]}"; do
        printf "  %-10s %s\n" "${entry%%:*}" "${entry#*:}"
    done
}

target_known() {
    local t="$1" entry
    for entry in "${TARGET_DESCRIPTIONS[@]}"; do
        [ "${entry%%:*}" = "${t}" ] && return 0
    done
    return 1
}

STARROCKS_HOME="$(cd "$(dirname "$0")" && pwd)"
SOURCE_TP="${STARROCKS_THIRDPARTY:-/var/local/thirdparty}"

rebuild_pprof() {
    # vars.sh resolves the architecture-specific file (vars-x86_64.sh, vars-aarch64.sh)
    # relative to TP_DIR, which its callers are expected to set
    TP_DIR="${STARROCKS_HOME}/thirdparty"
    # shellcheck disable=SC1091
    . "${TP_DIR}/vars.sh"
    if [ -z "${PPROF_DOWNLOAD}" ] || [ -z "${PPROF_MD5SUM}" ]; then
        error "PPROF_DOWNLOAD or PPROF_MD5SUM is not set by thirdparty/vars.sh"
        exit 1
    fi

    local dest="${SOURCE_TP}/installed/flamegraph/pprof"
    if [ -f "${dest}" ] && [ "$(md5sum "${dest}" | cut -d' ' -f1)" = "${PPROF_MD5SUM}" ]; then
        info "pprof: ${dest} already matches ${PPROF_MD5SUM}"
        return
    fi

    local tmp
    tmp="$(mktemp)"
    info "pprof: downloading ${PPROF_DOWNLOAD}"
    curl -fsSL --retry 3 -o "${tmp}" "${PPROF_DOWNLOAD}"
    local got
    got="$(md5sum "${tmp}" | cut -d' ' -f1)"
    if [ "${got}" != "${PPROF_MD5SUM}" ]; then
        rm -f "${tmp}"
        error "pprof: md5 ${got} does not match PPROF_MD5SUM ${PPROF_MD5SUM}"
        exit 1
    fi

    mkdir -p "$(dirname "${dest}")"
    install -m 0755 "${tmp}" "${dest}"
    rm -f "${tmp}"
    info "pprof: installed ${dest} (${PPROF_MD5SUM})"
}

target="${1:-all}"
if [ "${target}" = "all" ]; then
    for entry in "${TARGET_DESCRIPTIONS[@]}"; do
        "rebuild_${entry%%:*}"
    done
elif target_known "${target}"; then
    "rebuild_${target}"
else
    error "Unknown target: ${target}"
    usage
    exit 1
fi
