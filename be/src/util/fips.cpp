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

#include <openssl/conf.h>
#include <openssl/crypto.h>
#include <openssl/err.h>
#include <openssl/evp.h>
#include <openssl/provider.h>
#include <openssl/rand.h>

#include <atomic>
#include <cstdio>
#include <cstring>
#include <memory>
#include <string_view>

#include "common/config.h"
#include "fmt/format.h"
#include "util/openssl_errors.h"
#include "util/sha.h"

namespace starrocks {

// the module validated under CMVP #4985; any other fips.so, even another validated release, is not
// covered by the claims in FIPS.md
static constexpr std::string_view kFipsProviderName = "OpenSSL FIPS Provider";
static constexpr std::string_view kFipsProviderVersion = "3.1.2";

static std::atomic<bool> g_fips_enabled{false};
static std::string g_fips_description = "disabled";

static Status fips_error(std::string_view what) {
    return Status::InternalError(fmt::format("FIPS mode: {}:{}", what, get_openssl_errors()));
}

// fetching alone isn't enough: the digest has to run. null properties means the default ones
static bool digest_works(const char* name, const char* properties = nullptr) {
    EVP_MD* md = EVP_MD_fetch(nullptr, name, properties);
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

// the provider doesn't report conditional-errors, and the module-mac in fipsmodule.cnf covers only
// fips.so, so read the setting from the config the provider was loaded from. Follows the same
// openssl_conf -> providers -> fips chain as OpenSSL, so a value overridden anywhere is seen
static std::string fips_section_value(const char* key) {
    std::unique_ptr<CONF, decltype(&NCONF_free)> conf(NCONF_new(nullptr), &NCONF_free);
    long error_line = 0;
    std::string value;
    if (conf != nullptr && NCONF_load(conf.get(), config::fips_openssl_conf.c_str(), &error_line) == 1) {
        const char* init = NCONF_get_string(conf.get(), nullptr, "openssl_conf");
        const char* providers = init ? NCONF_get_string(conf.get(), init, "providers") : nullptr;
        const char* fips = providers ? NCONF_get_string(conf.get(), providers, "fips") : nullptr;
        const char* v = fips ? NCONF_get_string(conf.get(), fips, key) : nullptr;
        if (v != nullptr) {
            value = v;
        }
    }
    // NCONF_get_string queues an error for every missing key
    ERR_clear_error();
    return value;
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
    // the SHA digest classes fetch their method once; one fetched before this point would stay
    // with the default provider for the life of the process
    for (const EVP_MD* md : {SHA224Digest::method(), SHA256Digest::method(), SHA384Digest::method(),
                             SHA512Digest::method()}) {
        const char* provider = OSSL_PROVIDER_get0_name(EVP_MD_get0_provider(md));
        if (provider == nullptr || std::strcmp(provider, "fips") != 0) {
            return Status::InternalError(fmt::format("FIPS mode: {} was fetched from the {} provider before FIPS "
                                                     "mode was set up",
                                                     EVP_MD_get0_name(md), provider ? provider : "unknown"));
        }
    }
    if (digest_works("MD5")) {
        return Status::InternalError("FIPS mode: MD5 is still available under the default properties");
    }
    // md5_method() in util/md5.cpp CHECKs this fetch, so without the default provider the node would
    // start and then abort on its first MD5 use (SQL md5, small file download, restore)
    if (!digest_works("MD5", "fips=no")) {
        // digest_works() has already cleared the error queue, so there is nothing to append
        return Status::InternalError(fmt::format(
                "FIPS mode: MD5 with fips=no is unavailable (does {} activate the default provider?)",
                config::fips_openssl_conf));
    }

    OSSL_PROVIDER* fips = OSSL_PROVIDER_load(nullptr, "fips");
    if (fips == nullptr) {
        return fips_error("cannot get the fips provider");
    }
    const char* name = nullptr;
    const char* version = nullptr;
    int status = 0;
    // security policy section 2.4 requires these for the approved mode; fipsinstall -pedantic
    // writes them as 1, but nothing stops fipsmodule.cnf being edited afterwards
    int security_checks = -1;
    int tls1_prf_ems_check = -1;
    int drbg_no_trunc_md = -1;
    OSSL_PARAM params[] = {OSSL_PARAM_construct_utf8_ptr("name", const_cast<char**>(&name), 0),
                           OSSL_PARAM_construct_utf8_ptr("version", const_cast<char**>(&version), 0),
                           OSSL_PARAM_construct_int("status", &status),
                           OSSL_PARAM_construct_int("security-checks", &security_checks),
                           OSSL_PARAM_construct_int("tls1-prf-ems-check", &tls1_prf_ems_check),
                           OSSL_PARAM_construct_int("drbg-no-trunc-md", &drbg_no_trunc_md),
                           OSSL_PARAM_construct_end()};
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
    std::string conditional_errors = fips_section_value("conditional-errors");
    if (security_checks != 1 || tls1_prf_ems_check != 1 || drbg_no_trunc_md != 1 || conditional_errors != "1") {
        return Status::InternalError(fmt::format(
                "FIPS mode: the fips provider is not configured for the approved mode (security-checks={}, "
                "tls1-prf-ems-check={}, drbg-no-trunc-md={}, conditional-errors={}; all must be 1). Delete "
                "conf/fipsmodule.cnf and restart to regenerate it",
                security_checks, tls1_prf_ems_check, drbg_no_trunc_md,
                conditional_errors.empty() ? "unset" : conditional_errors));
    }

    g_fips_description = std::move(description);
    g_fips_enabled.store(true, std::memory_order_release);
    return Status::OK();
}

static bool drbg_from_fips(EVP_RAND_CTX* drbg) {
    const OSSL_PROVIDER* provider = drbg ? EVP_RAND_get0_provider(EVP_RAND_CTX_get0_rand(drbg)) : nullptr;
    const char* name = provider ? OSSL_PROVIDER_get0_name(provider) : nullptr;
    return name != nullptr && std::strcmp(name, "fips") == 0;
}

Status restore_fips_rand() {
    if (!is_fips_mode_enabled()) {
        return Status::OK();
    }
    // s2n_init, run by Aws::InitAPI, installs its own DRBG as libcrypto's default RAND engine unless
    // it detects FIPS, and s2n 1.4.4 detects it only through the OpenSSL 1.x FIPS_mode(). RAND_bytes
    // would then bypass the provider's approved DRBG
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdeprecated-declarations"
    bool replaced = RAND_get_rand_method() != RAND_OpenSSL();
    if (replaced && RAND_set_rand_method(RAND_OpenSSL()) != 1) {
        return fips_error("cannot restore OpenSSL's random number generator");
    }
    bool restored = RAND_get_rand_method() == RAND_OpenSSL();
#pragma GCC diagnostic pop
    if (!restored) {
        return fips_error("random number generator is not OpenSSL's");
    }
    if (!drbg_from_fips(RAND_get0_public(nullptr)) || !drbg_from_fips(RAND_get0_private(nullptr))) {
        return fips_error("random number generator is not from the fips provider");
    }
    if (replaced) {
        fprintf(stderr, "FIPS mode: restored OpenSSL's random number generator after AWS SDK initialisation\n");
    }
    return Status::OK();
}

bool is_fips_mode_enabled() {
    return g_fips_enabled.load(std::memory_order_acquire);
}

const std::string& fips_mode_description() {
    return g_fips_description;
}

} // namespace starrocks
