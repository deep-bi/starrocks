#!/bin/bash
# Rebuilds libevent with OpenSSL support for the BE HTTPS feature.
#
# The pre-built thirdparty in the StarRocks dev-env docker image was compiled
# without OpenSSL, so libevent_openssl.a is missing and EVENT__HAVE_OPENSSL is
# undefined in event-config.h. This script creates a writable copy of the
# pre-built thirdparty and rebuilds only libevent against the existing OpenSSL.
#
# Usage (inside docker container):
#   ./rebuild-libevent-with-openssl.sh
#   export STARROCKS_THIRDPARTY=$(pwd)/thirdparty/custom
#   ./build.sh --be -j$(nproc)

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

info()  { echo -e "${GREEN}[INFO]${NC} $1"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_TP="${STARROCKS_THIRDPARTY:-/var/local/thirdparty}"
CUSTOM_TP="${SCRIPT_DIR}/thirdparty/custom"
CUSTOM_INSTALLED="${CUSTOM_TP}/installed"

LIBEVENT_COMMIT="24236aed01798303745470e6c498bf606e88724a"
LIBEVENT_SHORT="24236ae"
LIBEVENT_DOWNLOAD="https://github.com/libevent/libevent/archive/${LIBEVENT_SHORT}.zip"
PATCH_FILE="${SCRIPT_DIR}/thirdparty/patches/libevent_on_free_cb.patch"

if [ ! -d "${SOURCE_TP}/installed" ]; then
    error "Pre-built thirdparty not found at ${SOURCE_TP}/installed"
    error "This script is meant to run inside the StarRocks dev-env docker container."
    exit 1
fi

if [ ! -f "${SOURCE_TP}/installed/lib/libssl.a" ]; then
    error "OpenSSL not found in thirdparty at ${SOURCE_TP}/installed/lib/libssl.a"
    exit 1
fi

if [ ! -f "${PATCH_FILE}" ]; then
    error "StarRocks libevent patch not found at ${PATCH_FILE}"
    exit 1
fi

# Step 1: Create writable thirdparty copy
if [ -d "${CUSTOM_INSTALLED}" ]; then
    warn "Custom thirdparty already exists at ${CUSTOM_TP}"
    read -p "Remove and rebuild? (y/N): " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        info "Keeping existing directory. Skipping copy."
    else
        rm -rf "${CUSTOM_TP}"
    fi
fi

if [ ! -d "${CUSTOM_INSTALLED}" ]; then
    info "Copying pre-built thirdparty to writable location..."
    info "This takes a few minutes (~3-7GB depending on the image)."
    mkdir -p "${CUSTOM_INSTALLED}"
    cp -r "${SOURCE_TP}/installed/"* "${CUSTOM_INSTALLED}/"
    info "Done. Size: $(du -sh "${CUSTOM_TP}" | cut -f1)"
fi

# Step 2: Download libevent source
BUILD_DIR="/tmp/libevent-rebuild-$$"
mkdir -p "${BUILD_DIR}"
cd "${BUILD_DIR}"

info "Downloading libevent (commit ${LIBEVENT_SHORT})..."
wget -q "${LIBEVENT_DOWNLOAD}"
unzip -q "${LIBEVENT_SHORT}.zip"
cd "libevent-${LIBEVENT_COMMIT}"

# Step 3: Apply StarRocks patch
info "Applying StarRocks libevent patch..."
if patch -p1 --dry-run < "${PATCH_FILE}" > /dev/null 2>&1; then
    patch -p1 < "${PATCH_FILE}"
elif patch -p1 -R --dry-run < "${PATCH_FILE}" > /dev/null 2>&1; then
    warn "Patch already applied."
else
    warn "Patch status uncertain, continuing..."
fi

# Step 4: Build libevent with OpenSSL
info "Building libevent with OpenSSL support..."
mkdir build && cd build
cmake \
    -DCMAKE_INSTALL_PREFIX="${CUSTOM_INSTALLED}" \
    -DEVENT__DISABLE_TESTS=ON \
    -DEVENT__DISABLE_OPENSSL=OFF \
    -DEVENT__DISABLE_SAMPLES=ON \
    -DEVENT__DISABLE_REGRESS=ON \
    -DOPENSSL_ROOT_DIR="${CUSTOM_INSTALLED}" \
    -DOPENSSL_INCLUDE_DIR="${CUSTOM_INSTALLED}/include" \
    -DOPENSSL_CRYPTO_LIBRARY="${CUSTOM_INSTALLED}/lib/libcrypto.a" \
    -DOPENSSL_SSL_LIBRARY="${CUSTOM_INSTALLED}/lib/libssl.a" \
    .. > /dev/null

make -j"$(nproc)" > /dev/null
make install > /dev/null

# Step 5: Verify
info "Verifying installation..."

if [ ! -f "${CUSTOM_INSTALLED}/lib/libevent_openssl.a" ]; then
    error "libevent_openssl.a not found after build!"
    exit 1
fi

if ! grep -q '#define EVENT__HAVE_OPENSSL 1' "${CUSTOM_INSTALLED}/include/event2/event-config.h"; then
    error "EVENT__HAVE_OPENSSL not set in event-config.h!"
    exit 1
fi

info "libevent_openssl.a: $(ls -lh "${CUSTOM_INSTALLED}/lib/libevent_openssl.a" | awk '{print $5}')"
info "EVENT__HAVE_OPENSSL: defined"

# Cleanup
rm -rf "${BUILD_DIR}"

echo ""
info "Done. To build StarRocks with HTTPS support:"
echo ""
echo "  export STARROCKS_THIRDPARTY=${CUSTOM_TP}"
echo "  ./build.sh --be -j\$(nproc)"
echo ""
