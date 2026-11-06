# FIPS 140-3 support in StarRocks BE and CN

The StarRocks BE and CN can run in FIPS 140-3 mode using the OpenSSL FIPS Provider 3.1.2, CMVP certificate #4985. Every package ships the provider. `enable_fips_mode = true` in `be.conf` or `cn.conf` turns FIPS mode on; it is off by default.

## Build

There is no FIPS build option. Every BE package contains the FIPS provider, so the same package serves FIPS and non-FIPS deployments. Deployments that must be FIPS compliant need an x86_64 package. The only Linux environments the certificate lists are Ubuntu 22.04.1 and Debian 11.5 on Intel x86_64 (security policy, Table 3). The build host itself needs no FIPS setup. Compliance depends on where `fips.so` runs, and on it being built exactly as the security policy describes, which `rebuild-thirdparty.sh` does.

```sh
./rebuild-thirdparty.sh                # rebuilds the changed thirdparty libraries and the 3.1.2 FIPS provider
./build.sh --be [--enable-shared-data]
```

`rebuild-thirdparty.sh` skips targets that are already built, so it is safe to re-run. `build.sh` stops with an error if the FIPS provider is missing from thirdparty.

The package includes:

| Path in `output/be` | Purpose |
|---|---|
| `lib/ossl-modules/fips.so` | The validated FIPS provider |
| `lib/openssl-fips/openssl`, `libcrypto.so.3`, `libssl.so.3` | The 3.1.2 `openssl` tool, used once per host to generate `conf/fipsmodule.cnf` |
| `lib/openssl-fips/openssl-fips.cnf` | OpenSSL config that activates the FIPS provider and sets `default_properties = fips=yes` |

All FIPS files ship under `lib/`, so an upgrade that replaces `bin/` and `lib/` brings everything FIPS mode needs. `conf/` holds only per-host files: `be.conf`, `cn.conf` and the generated `fipsmodule.cnf`.

## Enable and disable FIPS mode

FIPS mode is controlled by `enable_fips_mode` in `be.conf` (BE) or `cn.conf` (CN). The setting is read at startup, so a change needs a restart.

| Setting | Default | Meaning |
|---|---|---|
| `enable_fips_mode` | `false` | `true` turns FIPS mode on |
| `fips_module_dir` | `${STARROCKS_HOME}/lib/ossl-modules` | Directory that holds `fips.so` |
| `fips_openssl_conf` | `${STARROCKS_HOME}/lib/openssl-fips/openssl-fips.cnf` | OpenSSL config loaded in FIPS mode |

Leave `fips_module_dir` and `fips_openssl_conf` at their defaults unless the package layout is changed.

To enable:

1. Add `enable_fips_mode = true` to `be.conf` or `cn.conf`.
2. Start or restart the node with `bin/start_be.sh` or `bin/start_cn.sh`.
3. On the first start, the start script generates `conf/fipsmodule.cnf` for that host.
4. Check that `be.out` or `cn.out` shows `FIPS mode enabled: OpenSSL FIPS Provider 3.1.2`.

To disable, set `enable_fips_mode = false` or remove the line, then restart. `conf/fipsmodule.cnf` can stay; it is not used while FIPS mode is off.

### Who generates `conf/fipsmodule.cnf`

The file holds the HMAC-SHA2-256 reference value that `fips.so` checks itself against on every load. It also holds the settings that section 2.4 of the security policy requires for the approved mode. Section 11.1 makes installation a Cryptographic Officer task "on each platform where it is intended to be used". The start script generates the file only when it is missing, so either workflow works:

- Default: the start script runs `openssl fipsinstall -pedantic` on the first start.
- Security team: before the first start, they check the sha256 of `lib/ossl-modules/fips.so` against the published value, then generate the file themselves on each host:

  ```sh
  LD_LIBRARY_PATH=lib/openssl-fips lib/openssl-fips/openssl fipsinstall \
      -module lib/ossl-modules/fips.so -out conf/fipsmodule.cnf -provider_name fips -pedantic
  ```

In both cases, the step that matters is confirming `fips.so` is the genuine module before the first start. Generating the file adds no assurance on its own.

## Upgrade

Upgrade BE and CN nodes before FE nodes, as for any StarRocks upgrade. On each node:

1. Stop the node and record `sha256sum lib/ossl-modules/fips.so`.
2. Replace `bin/` and `lib/` with the new package. Keep `conf/`.
3. If the sha256 of the new `fips.so` is unchanged, keep `conf/fipsmodule.cnf`. If it changed, check it against the published value for the release, then delete `conf/fipsmodule.cnf`.
4. Start the node and check `be.out` or `cn.out` for `FIPS mode enabled` before moving to the next node.

If step 3 is skipped after `fips.so` changed, the node refuses to start and the start script logs that `fips.so` is newer than `conf/fipsmodule.cnf`.

## Supported

- BE and CN, in both shared-nothing and shared-data deployments.
- One package for both modes. The same binary runs with FIPS mode on or off. With FIPS mode off, the provider is never loaded.
- Same behaviour in both modes. SQL results and on-disk formats don't change when FIPS mode is on.
- Startup checks. With FIPS mode on, the BE or CN refuses to start, and logs the reason to `be.out` or `cn.out`, when:
  - `fips.so` or the 3.1.2 `openssl` tool is missing, or `fips_module_dir` points elsewhere;
  - `fips.so` fails its integrity check or self-tests;
  - `conf/fipsmodule.cnf` is missing or was generated for a different `fips.so`.

  It never falls back to non-FIPS mode.
