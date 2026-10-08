#!/bin/bash
# Rebuilds a single thirdparty library against the pre-built thirdparty that
# ships in the starrocks/dev-env-centos7:3.5.11 image, without re-running the
# full thirdparty/build-thirdparty.sh (which builds everything from scratch).
#
# Usage (inside the starrocks/dev-env-centos7:3.5.11 container):
#   ./rebuild-thirdparty.sh [target]
#   ./build.sh --be -j$(nproc)
#
# With no target, rebuilds every registered library (both libevent and
# libserdes are always needed together for a BE build with HTTPS + mTLS
# support). Pass a specific target to rebuild just that one, e.g. while
# iterating on a single patch. Run with an unknown target to list them.
#
# To add another target: write a rebuild_<name>() function below and add
# [<name>]="description" to TARGET_DESCRIPTIONS.

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

info()  { echo -e "${GREEN}[INFO]${NC} $1"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; }

# Each target is a rebuild_<name>() function defined below plus one line
# here; add both and it becomes selectable, with no other list to update.
# (Plain array + case, not an associative array, so this runs on bash 3.2 too.)
#
# Order matters for `all`: rebuild_libevent ends by copying the whole
# ${SOURCE_TP}/installed tree aside, rebuilding into the copy, then deleting
# the original and moving the copy back — so it must run before any target
# (like libserdes) that builds straight into ${SOURCE_TP}/installed in
# place, or that target's output would be wiped out by libevent's swap.
TARGET_DESCRIPTIONS=(
    "libevent:Rebuild libevent with OpenSSL support (needed for BE/CN HTTPS)"
    "libserdes:Rebuild libserdes against the deep-bi mTLS branch tip"
)

