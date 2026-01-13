#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

#################################################################################
# This script will rebuild libevent with OpenSSL support for HTTPS feature
#################################################################################
set -e

curdir=`dirname "$0"`
curdir=`cd "$curdir"; pwd`

export STARROCKS_HOME=${STARROCKS_HOME:-$curdir/..}
export TP_DIR=$curdir

# include custom environment variables
if [[ -f ${STARROCKS_HOME}/env.sh ]]; then
    . ${STARROCKS_HOME}/env.sh
fi

if [[ ! -f ${TP_DIR}/download-thirdparty.sh ]]; then
    echo "Download thirdparty script is missing".
    exit 1
fi

if [ ! -f ${TP_DIR}/vars.sh ]; then
    echo "vars.sh is missing".
    exit 1
fi
. ${TP_DIR}/vars.sh

cd $TP_DIR

# Download thirdparties.
${TP_DIR}/download-thirdparty.sh

# set COMPILER
if [[ ! -z ${STARROCKS_GCC_HOME} ]]; then
    export CC=${STARROCKS_GCC_HOME}/bin/gcc
    export CPP=${STARROCKS_GCC_HOME}/bin/cpp
    export CXX=${STARROCKS_GCC_HOME}/bin/g++
    export PATH=${STARROCKS_GCC_HOME}/bin:$PATH
else
    echo "STARROCKS_GCC_HOME environment variable is not set"
    exit 1
fi

# prepare installed prefix
mkdir -p ${TP_DIR}/installed

check_prerequest() {
    local CMD=$1
    local NAME=$2
    if ! $CMD; then
        echo $NAME is missing
        exit 1
    else
        echo $NAME is found
    fi
}

check_prerequest "${CMAKE_CMD} --version" "cmake"

BUILD_SYSTEM=${BUILD_SYSTEM:-make}
BUILD_DIR=starrocks_build
MACHINE_TYPE=$(uname -m)

if [[ "${MACHINE_TYPE}" == "arm64" ]]; then
    MACHINE_TYPE="aarch64"
fi

echo "machine type : $MACHINE_TYPE"

check_if_source_exist() {
    if [ -z $1 ]; then
        echo "dir should specified to check if exist."
        exit 1
    fi

    if [ ! -d $TP_SOURCE_DIR/$1 ];then
        echo "$TP_SOURCE_DIR/$1 does not exist."
        exit 1
    fi
    echo "===== begin build $1 with OpenSSL support"
}

# Build libevent with OpenSSL support
build_libevent() {
    check_if_source_exist $LIBEVENT_SOURCE
    cd $TP_SOURCE_DIR/$LIBEVENT_SOURCE

    mkdir -p $BUILD_DIR
    cd $BUILD_DIR
    rm -rf CMakeCache.txt CMakeFiles

    echo "Building libevent with OpenSSL..."
    $CMAKE_CMD -DCMAKE_INSTALL_PREFIX=${TP_INSTALL_DIR} \
              -DCMAKE_INSTALL_LIBDIR=lib \
              -DEVENT__DISABLE_TESTS=ON \
              -DEVENT__DISABLE_OPENSSL=OFF \
              -DEVENT__DISABLE_SAMPLES=ON \
              -DEVENT__DISABLE_REGRESS=ON \
              -DOPENSSL_ROOT_DIR=${STARROCKS_THIRDPARTY}/installed \
              -DOPENSSL_INCLUDE_DIR=${STARROCKS_THIRDPARTY}/installed/include \
              -DOPENSSL_CRYPTO_LIBRARY=${STARROCKS_THIRDPARTY}/installed/lib/libcrypto.a \
              -DOPENSSL_SSL_LIBRARY=${STARROCKS_THIRDPARTY}/installed/lib/libssl.a \
              ..

    ${BUILD_SYSTEM} -j$PARALLEL
    ${BUILD_SYSTEM} install
}

# set GLOBAL_C*FLAGS
export FILE_PREFIX_MAP_OPTION="-ffile-prefix-map=${TP_SOURCE_DIR}=. -ffile-prefix-map=${TP_INSTALL_DIR}=."
export GLOBAL_CPPFLAGS="-I ${TP_INCLUDE_DIR}"
export GLOBAL_CFLAGS="-O3 -fno-omit-frame-pointer -std=c99 -fPIC -g -D_POSIX_C_SOURCE=200112L -gz=zlib ${FILE_PREFIX_MAP_OPTION}"
export GLOBAL_CXXFLAGS="-O3 -fno-omit-frame-pointer -Wno-class-memaccess -fPIC -g -gz=zlib ${FILE_PREFIX_MAP_OPTION}"

export CPPFLAGS=$GLOBAL_CPPFLAGS
export CXXFLAGS=$GLOBAL_CXXFLAGS
export CFLAGS=$GLOBAL_CFLAGS

build_libevent

# Copy libevent libraries to STARROCKS_THIRDPARTY
echo "Copying libevent with OpenSSL to ${STARROCKS_THIRDPARTY}/installed..."
cp -rf ${TP_INSTALL_DIR}/include/event2 ${STARROCKS_THIRDPARTY}/installed/include/
cp ${TP_INSTALL_DIR}/lib/libevent*.a ${STARROCKS_THIRDPARTY}/installed/lib/

echo "✓ libevent with OpenSSL built and installed successfully!"
echo "  Location: ${STARROCKS_THIRDPARTY}/installed/lib/libevent_openssl.a"