- A successful start logs `FIPS mode enabled: OpenSSL FIPS Provider 3.1.2`. `enable_fips_mode` appears in `information_schema.be_configs`.

## Compliant

These run on FIPS-approved algorithms inside the validated module when FIPS mode is on.

| Function | Algorithm |
|---|---|
| SQL `sha2`, `sha224`, `sha256`, `sha384`, `sha512` | SHA-2 |
| SQL `encode_fingerprint_sha256` | SHA-256 |
| Shared-data filesystem cache key | SHA-256 |
| Clone and replication duplicate-file checks | SHA-256 |
| S3 request signing (SigV4) | SHA-256, HMAC-SHA256 |
| LLM function result cache key | SHA-256 |
| Encryption at rest | AES-CTR, AES-GCM key wrap, approved random number generator |
| TLS (brpc, curl, cloud SDKs) | Approved cipher suites only. These libraries share the same OpenSSL library context, so the FIPS default applies to them. Not yet verified end to end. |

## Non-compliant, and why we keep it

These use algorithms or methods that FIPS doesn't approve. They stay enabled so that the same SQL and the same data work with FIPS mode on and off.

| Function | Why it is non-compliant | Why it is kept |
|---|---|---|
| SQL `md5`, `md5sum`, `md5sum_numeric` | MD5 is not approved. | Users expect MD5 output. It is a compatibility function and makes no security claim. |
| Small files and UDF jars downloaded from the FE | Checked with MD5. | The FE sends an MD5 checksum. Changing it would change the FE and the FE to BE protocol. It detects corruption and makes no security claim. |
| Backup and restore snapshots | Checked with MD5. | Backup repositories store MD5 checksums. Changing it would break restores of existing backups. It detects corruption and makes no security claim. |
| S3 `Content-MD5` (for example on `DeleteObjects`) | MD5 is not approved. | The S3 API requires it for some requests. StarRocks replaces the AWS SDK's MD5 with its own, because the SDK's version fails in FIPS mode without reporting an error. It detects corruption and makes no security claim. |
| SQL `sm3` | SM3 is not approved. It is StarRocks' own code and doesn't use the validated module. | Compatibility. It makes no security claim. |
| SQL `aes_encrypt`, `aes_decrypt` | The AES cipher runs in the validated module, but the key is derived from the user's key by XOR folding, which is not an approved key derivation. The IV is zero or supplied by the caller, which doesn't meet SP 800-38D for GCM. | MySQL compatibility: existing data encrypted with these functions must still decrypt. |

In FIPS mode, MD5 is available only where the code asks for it explicitly. Any other use of MD5 is refused, so a new MD5 use can't slip in unnoticed.

### Compliance position

The claim is that every security function in the BE and CN uses an approved algorithm from the validated module. The non-compliant items above either make no security claim or are user-requested functions kept for compatibility.

For MD5 and SM3, we are moderately confident this fits the FIPS 140-3 Implementation Guidance on non-approved algorithms used with no security claim (IG 2.4.A). `aes_encrypt` and `aes_decrypt` are the weaker case: they are encryption whose key handling is not approved. A compliance owner must confirm both before FIPS compliance is stated to a customer. If a deployment needs every encryption path to be approved, these two functions must be disabled for it; today they can't be disabled separately.

## Requirements to stay compliant

- Deploy on x86_64 Linux. The certificate lists no OS other than Ubuntu 22.04.1 and Debian 11.5 for Linux. The customer's compliance owner decides whether another OS is acceptable.
- The certificate lists gcc 11.2.0 for Ubuntu 22.04.1, and the dev-env image builds `fips.so` with a newer gcc. The security policy gives build steps but no required compiler version. Record the compiler used in the release notes in case an auditor asks.
- Don't modify, strip or rebuild `lib/ossl-modules/fips.so`. It must stay byte-identical to the module built from the OpenSSL 3.1.2 source with `./Configure enable-fips` and no other options.
- Generate `conf/fipsmodule.cnf` on each host, as section 11.1 of the security policy and the OpenSSL 3.1.2 `README-FIPS.md` require, either on first start or by the security team. OpenSSL's explicit ban on copying the file applies to 3.0. A 3.0 file carries an `install-status` entry that skips the self-tests. The 3.1.2 file generated with `-pedantic` has none, so the self-tests run on every load. Whether copying the file or mounting it from a Kubernetes ConfigMap is acceptable is for the compliance owner to decide. With the default workflow, `conf/` must stay writable.
- The start script never regenerates `conf/fipsmodule.cnf`, because that would give a modified `fips.so` a valid integrity MAC. After a deliberate upgrade of `fips.so`, delete `conf/fipsmodule.cnf` before starting.

## References

- [CMVP #4985 security policy](https://csrc.nist.gov/CSRC/media/projects/cryptographic-module-validation-program/documents/security-policies/140sp4985.pdf): sections 2.4, 5.1, 11.1 and Table 3
- [OpenSSL 3.1.2 README-FIPS.md](https://github.com/openssl/openssl/blob/openssl-3.1.2/README-FIPS.md)
- [openssl-fipsinstall(1)](https://docs.openssl.org/3.1/man1/openssl-fipsinstall/)
