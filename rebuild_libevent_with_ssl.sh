#!/bin/bash
# Rebuilds libevent with OpenSSL support for HTTPS feature
# Run this once in your Docker container before building StarRocks with HTTPS support

set -e

echo "Rebuilding libevent with OpenSSL support..."

THIRDPARTY_DIR=${STARROCKS_THIRDPARTY:-/var/local/thirdparty}
LIBEVENT_VERSION="24236aed01798303745470e6c498bf606e88724a"

cd /tmp
if [ ! -d "libevent-${LIBEVENT_VERSION}" ]; then
    echo "Downloading libevent..."
    wget -q https://github.com/libevent/libevent/archive/24236ae.zip
    unzip -q 24236ae.zip
fi

cd libevent-${LIBEVENT_VERSION}

# Apply StarRocks custom patch
if [ -f "${STARROCKS_HOME:-/root/starrocks}/thirdparty/patches/libevent_on_free_cb.patch" ]; then
    echo "Applying StarRocks patch..."
    patch -p1 < ${STARROCKS_HOME:-/root/starrocks}/thirdparty/patches/libevent_on_free_cb.patch || true
fi

# Build with OpenSSL support
echo "Building libevent with OpenSSL..."
rm -rf build
mkdir build && cd build
cmake \
  -DCMAKE_INSTALL_PREFIX=${THIRDPARTY_DIR}/installed \
  -DEVENT__DISABLE_TESTS=ON \
  -DEVENT__DISABLE_OPENSSL=OFF \
  -DEVENT__DISABLE_SAMPLES=ON \
  -DEVENT__DISABLE_REGRESS=ON \
  -DOPENSSL_ROOT_DIR=${THIRDPARTY_DIR}/installed \
  -DOPENSSL_INCLUDE_DIR=${THIRDPARTY_DIR}/installed/include \
  -DOPENSSL_CRYPTO_LIBRARY=${THIRDPARTY_DIR}/installed/lib/libcrypto.a \
  -DOPENSSL_SSL_LIBRARY=${THIRDPARTY_DIR}/installed/lib/libssl.a \
  .. > /dev/null

make -j$(nproc) > /dev/null
make install > /dev/null

echo "✓ libevent with OpenSSL installed successfully!"
echo "  Location: ${THIRDPARTY_DIR}/installed/lib/libevent_openssl.a"
echo ""
echo "You can now build StarRocks with HTTPS support:"
echo "  cd /root/starrocks"
echo "  export STARROCKS_THIRDPARTY=${THIRDPARTY_DIR}"
echo "  ./build.sh --be -j10"