usage() {
    echo "Usage: $0 [target]"
    echo ""
    echo "Targets:"
    printf "  %-10s %s\n" "all" "Rebuild every target below (default)"
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

TARGET="${1:-all}"
if [ "${TARGET}" != "all" ] && ! target_known "${TARGET}"; then
    error "Unknown target '${TARGET}'"
    usage
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TP_DIR="${SCRIPT_DIR}/thirdparty"
SOURCE_TP="${STARROCKS_THIRDPARTY:-/var/local/thirdparty}"

if [ -z "${STARROCKS_GCC_HOME}" ]; then
    error "STARROCKS_GCC_HOME environment variable is not set"
    exit 1
fi
export CC="${STARROCKS_GCC_HOME}/bin/gcc"
export CXX="${STARROCKS_GCC_HOME}/bin/g++"
export PATH="${STARROCKS_GCC_HOME}/bin:${PATH}"

if [ ! -d "${SOURCE_TP}/installed" ]; then
    error "Pre-built thirdparty not found at ${SOURCE_TP}/installed"
    error "This script is meant to run inside the StarRocks dev-env docker container."
    exit 1
fi

# extract_functions <name>... <file>
# Prints the named top-level function bodies from <file> verbatim, using a
# brace-depth counter rather than a sed range ending at the first "^}" (a
# nested if/for whose closing brace lands alone at column 0 would end that
# early). Never sources the file, since build-thirdparty.sh runs real build
# steps at the top level (cd $TP_DIR, ./download-thirdparty.sh, tool presence
# checks that call exit 1) interleaved between function definitions.
extract_functions() {
    local file="${@: -1}"
    local name_count=$(( $# - 1 ))
    local names=("${@:1:${name_count}}")
    awk -v names="${names[*]}" '
        BEGIN {
            n = split(names, arr, " ")
            for (i = 1; i <= n; i++) wanted[arr[i]] = 1
        }
        !in_fn {
            if (match($0, /^[A-Za-z_][A-Za-z0-9_]*\(\)[ \t]*\{/)) {
                name = $0
                sub(/\(\).*/, "", name)
                if (name in wanted) { in_fn = 1; depth = 0; buf = ""; found[name] = 1 } else next
            } else next
        }
        in_fn {
            buf = buf $0 "\n"
            depth += gsub(/\{/, "{")
            depth -= gsub(/\}/, "}")
            if (depth == 0) { printf "%s", buf; in_fn = 0 }
        }
        END {
            for (fn in wanted) if (!(fn in found)) { print "MISSING:" fn > "/dev/stderr"; missing = 1 }
            exit missing
        }
    ' "${file}"
}

# require_files <path>... — fail fast if any of the given files/dirs are missing.
require_files() {
    for f in "$@"; do
        if [ ! -e "${f}" ]; then
            error "Required file not found: ${f}"
            exit 1
        fi
    done
}

#####################################################################
# libevent: rebuild with OpenSSL support (BE/CN HTTPS)
#####################################################################
rebuild_libevent() {
    local custom_tp="${SOURCE_TP}/custom"
    local custom_installed="${custom_tp}/installed"
    local libevent_commit="24236aed01798303745470e6c498bf606e88724a"
    local libevent_short="24236ae"
    local libevent_download="https://github.com/libevent/libevent/archive/${libevent_short}.zip"
    local patch_file="${TP_DIR}/patches/libevent_on_free_cb.patch"

    require_files "${SOURCE_TP}/installed/lib/libssl.a" "${patch_file}"

    # Step 1: create writable thirdparty copy
    if [ -d "${custom_installed}" ]; then
        warn "Custom thirdparty already exists at ${custom_tp}"
        read -p "Remove and rebuild? (y/N): " -n 1 -r
        echo
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            info "Keeping existing directory. Skipping copy."
        else
            rm -rf "${custom_tp}"
        fi
    fi

    if [ ! -d "${custom_installed}" ]; then
        info "Copying pre-built thirdparty to writable location..."
        info "This takes a few minutes (~3-7GB depending on the image)."
        mkdir -p "${custom_installed}"
        cp -r "${SOURCE_TP}/installed/"* "${custom_installed}/"
        info "Done. Size: $(du -sh "${custom_tp}" | cut -f1)"
    fi

    # Step 2: download libevent source
    local build_dir="/tmp/libevent-rebuild-$$"
    mkdir -p "${build_dir}"
    cd "${build_dir}"

    info "Downloading libevent (commit ${libevent_short})..."
    wget -q "${libevent_download}"
    unzip -q "${libevent_short}.zip"
    cd "libevent-${libevent_commit}"

    # Step 3: apply StarRocks patch
    info "Applying StarRocks libevent patch..."
    if patch -p1 --dry-run < "${patch_file}" > /dev/null 2>&1; then
        patch -p1 < "${patch_file}"
    elif patch -p1 -R --dry-run < "${patch_file}" > /dev/null 2>&1; then
        warn "Patch already applied."
    else
        warn "Patch status uncertain, continuing..."
    fi

    # Step 4: build libevent with OpenSSL
    info "Building libevent with OpenSSL support..."
    mkdir build && cd build
    # GCC 14 promotes this check from a warning to an error by default. evutil.c predates
    # that change and reinterprets struct evutil_addrinfo* as struct addrinfo* when calling
    # the system getaddrinfo/freeaddrinfo; demote it back to a warning rather than an error.
    CFLAGS="-Wno-error=incompatible-pointer-types" \
    cmake \
        -DCMAKE_INSTALL_PREFIX="${custom_installed}" \
        -DBUILD_SHARED_LIBS=OFF \
        -DEVENT__DISABLE_TESTS=ON \
        -DEVENT__DISABLE_OPENSSL=OFF \
        -DEVENT__DISABLE_SAMPLES=ON \
        -DEVENT__DISABLE_REGRESS=ON \
        -DOPENSSL_ROOT_DIR="${custom_installed}" \
        -DOPENSSL_INCLUDE_DIR="${custom_installed}/include" \
        -DOPENSSL_CRYPTO_LIBRARY="${custom_installed}/lib/libcrypto.a" \
        -DOPENSSL_SSL_LIBRARY="${custom_installed}/lib/libssl.a" \
        .. > /dev/null

    make -j"$(nproc)" > /dev/null
    make install > /dev/null

    rm -f "${custom_installed}/lib/libevent"*.so
    rm -f "${custom_installed}/lib/libevent"*.so.*

    # Step 5: verify
    info "Verifying installation..."
    if [ ! -f "${custom_installed}/lib/libevent_openssl.a" ]; then
        error "libevent_openssl.a not found after build!"
        exit 1
    fi
    if ! grep -q '#define EVENT__HAVE_OPENSSL 1' "${custom_installed}/include/event2/event-config.h"; then
        error "EVENT__HAVE_OPENSSL not set in event-config.h!"
        exit 1
    fi
    info "libevent_openssl.a: $(ls -lh "${custom_installed}/lib/libevent_openssl.a" | awk '{print $5}')"
    info "EVENT__HAVE_OPENSSL: defined"

    # Step 6: swap the writable copy into place
    rm -rf "${build_dir}"
    rm -rf "${SOURCE_TP}/installed"
    mv "${custom_installed}" "${SOURCE_TP}/installed"
    rm -rf "${custom_tp}"
}

#####################################################################
# libserdes: rebuild against the deep-bi mTLS branch tip
#####################################################################
rebuild_libserdes() {
    require_files "${TP_DIR}/vars.sh" "${TP_DIR}/build-thirdparty.sh"

    # vars.sh pins TP_SOURCE_DIR/TP_INSTALL_DIR under thirdparty/; only the
    # SERDES_* download coordinates are needed here, so grab those and drop
    # the rest rather than adopting its directory layout.
    local serdes_download serdes_name serdes_source patch_file
    serdes_download=$(grep '^SERDES_DOWNLOAD=' "${TP_DIR}/vars.sh" | cut -d'"' -f2)
    serdes_name=$(grep '^SERDES_NAME=' "${TP_DIR}/vars.sh" | cut -d'"' -f2)
    serdes_source=$(grep '^SERDES_SOURCE=' "${TP_DIR}/vars.sh" | cut -d'"' -f2)
    if [ -z "${serdes_download}" ] || [ -z "${serdes_name}" ] || [ -z "${serdes_source}" ]; then
        error "Could not read SERDES_DOWNLOAD/SERDES_NAME/SERDES_SOURCE from ${TP_DIR}/vars.sh"
        exit 1
    fi
    patch_file="${TP_DIR}/patches/libserdes-7.3.1.patch"
    require_files "${patch_file}"

    for lib in libjansson.a libavro.a librdkafka.a librdkafka++.a libssl.a libcrypto.a; do
        if [ ! -f "${SOURCE_TP}/installed/lib/${lib}" ] && [ ! -f "${SOURCE_TP}/installed/lib64/${lib}" ]; then
            error "${lib} not found under ${SOURCE_TP}/installed/{lib,lib64}"
            exit 1
        fi
    done

    # Step 1: download the branch tip source into a scratch dir
    local build_dir="/tmp/libserdes-rebuild-$$"
    mkdir -p "${build_dir}"
    cd "${build_dir}"

    info "Downloading current libserdes (feature/mTLS-implementation)..."
    wget --progress=dot:mega --tries=3 --no-check-certificate "${serdes_download}" -O "${serdes_name}"

    case "${serdes_name}" in
        *.tar.gz|*.tgz) tar xzf "${serdes_name}" ;;
        *.zip)          unzip -q "${serdes_name}" ;;
        *) error "Don't know how to unpack ${serdes_name}"; exit 1 ;;
    esac

    # GitHub branch tarballs unpack to <repo>-<branch-with-slashes-turned-to-dashes>,
    # not to $serdes_source; find whatever single directory came out.
    local extracted_dir
    extracted_dir=$(find . -mindepth 1 -maxdepth 1 -type d | head -1)
    if [ -z "${extracted_dir}" ]; then
        error "Nothing extracted from ${serdes_name}"
        exit 1
    fi
    mv "${extracted_dir}" "${serdes_source}"

    info "Applying StarRocks libserdes patch..."
    cd "${serdes_source}"
    if patch -p0 --dry-run < "${patch_file}" > /dev/null 2>&1; then
        patch -p0 < "${patch_file}"
    elif patch -p0 -R --dry-run < "${patch_file}" > /dev/null 2>&1; then
        info "Patch already applied."
    else
        error "libserdes-7.3.1.patch does not apply cleanly to ${serdes_source}"
        exit 1
    fi
    cd "${build_dir}"

    # Step 2: pull in build_serdes() and its two helpers verbatim from
    # build-thirdparty.sh, instead of re-implementing the build/install/objcopy
    # steps here where they could quietly diverge from the real build.
    info "Loading build_serdes() from thirdparty/build-thirdparty.sh..."
    local extracted_funcs extract_status
    extracted_funcs=$(extract_functions check_if_source_exist restore_compile_flags build_serdes "${TP_DIR}/build-thirdparty.sh") \
        && extract_status=0 || extract_status=$?
    if [ "${extract_status}" -ne 0 ] || [ -z "${extracted_funcs}" ]; then
        error "Could not extract check_if_source_exist/restore_compile_flags/build_serdes from ${TP_DIR}/build-thirdparty.sh"
        exit 1
    fi
    eval "${extracted_funcs}"
    type build_serdes > /dev/null 2>&1 || { error "build_serdes() did not load correctly"; exit 1; }

    # Step 3: build + install libserdes straight into the pre-built thirdparty,
    # against the jansson/avro/rdkafka/openssl already installed there.
    export TP_SOURCE_DIR="${build_dir}"
    export TP_INSTALL_DIR="${SOURCE_TP}/installed"
    export TP_INCLUDE_DIR="${TP_INSTALL_DIR}/include"
    export SERDES_SOURCE="${serdes_source}"
    export PARALLEL="${PARALLEL:-$(nproc)}"
    # restore_compile_flags (called at the end of build_serdes) resets
    # CPPFLAGS/CFLAGS/CXXFLAGS to these; mirrors the GLOBAL_* formulas
    # build-thirdparty.sh itself uses so a standalone serdes rebuild leaves the
    # shell in the same state a full thirdparty build would.
    local file_prefix_map_option="-ffile-prefix-map=${TP_SOURCE_DIR}=. -ffile-prefix-map=${TP_INSTALL_DIR}=."
    export GLOBAL_CPPFLAGS="-I ${TP_INCLUDE_DIR}"
    export GLOBAL_CFLAGS="-O3 -fno-omit-frame-pointer -std=c99 -fPIC -g -D_POSIX_C_SOURCE=200112L -gz=zlib ${file_prefix_map_option}"
    export GLOBAL_CXXFLAGS="-O3 -fno-omit-frame-pointer -Wno-class-memaccess -fPIC -g -gz=zlib ${file_prefix_map_option}"

    info "Building libserdes..."
    build_serdes

    # Step 4: verify
    info "Verifying installation..."
    if [ ! -f "${TP_INSTALL_DIR}/lib/libserdes.a" ]; then
        error "libserdes.a not found after build!"
        exit 1
    fi
    if ! grep -q "serdes_conf_set_client_cert" "${TP_INSTALL_DIR}/include/libserdes/serdes.h"; then
        error "serdes_conf_set_client_cert not found in installed serdes.h!"
        error "The feature/mTLS-implementation branch may not have this symbol yet."
        exit 1
    fi
    info "serdes_conf_set_client_cert: found in ${TP_INSTALL_DIR}/include/libserdes/serdes.h"

    rm -rf "${build_dir}"
}

if [ "${TARGET}" = "all" ]; then
    for entry in "${TARGET_DESCRIPTIONS[@]}"; do
        t="${entry%%:*}"
        info "=== Rebuilding ${t} ==="
        "rebuild_${t}"
    done
else
    "rebuild_${TARGET}"
fi

echo ""
info "Done. Rebuild StarRocks BE with:"
echo ""
echo "  ./build.sh --be -j\$(nproc)"
echo ""
