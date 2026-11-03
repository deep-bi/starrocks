# StarRocks 4.1 Java FIPS configuration and release boundaries

The implementation configures BCFIPS and BCJSSE for FE and the Java components embedded in
BE/CN. Approved-only mode and pure-Java BC execution are required, ordinary BC conflicts are
excluded, and private-key stores use BCFKS. These settings are unconditional for the Java
components there is no ordinary/FIPS build profile or runtime mode switch. TLS remains
configurable, with certificate and hostname validation on supported TLS paths.

Native cryptography and Docker-image preparation are separate tasks. The native source still
selects OpenSSL 1.1.1m the OpenSSL 3.5.9/3.1.2 inputs below are targets for that separate work,
not integrated providers. Full BE/CN service validation, complete connector qualification,
deployment-environment qualification and certificate-advisory mitigations remain open.

[Java dependencies and packaging](java-fips-dependencies.md),
[Java TLS and key handling](java-fips-tls.md) and
[startup configuration and verification](java-fips-runtime.md) describe the implementation.
The vendor-confirmed module HMAC security-policy typo and unchanged artifact checks are
recorded in [the provider integrity reference](java-fips-dependencies.md#provider-integrity-and-policy-erratum).

## Selected components

The OS and JDK selections below remain the release and qualification baseline. Startup does not
enforce their exact identity using another environment still requires qualification.

| Component | Selected version/configuration | Notes |
| --- | --- | --- |
| StarRocks | DeepBI 4.1.6 release target, 4.1 source tree | Record the source commit in release metadata |
| Runtime OS and architecture | Ubuntu 24.04, `linux/amd64` | Container userspace, host details are TBD. Other architectures need separate testing |
| Runtime Java | Eclipse Temurin HotSpot JDK `17.0.21+9`, Linux x64 | FE and embedded BE/CN JVMs, matching the Java 17 compilation target |
| Native TLS/core target, separate task | OpenSSL `3.5.9` | Not integrated build separately from the validated provider and rebuild native consumers |
| Native cryptographic module target, separate task | OpenSSL FIPS Provider `3.1.2` | Not integrated preserve the module and follow [security policy #4985][openssl-policy] |
| Java cryptographic module | `org.bouncycastle:bc-fips:2.1.1` | Unchanged provider JAR, approved-only mode, BC native code disabled |
| Java TLS | `org.bouncycastle:bctls-fips:2.1.25` | BCJSSE uses `FipsTlsCryptoProvider` in FIPS mode. Cryptography stays in BCFIPS and certificate paths pass through the shared constraint policy |
| Java utilities | `org.bouncycastle:bcutil-fips:2.1.8` | Resolve and inspect every shipped extension classpath |
| Java PKIX, where required | `org.bouncycastle:bcpkix-fips:2.1.13` | Include where needed, it does not fix bugs inside `bc-fips` |

The relevant validation records are [OpenSSL #4985][openssl-certificate] and
[BC-FJA #4943][bc-certificate]. The BC selection is the certificate-listed 2.1.1 module with
interim validation. Confirm certificate status and operating-environment coverage before release
a replacement must be covered by validation and pass security and compatibility checks.
These certificates cover the modules. They do not validate StarRocks itself.

## Artifact pins

These are the expected upstream SHA-256 values. The
[pin manifest](../java-fips-pinned-dependencies.json) supplies the Java artifact and security-overlay
hashes used by the package/startup validator. Fresh FE and Java-extension package inspection,
JAR signature verification and BCFIPS readiness checks are recorded in the
[runtime verification reference](java-fips-runtime.md#verification-status).
The image task must verify the JDK archive before installation. The OpenSSL pins are inputs to
the separate native task. Reject payload hash mismatches and preserve upstream signatures and
provenance records.

| Artifact | Expected SHA-256 | Checksum source |
| --- | --- | --- |
| [openssl-3.5.9.tar.gz][openssl-source] | `603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a` | [Publisher][openssl-sha] |
| [openssl-3.1.2.tar.gz][provider-source] | `a0ce69b8b97ea6a35b96875235aa453b966ba3cba8af2de23657d8b6767d6539` | [Publisher][provider-sha] |
| [OpenJDK17U-jdk_x64_linux_hotspot_17.0.21_9.tar.gz][jdk-binary] | `8b9b611d917e3dfd8419d3566b5949226f69b052fed7e109c1368add1dfe8711` | [Publisher][jdk-sha] |
| [bc-fips-2.1.1.jar][bc-core] | `a430d935ad6cec6d045930758457740f5a5f8f9715894e347f6800f7926a7321` | [Maven Central][bc-core-sha] |
| [bcutil-fips-2.1.8.jar][bc-util] | `f44904f61552c823bcc3686a24052ee3135212d547754eaa2ff6532e210103cb` | [Maven Central][bc-util-sha] |
| [bctls-fips-2.1.25.jar][bc-tls] | `af604aa5b337725872651067a819ebc03aa298685572b0b2ad5343c786faad0a` | [Maven Central][bc-tls-sha] |
| [bcpkix-fips-2.1.13.jar][bc-pkix] | `cf161ba2f6b4505816f4661663b3e0771184a50ee5b178a0018c817b336f0e5c` | [Maven Central][bc-pkix-sha] |

### Containers and build toolchain

These Linux amd64 platform manifest digests identify the selected image inputs. Manifest hashes
match their image-index entries image-layer contents are not qualified by that check. Use the
immutable references when preparing images and record the resulting FE/BE/CN image digests.

| Purpose | Observed tag | Immutable platform reference |
| --- | --- | --- |
| Selected runtime base | `ubuntu:24.04` | `ubuntu@sha256:78d4744ead1f5b19b04d0df558ab182a5300903900755e515b2ca4d10eb2044d` |
| Build image input, qualification open | `starrocks/toolchains-ubuntu:main-20251029` | `starrocks/toolchains-ubuntu@sha256:77953142a6afe1ce134d1b30d1e127630370b33dc87b2839bc3780995d562e9e` |
| Starlet input, crypto dependencies and rebuild needs TBD | `starrocks/starlet-artifacts-ubuntu22:v4.1.4` | `starrocks/starlet-artifacts-ubuntu22@sha256:a998789167f179b3508df7a889b74d8cdf7fbc399ada233a867e3d9b9f241dea` |

Manifest sources: [Ubuntu][ubuntu-manifest], [toolchain][toolchain-manifest],
[Starlet][starlet-manifest]. The build and Starlet images have not been qualified for FIPS.

The [toolchain recipe](../../docker/dockerfiles/toolchains/toolchains-ubuntu.Dockerfile) selects
Ubuntu 22.04, GCC 14.3.0, CMake 3.31.9, and distro packages for Maven, JDK 17, make, and lld.
The [development image](../../docker/dockerfiles/dev-env/dev-env.Dockerfile) references the
build image above. Its installed versions still need to be checked against the recipe.

Record the installed compiler/linker, CMake, Maven, build-JDK, and package versions, along with
build flags, patches, and the final development-image digest. This inventory is **TBD**.
Image rebuilds also need a pinned package snapshot because later `apt-get` results can change.
The OpenSSL module build must follow its own security policy. Using the StarRocks compiler does
not establish that the module's build environment was tested.

## Operating environment

Host details are TBD. Record the actual deployment environment when it is known.

| Item | Details needed |
| --- | --- |
| Container userspace | Ubuntu 24.04 / amd64, pinned above |
| Host OS and exact kernel | **TBD**, record distribution, kernel build, and relevant deployment restrictions |
| CPU and instruction availability | **TBD**, record CPU model/family, microcode, and instructions exposed to the container/VM |
| VM/container platform | **TBD**, record hypervisor, container runtime, and orchestrator versions where applicable |
| Entropy source and policy | Java uses BCFIPS `DEFAULT` randomness with SUN `NativePRNGNonBlocking` for module entropy and `file:/dev/urandom` deployment entropy assurance and native-module behavior remain **TBD** |
| Portability basis | **TBD**, check how the module policies and CMVP environment rules apply to the deployment |

Ubuntu 24.04 with this Temurin build is not the modules' exact lab-tested configuration.
Check the policies and run installation/self-tests on the target machines. For OpenSSL 3.1.2,
follow the [installation guidance][openssl-install] and do not copy successful installation state
between machines.

## Cryptographic path inventory

This inventory separates implemented Java controls from unqualified paths. Supported Java
operations select BCFIPS for cryptography and BCJSSE backed by BCFIPS for TLS. Native rows
describe source paths requiring separate work they do not claim OpenSSL FIPS integration.
Full dependency/binary inventory, remote connector tests and shared-data/Starlet qualification
remain open. PostgreSQL MD5 authentication is blocked full SCRAM and GSSAPI qualification
remain separate requirements.

| Path / source entry point | Current implementation | Evidence and remaining boundary |
| --- | --- | --- |
| FE MySQL/JDBC listener: [SSLContextLoader](../../fe/fe-core/src/main/java/com/starrocks/mysql/ssl/SSLContextLoader.java) | Explicit BCJSSE contexts/managers, BCFIPS randomness, BCFKS private keys and TLS 1.2/1.3 | Provider/store tests and actual FE TLS requests passed. `ssl_force_secure_transport` retains its localhost exception loopback smoke tests do not prove nonloopback enforcement or all client compatibility |
| FE HTTPS: [HttpSSLContextLoader](../../fe/fe-core/src/main/java/com/starrocks/http/rest/HttpSSLContextLoader.java) | Netty JDK TLS explicitly uses BCJSSE, BCFIPS randomness and BCFKS keys | Actual HTTPS requests and invalid-peer tests passed. HTTPS is optional enabled HTTP remains available |
| LDAP login and groups: [LDAPAuthProvider](../../fe/fe-core/src/main/java/com/starrocks/authentication/LDAPAuthProvider.java), [LDAPGroupProvider](../../fe/fe-core/src/main/java/com/starrocks/authentication/LDAPGroupProvider.java), [SslUtils](../../fe/fe-core/src/main/java/com/starrocks/authentication/SslUtils.java) | Shared BCJSSE trust loading and LDAPS endpoint identification for login/group contexts | Focused TLS/trust tests passed. Configured plaintext LDAP remains allowed remote directory qualification is separate |
| Password authentication: [MysqlPassword](../../fe/fe-core/src/main/java/com/starrocks/mysql/MysqlPassword.java) | SHA-1 and scramble randomness explicitly use BCFIPS the MySQL password protocol is preserved | Provider selection does not independently qualify the complete authentication construction or plaintext transport protocol changes require an explicit compatibility/migration decision |
| JWT/OAuth and outbound HTTP: [OpenIdConnectVerifier](../../fe/fe-core/src/main/java/com/starrocks/authentication/OpenIdConnectVerifier.java), [HttpUtils](../../fe/fe-core/src/main/java/com/starrocks/http/HttpUtils.java) | Nimbus RSA key construction/verification selects BCFIPS shared HTTPS uses BCJSSE trust/hostname checks and rejects HTTPS-to-HTTP redirects | Focused provider/TLS tests cover these consumers. Inventory additional dependency HTTP/signing paths before claiming complete connector coverage |
| Native service TLS: [starrocks_be.cpp](../../be/src/service/service_be/starrocks_be.cpp) | brpc SSL options use configured certificate/private-key paths no native FIPS initialization is implemented | Separate native task: trace listeners/clients, select the provider, authenticate peers and test protocols/ciphers |
| Native S3 and credentials: [fs_s3.cpp](../../be/src/fs/fs_s3.cpp), [Poco sessions](../../be/src/fs/s3/poco_common.cpp), [AWS build](../../thirdparty/build-thirdparty.sh) | Native AWS HTTP paths include CRT/s2n the SSL-option iterator defect remains outside the Java changes | Separate native task: fix SSL-option handling and qualify HTTPS, hostname/CA checks, signing, credential acquisition and curl/Poco/CRT provider use |
| Java S3/object storage: [S3FileSystem](../../fe/fe-core/src/main/java/com/starrocks/fs/s3/S3FileSystem.java), [Hadoop dependencies](../../java-extensions/hadoop-lib/pom.xml) | JVM configuration supplies BCFIPS/BCJSSE native Netty TLS is disabled, WildFly bindings and incompatible Tencent artifacts are excluded | ABFS/S3A, Paimon, Azure and selected AWS signing have focused evidence. ADL Gen1 and the excluded Tencent connector are unsupported complete signing/KMS/remote-service coverage remains open |
| Shared-data/Starlet: [PreBuildModule.cmake](../../be/cmake_modules/PreBuildModule.cmake), [artifact version](../../thirdparty/starlet-artifacts-version.sh) | Prebuilt native libraries have separate transitive cloud dependencies | Separate native task: identify every crypto implementation, obtain compatible artifacts and qualify real shared-data operations |
| Kafka/Pulsar: [data_consumer.cpp](../../be/src/runtime/routine_load/data_consumer.cpp), [dependency build](../../thirdparty/build-thirdparty.sh) | librdkafka and Pulsar retain their native TLS/authentication paths | Separate native task: qualify TLS/mTLS and PLAIN over verified TLS assess SCRAM/GSSAPI and each enabled Pulsar mechanism |
| Outbound JDBC: [JDBCMetadata](../../fe/fe-core/src/main/java/com/starrocks/connector/jdbc/JDBCMetadata.java), [JDBCScanner](../../java-extensions/jdbc-bridge/src/main/java/com/starrocks/jdbcbridge/JDBCScanner.java), [PostgresJdbcPolicy](../../java-extensions/hadoop-ext/src/main/java/com/starrocks/connector/share/security/PostgresJdbcPolicy.java) | Both pools reject PostgreSQL MD5 authentication through driver-parsed `requireAuth` drivers lacking that capability are rejected | Tests use PostgreSQL 42.7.12, including URL precedence and failed-module cases. Full SCRAM, remote metadata/scans, TLS and arbitrary externally supplied drivers remain unqualified |
| MariaDB/MySQL metadata and embedded MariaDB JDBC: [MariaDbJdbcPolicy](../../java-extensions/hadoop-ext/src/main/java/com/starrocks/connector/share/security/MariaDbJdbcPolicy.java) | Driver-parsed `restrictedAuth` rejects Ed25519/GSSAPI/PAM switches, restricted JDK services prevent the native-password/caching-SHA2 digests from falling back after BCFIPS failure | Actual 3.3.2 handshake/plugin regression covers initial responses and failed-module retries. Remote database exchanges and external MySQL Connector/J remain unqualified |
| HDFS, UDFs and Java extensions: [start_backend.sh](../../bin/start_backend.sh), [HDFS](../../be/src/fs/hdfs/fs_hdfs.cpp), [Java UDF](../../be/src/udf/java/java_udf.cpp), [ODPS dependencies](../../java-extensions/odps-reader/pom.xml) | Validated `LIBHDFS_OPTS` reaches JNI readers delegate BC classes to the shared parent ODPS ordinary BC is excluded. Hadoop AES-CTR/RNG explicitly uses BCFIPS and cryptographic SunSASL mechanisms are blocked | JNI/native-worker and six-reader tests passed. DIGEST-MD5/CRAM-MD5/NTLM-dependent paths are unsupported GSSAPI, user code and complete BE/CN service execution remain separate qualification work |
| Key generation, wrapping and encrypted files: [EncryptionUtil](../../fe/fe-core/src/main/java/com/starrocks/encryption/EncryptionUtil.java), [encrypt_file.cpp](../../be/src/fs/encrypt_file.cpp), [key_cache.cpp](../../be/src/fs/key_cache.cpp) | FE uses BCFIPS AES key generation and GCM with module-generated encryption IVs, preserving nonce/ciphertext/tag format. Native BE AES-CTR/GCM and unchecked `RAND_bytes` handling are unchanged | Parquet compatibility and two-JVM persist/replay/unwrap/new-wrap tests passed. Full FE crash recovery, BE interoperability/rotation and native randomness failure handling remain open |
| SQL crypto and checksums: [aes_util.cpp](../../be/src/util/aes_util.cpp), [md5.cpp](../../be/src/util/md5.cpp), [encryption_functions.cpp](../../be/src/exprs/encryption_functions.cpp) | Native EVP AES and low-level MD5 calls coexist these paths are not changed by Java provider configuration | Separate native task: distinguish security operations from checksums/SQL compatibility, identify bypasses and define supported behavior without silently changing formats/results |
| Additional protocols/connectors: [native gRPC build](../../thirdparty/build-thirdparty.sh), [Arrow Flight](../../be/src/service/service_be/arrow_flight_sql_service.cpp), other catalogs/cloud SDKs | Additional native, Java or external TLS termination is outside the focused evidence | Inventory and qualify each enabled path FE/embedded-JVM checks do not qualify an external proxy or an unrelated crypto implementation |

## Vulnerability and implementation blockers

BC 2.1.1 is affected by the vendor's [ASN.1 allocation][bc-asn1], [OCSP binding][bc-ocsp],
and [name-constraint][bc-name] advisories. The listed fixes are in ordinary BC 1.85 and FIPS
core 2.1.3 neither is a substitute for the selected validated core without checking validation
coverage. The Java certificate policy checks OCSP response binding and rejects affected name
constraints. Other certificate/parser advisories still require reachability analysis and tested
mitigation.

The table distinguishes implemented controls from open release work. Advisory applicability
must be checked against the shipped artifacts and enabled call paths this is not a complete
vulnerability scan. Record affected artifacts, reachable entry points, enforcement, reproducible
tests and ownership for each applicable issue.

| Issue | Current control or remaining release requirement |
| --- | --- |
| BC ASN.1 parsing: CVE-2026-14682, CVE-2026-13506, distinguished names: CVE-2026-58059 | Trace untrusted certificates/CRLs and name handling. Reachable affected paths block release until an effective tested mitigation or validated fixed core is available. Input-size limits alone do not establish mitigation |
| BC OCSP: CVE-2026-58062, name constraints: CVE-2026-8763 | Configured BCJSSE and default PKIX validation reject CA chains with email or URI name constraints and DNS names ending in a dot. Other DNS constraints remain supported. Supplied OCSP responses are checked against the certificate serial and issuer name/key hashes. Unrelated responses trigger validation without that response using a fresh BCFIPS checker. Tests cover valid, revoked, unknown and unrelated responses and preserve CRL fallback through PKIX and BCJSSE |
| BC native entropy/cleanup: CVE-2026-8798, CVE-2025-12194 | `org.bouncycastle.native.cpu_variant=java` is required in FE/BE/CN options runtime tests verify BC native services are disabled. This control does not mitigate certificate/parser defects |
| BC HSS/LMS: CVE-2026-58060, CCM: CVE-2026-58061 | No blanket API exclusion is implemented. Determine reachability and enforce/test restrictions before supporting affected operations |
| BC keystore loading: CVE-2026-13586, CVE-2026-58063 | Restrict loading to controlled deployment keystores protected against tampering, assess actual loading paths |
| OpenSSL ECDSA signing: CVE-2024-13176 | Separate native task: enforce and test a signing exclusion wherever the selected provider can sign. This is not an exclusion of ECDSA verification or ECDH |
| OpenSSL DHX: CVE-2026-42770 | The [vendor advisory][openssl-dhx] explicitly includes provider 3.1.2. Exclude DHX or require `EVP_PKEY_parameters_eq()` to return 1 for peer/local parameters before exchange, with regression evidence |
| OpenSSL RSA-KEM: CVE-2026-31790, oversized key validation: CVE-2024-4603, CVE-2023-6237 | Exclude RSA-KEM or bound key sizes and require `EVP_PKEY_public_check()` or `EVP_PKEY_public_check_quick()` to return 1 before encapsulation. Reject oversized keys using `EVP_PKEY_get_bits()` before public-key validation. Supported size limits and effective enforcement remain to be established |
| Native integration | Rebuild consumers, resolve CRT/s2n and Starlet crypto provenance, propagate randomness failures, correct S3 SSL-option handling |
| Java integration and trust | Ordinary BC conflicts are excluded, signed FIPS artifacts are preserved, private keys use BCFKS and supported TLS paths validate trust/hostnames. Restricted SUN services retain entropy, nonsecurity MD5 and JDK compatibility without SHA fallback. FE HTTP clients, default AWS SDK v2 transports, Hadoop S3A and Paimon's isolated AWS client accept only Basic HTTP authentication, blocking Digest/NTLM use of SUN MD5. Oracle pools enforce Thin-driver 12a authentication and JCE cryptography, replacing MD5 session-key derivation with BCFIPS PBKDF2. Remaining independent dependency crypto and MD5/entropy consumers prevent a universal no-fallback claim |

The required algorithm exclusions address bugs in the selected versions. They are not general FIPS bans.
If an enabled path cannot meet the security policy and vulnerability requirements, it cannot ship
as part of the supported configuration. Decide whether to change the scope or baseline. Do not relax approved mode or
count an untested mitigation as a fix.

## Release acceptance criteria

These criteria apply to the complete FIPS release. The Java implementation provides configuration,
package controls and focused evidence for image preparation and integration qualification.
Keep test results with the source commit, image digests, configuration and deployment environment.

| Acceptance area | Current evidence | Remaining release work |
| --- | --- | --- |
| Reproducible inputs | Fresh FE and Java-extension Maven packages pass dependency enforcement and package/hash checks signed provider verification and BCFIPS readiness pass | Verify the selected JDK archive during image preparation record complete native/Maven/JNI/JDBC inventories, package snapshots, installed toolchain versions and final image digests |
| Module and environment coverage | Java versions/configuration and module policy references are explicit. Builds, focused tests, JNI/dependency harnesses and live FE checks passed on pinned Temurin `17.0.21+9` after archive verification | Confirm certificate status, deployment host/CPU/runtime/entropy details and operating-environment coverage. Local pinned-JDK execution does not qualify complete BE/CN services or the deployment environment |
| Native initialization | Outside the Java implementation | Preserve/install the selected module according to policy verify `EVP_default_properties_is_fips_enabled(libctx) == 1` and actual operation providers in relevant contexts. Default properties alone are insufficient |
| Java initialization | Approved-only settings precede provider use tests check BCFIPS/BCJSSE, disabled BC native code, native-worker/readers and launcher overrides using the [provider guide][bc-guide] | Complete BE/CN service execution and deployment-image qualification focused JNI evidence does not cover every Java consumer |
| Failure behavior | Launcher faults, prohibited operations, invalid peers and injected module-error cases fail on tested explicit/dependency paths | Inventory remaining security consumers and prevent unapproved fallback on every supported path retained SUN services mean provider order alone is insufficient |
| Supported features | The inventory identifies supported controls and explicit unsupported paths | Complete positive/negative remote-service tests resolve Starlet/CRT, full SCRAM/GSSAPI, external drivers/UDFs, connectors and external TLS termination |
| Vulnerabilities | BC native services are disabled certificate/parser and native-module advisory work is identified | Verify applicability and close each blocker with reproducible evidence. Pins, scanner suppressions and proposed workarounds do not establish mitigation |
| Data and compatibility | Java key generation/wrapping, Parquet compatibility and two-JVM key replay/new wrapping pass | Qualify full FE crash recovery, FE/BE encrypted-data interoperability, rotation, clients and upgrade/rollback constraints |
| Operational qualification | No sustained-load qualification is claimed | Define workloads/thresholds and record CPU, memory, latency and throughput results with ownership |
| Redistribution and support | Dependency/configuration and migration references are provided | Complete component inventories, licences/notices, corresponding-source/relinking materials, deployment instructions and supported-feature matrix bound FIPS claims by evidence and validation records |

Update this document and repeat the affected checks when versions, digests, enabled crypto paths,
or deployment environments change. Updates to OpenSSL core/TLS, companion JARs, the OS, and the JDK
must preserve the validated modules and permitted configuration.

[openssl-certificate]: https://csrc.nist.gov/projects/cryptographic-module-validation-program/certificate/4985
[openssl-policy]: https://csrc.nist.gov/CSRC/media/projects/cryptographic-module-validation-program/documents/security-policies/140sp4985.pdf
[openssl-install]: https://github.com/openssl/openssl/blob/openssl-3.5.9/README-FIPS.md
[bc-certificate]: https://csrc.nist.gov/projects/cryptographic-module-validation-program/certificate/4943
[bc-guide]: https://downloads.bouncycastle.org/fips-java/docs/BC-FJA-UserGuide-2.1.1.pdf
[openssl-source]: https://github.com/openssl/openssl/releases/download/openssl-3.5.9/openssl-3.5.9.tar.gz
[openssl-sha]: https://github.com/openssl/openssl/releases/download/openssl-3.5.9/openssl-3.5.9.tar.gz.sha256
[provider-source]: https://github.com/openssl/openssl/releases/download/openssl-3.1.2/openssl-3.1.2.tar.gz
[provider-sha]: https://github.com/openssl/openssl/releases/download/openssl-3.1.2/openssl-3.1.2.tar.gz.sha256
[jdk-binary]: https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.21%2B9/OpenJDK17U-jdk_x64_linux_hotspot_17.0.21_9.tar.gz
[jdk-sha]: https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.21%2B9/OpenJDK17U-jdk_x64_linux_hotspot_17.0.21_9.tar.gz.sha256.txt
[bc-core]: https://repo.maven.apache.org/maven2/org/bouncycastle/bc-fips/2.1.1/bc-fips-2.1.1.jar
[bc-core-sha]: https://repo.maven.apache.org/maven2/org/bouncycastle/bc-fips/2.1.1/bc-fips-2.1.1.jar.sha256
[bc-util]: https://repo.maven.apache.org/maven2/org/bouncycastle/bcutil-fips/2.1.8/bcutil-fips-2.1.8.jar
[bc-util-sha]: https://repo.maven.apache.org/maven2/org/bouncycastle/bcutil-fips/2.1.8/bcutil-fips-2.1.8.jar.sha256
[bc-tls]: https://repo.maven.apache.org/maven2/org/bouncycastle/bctls-fips/2.1.25/bctls-fips-2.1.25.jar
[bc-tls-sha]: https://repo.maven.apache.org/maven2/org/bouncycastle/bctls-fips/2.1.25/bctls-fips-2.1.25.jar.sha256
[bc-pkix]: https://repo.maven.apache.org/maven2/org/bouncycastle/bcpkix-fips/2.1.13/bcpkix-fips-2.1.13.jar
[bc-pkix-sha]: https://repo.maven.apache.org/maven2/org/bouncycastle/bcpkix-fips/2.1.13/bcpkix-fips-2.1.13.jar.sha256
[ubuntu-manifest]: https://registry-1.docker.io/v2/library/ubuntu/manifests/sha256:78d4744ead1f5b19b04d0df558ab182a5300903900755e515b2ca4d10eb2044d
[toolchain-manifest]: https://registry-1.docker.io/v2/starrocks/toolchains-ubuntu/manifests/sha256:77953142a6afe1ce134d1b30d1e127630370b33dc87b2839bc3780995d562e9e
[starlet-manifest]: https://registry-1.docker.io/v2/starrocks/starlet-artifacts-ubuntu22/manifests/sha256:a998789167f179b3508df7a889b74d8cdf7fbc399ada233a867e3d9b9f241dea
[bc-asn1]: https://github.com/bcgit/bc-java/wiki/CVE%E2%80%902026%E2%80%9014682
[bc-ocsp]: https://github.com/bcgit/bc-java/wiki/CVE%E2%80%902026%E2%80%9058062
[bc-name]: https://github.com/bcgit/bc-java/wiki/CVE%E2%80%902026%E2%80%908763
[openssl-dhx]: https://openssl-library.org/news/secadv/20260609.txt
