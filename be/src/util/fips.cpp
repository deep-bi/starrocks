// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include "util/fips.h"

#include <openssl/crypto.h>
#include <openssl/err.h>
#include <openssl/evp.h>
#include <openssl/provider.h>

#include <atomic>
#include <string_view>

#include "common/config.h"
#include "fmt/format.h"

namespace starrocks {

// the module validated under CMVP #4985; any other fips.so, even another validated release, is not
// covered by the claims in FIPS.md
static constexpr std::string_view kFipsProviderName = "OpenSSL FIPS Provider";
static constexpr std::string_view kFipsProviderVersion = "3.1.2";

static std::atomic<bool> g_fips_enabled{false};
static std::string g_fips_description = "disabled";

static std::string drain_openssl_errors() {
    std::string out;
    char buf[256];
    unsigned long err;
    while ((err = ERR_get_error()) != 0) {
        ERR_error_string_n(err, buf, sizeof(buf));
        out.append(" ").append(buf);
    }
    return out;
}

static Status fips_error(std::string_view what) {
    return Status::InternalError(fmt::format("FIPS mode: {}:{}", what, drain_openssl_errors()));
}

// fetching alone isn't enough: the digest has to run under the default properties
static bool digest_works(const char* name) {
    EVP_MD* md = EVP_MD_fetch(nullptr, name, nullptr);
    if (md == nullptr) {
        ERR_clear_error();
        return false;
    }
    unsigned char out[EVP_MAX_MD_SIZE];
    unsigned int len = 0;
    bool ok = EVP_Digest("abc", 3, out, &len, md, nullptr) == 1;
    EVP_MD_free(md);
    ERR_clear_error();
    return ok;
}

Status init_fips_mode() {
    // stop the first later use of OpenSSL (curl, aws sdk, brpc) from loading $OPENSSL_CONF or the
    // build host's openssl.cnf, either of which could reset the default properties set below
    if (OPENSSL_init_crypto(OPENSSL_INIT_NO_LOAD_CONFIG, nullptr) != 1) {
        return fips_error("OPENSSL_init_crypto failed");
    }
    // otherwise OpenSSL looks in the MODULESDIR compiled into the build, a path on the build host
    if (OSSL_PROVIDER_set_default_search_path(nullptr, config::fips_module_dir.c_str()) != 1) {
        return fips_error(fmt::format("cannot set provider search path {}", config::fips_module_dir));
    }
    // the fips provider runs its self-tests and integrity check while this loads it
    if (OSSL_LIB_CTX_load_config(nullptr, config::fips_openssl_conf.c_str()) != 1) {
        return fips_error(fmt::format("cannot load {} (is conf/fipsmodule.cnf present and generated on this host?)",
                                      config::fips_openssl_conf));
    }
    // a failed integrity check or self-test lands here, usually with nothing on the error queue
    if (OSSL_PROVIDER_available(nullptr, "fips") != 1) {
        return fips_error(fmt::format(
                "fips provider not loaded from {} (fips.so failed its integrity check or self-tests, or "
                "conf/fipsmodule.cnf was generated for a different fips.so)",
                config::fips_module_dir));
    }
    if (EVP_default_properties_enable_fips(nullptr, 1) != 1 || EVP_default_properties_is_fips_enabled(nullptr) != 1) {
        return fips_error("cannot set fips=yes as the default property");
    }
    if (!digest_works("SHA2-256")) {
        return fips_error("SHA2-256 is unavailable");
    }
    if (digest_works("MD5")) {
        return Status::InternalError("FIPS mode: MD5 is still available under the default properties");
    }

    OSSL_PROVIDER* fips = OSSL_PROVIDER_load(nullptr, "fips");
    if (fips == nullptr) {
        return fips_error("cannot get the fips provider");
    }
    const char* name = nullptr;
    const char* version = nullptr;
    int status = 0;
    OSSL_PARAM params[] = {OSSL_PARAM_construct_utf8_ptr("name", const_cast<char**>(&name), 0),
                           OSSL_PARAM_construct_utf8_ptr("version", const_cast<char**>(&version), 0),
                           OSSL_PARAM_construct_int("status", &status), OSSL_PARAM_construct_end()};
    bool got_params = OSSL_PROVIDER_get_params(fips, params) == 1;
    std::string description = fmt::format("{} {}", name ? name : "unknown", version ? version : "unknown");
    bool expected = got_params && name != nullptr && version != nullptr && kFipsProviderName == name &&
                    kFipsProviderVersion == version;
    // drops only this reference; the provider stays active from the config
    OSSL_PROVIDER_unload(fips);
    if (!got_params || status != 1) {
        return fips_error("fips provider reports it is not running");
    }
    if (!expected) {
        return Status::InternalError(fmt::format("FIPS mode: {} in {} is not {} {}", description,
                                                 config::fips_module_dir, kFipsProviderName, kFipsProviderVersion));
    }

    g_fips_description = std::move(description);
    g_fips_enabled.store(true, std::memory_order_release);
    return Status::OK();
}

bool is_fips_mode_enabled() {
    return g_fips_enabled.load(std::memory_order_acquire);
}

const std::string& fips_mode_description() {
    return g_fips_description;
}

} // namespace starrocks
