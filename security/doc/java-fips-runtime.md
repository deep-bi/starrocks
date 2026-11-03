# Java FIPS startup and runtime contract

Java providers are configured by the JVM security file and launcher options.
There is no application security initializer or JNI security callback.
This reference covers FE and the Java components embedded in BE/CN.
The verification scope and remaining qualification work are listed below.
The [baseline](fips-baseline.md), [dependencies](java-fips-dependencies.md) and
[TLS/store policy](java-fips-tls.md) remain part of the contract.

## Deployment contract

FE and embedded Java use the pinned FIPS providers unconditionally, without a Maven profile
or build mode flag. Use clean builds when updating dependencies: dependency copying can retain
stale JARs that package validation rejects. Compilation targets Java 17. The release and qualification
baseline is Temurin HotSpot `17.0.21+9` on Ubuntu 24.04 Linux x86_64.
Startup does not require that exact JDK vendor, patch version or OS identity. The existing launchers
reject Java below 17 for FE and warn for BE/CN. Other environments still require qualification.
[`java-fips-pinned-dependencies.json`](../java-fips-pinned-dependencies.json) pins the JDK
and the four provider artifacts: `bc-fips:2.1.1`, `bcutil-fips:2.1.8`,
`bctls-fips:2.1.25` and `bcpkix-fips:2.1.13`.
Image installation verifies the JDK archive before extracting a new runtime, using the
commands in [the dependency handoff](java-fips-dependencies.md#runtime-handoff-for-the-image-task).
It converts the archive's 118 public PKCS12 anchors to certificate-only JKS
`lib/security/jssecacerts`, with password `changeit`.
Signed BC JARs and private keys are unchanged.

| Input | FE | BE/CN |
| --- | --- | --- |
| JVM security configuration | `conf/java-fips.security` | `conf/java-fips.security` |
| Signed provider JARs | `lib` | `lib/hadoop/common` |
| Entry point | `bin/start_fe.sh` | `bin/start_be.sh` or `bin/start_cn.sh` |

`build.sh` copies `conf/java-fips.security`, the service configuration and
`security/{check-java-fips.py,java-fips-pinned-dependencies.json}` into the distribution.
The fixed JVM flags are in `JAVA_OPTS` in `conf/fe.conf`, `conf/be.conf` and `conf/cn.conf`.
There is no post-build preparation or option-generation script.
Validate each assembled distribution explicitly before deployment.

```bash
python3 security/check-java-fips.py --providers output/fe/lib output/fe
python3 security/check-java-fips.py --providers output/be/lib/hadoop/common output/be
```

Neither `build.sh` nor Maven executes the Python validator.
Ship the configuration and validator together with the JARs.
`check-java-fips.py` is the single validator. Package paths select the full artifact scan.
`--home` and `--component` select startup validation. Launchers use `--preflight` to check
injected options before the Java version probe, then `--options` for the final JVM settings and pinned files.
Startup requires Python 3 and Bash.
The configuration is not embedded in application JARs. Startup compares its SHA-256 with
`security_properties_sha256` in the pin manifest. Update that pin when changing the configuration.

The runtime, JARs, launchers and security configuration are deployment-owned and fixed for
the JVM lifetime. Deny service-user writes to them. Protect private stores and password-bearing
configuration with installation permissions, normally mode `0600`.
Installation paths and JVM option values containing whitespace remain unsupported.
External drivers, UDFs and custom classpaths require separate qualification.
A new security-sensitive dependency path needs its own provider and failure evidence.

## Runtime responsibilities

| Responsibility | Owner |
| --- | --- |
| Artifact pins, hashes, signed provider bytes and uniqueness | Explicit `check-java-fips.py` validation. Only the four pinned provider artifacts are checked at startup |
| OS and JDK identity | Release packaging and deployment qualification startup does not enforce exact identity |
| Required provider options, configuration SHA-256 | `check-java-fips.py --home ...` after launcher overrides |
| Provider registration, BCJSSE FIPS mode and algorithm configuration | `conf/java-fips.security`, consumed by JVM security machinery. `FipsTlsCryptoProvider` delegates cryptography to BCFIPS and certificate paths to `StarRocksPKIX` |
| Approved-only and pure-Java settings before any provider use | Service `JAVA_OPTS` configuration, passed to the FE JVM or through `LIBHDFS_OPTS` to `JNI_CreateJavaVM` |
| Integrity/self-tests, approved-operation restrictions and module error state | BCFIPS when the provider or operation is used |
| Explicit application provider selection | `CryptoUtils`, without reflection, readiness state or initialization hooks |
| Default dependency services | JVM provider configuration plus focused provider and failure tests |
| Netty/Azure TLS | Required native-TLS disable flags cover Netty and the bundled gRPC, GCS and Kudu copies before initialization. Default JDK TLS uses BCJSSE |
| Hadoop/Paimon TLS | Maven exclusions in StarRocks and the Paimon FIPS build keep WildFly OpenSSL out of the packages. Package validation rejects retained copies. Default factories use BCJSSE |
| HTTP authentication | FE HTTP clients and Hadoop S3A use `HttpAuthenticationPolicy`. The required AWS transport property selects `FipsAwsHttpService` for default SDK v2 clients, including the copy inside Paimon's plugin. Paimon's Apache HTTP factory permits only Basic HTTP challenge authentication for both bundled SDK versions |
| Hadoop AES-CTR and randomness | `FipsAesCtrCryptoCodec` selects BCFIPS without RNG fallback, `HadoopExt` rejects incompatible filesystem configuration |
| Hadoop SASL mechanisms | `FipsSaslProvider` in the shared `starrocks-hadoop-ext.jar` exposes only JDK PLAIN/EXTERNAL. JdkSASL remains separately configured |
| PostgreSQL JDBC authentication | `PostgresJdbcPolicy` rejects MD5 negotiation in FE and embedded scans using the driver's `requireAuth` capability |
| MariaDB JDBC authentication | `MariaDbJdbcPolicy` restricts negotiated plugins to native-password and caching-SHA2, `FipsJdkProvider` removes competing JDK SHA services so their default digests cannot fall back |
| FE key wrapping after JVM restart | Existing key records are replayed and their bytes imported into the new module instance BCFIPS generates each new wrapping IV internally |
| Application certificate-only BCFKS/JKS/PEM trust loading | Existing `TlsUtils` consumer methods |
| Default JSSE trust | Standard JSSE properties, certificate-only JKS read by BCFIPS |
| Configured TLS credentials and hostname verification | Existing TLS consumers |
| Bundled ordinary BC in the Tencent connector | Exclude the incompatible dependencies from FIPS packaging |
| Runtime immutability and permissions | Deployment installation |

`getJNIEnv` creates the JVM lazily, attaches native workers and uses libhdfs thread-local
caching. No FIPS-specific libhdfs patch or security callback is required.
Required FIPS options are checked by `check-java-fips.py` before the service starts.

## JVM options and trust inputs

The service configuration supplies these settings. The launchers validate them without
adding or changing them. `${STARROCKS_HOME}` is expanded by the existing configuration loader.

```text
-Dorg.bouncycastle.fips.approved_only=true
-Dorg.bouncycastle.native.cpu_variant=java
-Dio.netty.handler.ssl.noOpenSsl=true
-Dio.grpc.netty.shaded.io.netty.handler.ssl.noOpenSsl=true
-Dcom.google.cloud.hadoop.repackaged.gcs.io.grpc.netty.shaded.io.netty.handler.ssl.noOpenSsl=true
-Dorg.apache.kudu.shaded.io.netty.handler.ssl.noOpenSsl=true
-Dorg.bouncycastle.jca.enable_jks=true
-Djavax.net.ssl.trustStoreProvider=BCFIPS
-Djavax.net.ssl.trustStoreType=JKS
-Djavax.net.ssl.trustStorePassword=changeit
-Djava.security.properties=<service>/conf/java-fips.security
```

The checked-in default trust-store password is `changeit`. When supplying a custom
`javax.net.ssl.trustStore`, configure its actual nonempty password in `JAVA_OPTS`.
Empty paths, `NONE`, conflicting providers and non-JKS global trust types are rejected.
The default path uses the prepared JDK's `jssecacerts`.
No custom default `SSLContext` is installed.

JSSE does not read PEM. Existing global PEM or BCFKS trust inputs must be converted offline
to certificate-only JKS before deployment, with the intended anchors preserved.
For each certificate alias in a BCFKS trust store, export its public certificate and import it
into a new JKS store. For PEM input, start with the import step, using one alias per anchor.
Run this during installation, outside the service JVM.

```bash
keytool -exportcert -rfc -alias ca -keystore trust.bcfks -storetype BCFKS \
  -storepass:env SR_TRUST_PASSWORD -file ca.pem \
  -providerclass org.bouncycastle.jcajce.provider.BouncyCastleFipsProvider \
  -providerpath /srv/starrocks/fe/lib/bc-fips-2.1.1.jar \
  -J-Dorg.bouncycastle.fips.approved_only=true -J-Dorg.bouncycastle.native.cpu_variant=java
keytool -importcert -noprompt -alias ca -file ca.pem -keystore trust.jks \
  -storetype JKS -storepass:env SR_TRUST_PASSWORD
```

Verify all expected certificate aliases after conversion. Do not import private keys.
Application-specific HTTPS and LDAP trust settings accept certificate-only
BCFKS, JKS and PEM through their existing loaders. Private-key stores remain BCFKS.
The BC JKS implementation rejects private-key JKS input.

FE and BE/CN validate the selected final service options after legacy-option selection.
Missing or conflicting required provider/trust settings and a different security overlay are rejected.
Existing installations must merge the required flags into their configuration. Legacy
`JAVA_OPTS_FOR_JDK_11` and `JAVA_OPTS_FOR_JDK_9_AND_LATER` still replace `JAVA_OPTS`. If used,
they must contain the same required settings. The validator rejects missing settings.
`JAVA_TOOL_OPTIONS` and `_JAVA_OPTIONS` are checked for the same conflicts.
`JDK_JAVA_OPTIONS` is checked for FE. The JNI invocation does not consume it.
Unrelated options and agents are not banned, and unused legacy variables are not validated.
Service options must use literal spaces as separators: libhdfs does not split tabs or newlines.
Java `@argument-files` and `-XX:VMOptionsFile` are rejected, including in consumed environment
variables, so an option file cannot override the validated properties. Environment option
parsing preserves JVM backslash and quoting behavior.

Full nested-JAR, provider-conflict and duplicate-artifact inspection runs in explicit package validation.
Startup checks the four pinned provider files and the security overlay without rescanning every
application JAR, following manifest classpaths or prohibiting executable configuration directories.
Deployment owns the installed classpath and any agents, drivers or UDFs. Changing them requires
rechecking provider usage and failure handling for the affected paths.
Extension classloaders delegate BC classes to their parent.
Readers receive `java-utils` transitively through `jni-connector` with compile scope.

## Provider and application boundaries

The [BC guide][guide], section 2.2, documents BCFIPS, BCJSSE `fips:BCFIPS` and SUN together.
Appendix A describes approved-only mode as a restriction on the module's own operations.
The [module policy][policy], sections 2.1 and 6.3, describes that enforcement boundary.
It does not enforce application TLS, hostname, trust-store or advisory-specific policy.
The guide uses `org.bouncycastle.fips.approved_only`, which is the property tested here.
The policy text in section 6.3 spells it `approved-only`. Do not substitute that spelling.

`StarRocksPKIX` delegates certificate parsing and path validation to BCFIPS and rejects
CA chains with email or URI name constraints. This restriction covers the pinned core's
trailing-dot name-constraint bypass. DNS subject alternative names and constraint bases
ending in a dot are also rejected. Other DNS constraints remain supported.
BCJSSE uses `fips:com.starrocks.connector.share.security.FipsTlsCryptoProvider` so its
explicit certificate-provider lookups also pass through this policy. All TLS cryptographic
operations still select BCFIPS. The policy preserves caller-supplied path checkers and
revocation settings. Supplied OCSP responses are checked against the certificate serial and
issuer name/key hashes in the validated path. An unrelated response is removed from a private
copy of the parameters and validation runs again with a fresh BCFIPS revocation checker.
Configured OCSP retrieval and CRL fallback remain available. The original caller's response
map and the signed BC artifacts are unchanged.

`FipsJdkProvider` replaces the full SUN registration with six pinned JDK services, retaining
the provider name `SUN`: `NativePRNGNonBlocking`, MD5, collection/indexed certificate stores,
Java policy and JAAS configuration. SHA digests, signatures, keystores and certificate-path
implementations are not exposed by that registration. The signed BC artifacts are unchanged.
The module retains its default DRBG, prediction resistance and entropy health tests.
`securerandom.strongAlgorithms=NativePRNGNonBlocking:SUN` supplies module entropy.
The supported application random path is explicit BCFIPS `DEFAULT`.
MD5 remains registered for existing nonsecurity checksums. Each authentication consumer must
reject its security use. FE HTTP clients, Hadoop S3A and Paimon's isolated AWS client install
Basic-only authentication registries. The required JVM property
`software.amazon.awssdk.http.service.impl=com.starrocks.connector.share.security.FipsAwsHttpService`
also applies that policy to default AWS SDK v2 clients, including Iceberg, S3, STS and Glue.
Digest and NTLM challenges cannot reach SUN MD5 through these clients. The restriction also applies to
cached clients after module failure and does not depend on where proxy credentials came from.
Retaining MD5 and OS entropy is not a universal no-fallback claim.

The overlay uses `com.starrocks.connector.share.security.FipsSaslProvider` from the shared
Hadoop extension JAR in place of SunSASL. Its factory retains the JDK PLAIN/EXTERNAL
implementations and filters both advertised and requested mechanisms. DIGEST-MD5,
CRAM-MD5 and NTLM are unavailable, including to Hadoop's directly cached factories.
`jdk.sasl.disabledMechanisms` alone is insufficient because those factories bypass it.
Hadoop delegation-token authentication and HDFS block-transfer paths requiring DIGEST-MD5
are unsupported and fail closed. HDFS may report an upstream null-factory error.
Do not enable `ipc.client.fallback-to-simple-auth-allowed` as a workaround.
PLAIN/EXTERNAL still require appropriately secured transport. GSSAPI remains unqualified.

The Netty native-TLS disable flags are required in FE, BE and CN options and checked after
legacy and environment overrides. They also cover Azure's default Reactor transport and
credential clients, plus the separately shaded gRPC, GCS and Kudu transports.
Signed BC artifacts and native compression/network transports are unchanged.

Elasticsearch HTTPS uses BCJSSE trust validation and normal hostname verification.
Install the Elasticsearch CA in the configured trust store before connecting.

Hadoop's separate WildFly OpenSSL dependency is excluded and banned by both Maven reactors.
FE and Paimon-reader consume Paimon `1.4.2-fips-deepbi1`, which excludes ordinary BC and
WildFly in its own build. StarRocks copies these artifacts without rewriting them.
Its S3 options and FileIO implementation remain available. Package inspection rejects remaining
WildFly provider classes, including nested copies. ABFS, S3A and Paimon's isolated Hadoop
default TLS factories use BCJSSE, an explicitly requested OpenSSL factory fails. Azure Data Lake
Gen1 (`adl://`) is unsupported because its SDK requires the excluded binding during class
initialization. This restriction does not apply to ABFS.

Hadoop AES-CTR uses `FipsAesCtrCryptoCodec`, which keeps Hadoop's JCE stream implementation
and selects BCFIPS for its cipher and `DEFAULT` randomness without the upstream RNG fallback.
The shipped `core-site.xml` supplies these defaults. The existing `HadoopExt` configuration
hook also sets them before filesystem creation, including JNI and reader configurations without
default XML resources. Explicit incompatible codec, provider or RNG settings are rejected,
SM4 and native OpenSSL codecs are disabled. Keep the singleton approved codec list when
customizing Hadoop configuration. This does not qualify unrelated Commons Crypto consumers.

Ranger throws when a configured credential store cannot be loaded or its requested credential
cannot be read. Unsupported JCEKS stores therefore stop TLS client construction instead of
becoming null passwords/default managers. Absent optional credential-store settings remain
supported. Credential-store migration is not provided.

Provider order alone cannot prevent fallback. The unrestricted SUN provider previously allowed
raw SHA digests to succeed after BCFIPS failed before provider loading. The restricted registration
removes that fallback: default SHA-1/SHA-256/SHA-384/SHA-512 calls fail when BCFIPS is missing
or in an error state, including retries.
Application security digests use `CryptoUtils.messageDigest`, which selects BCFIPS explicitly.
Explicit BCFIPS digest/random operations, dependency HMAC and default TLS fail in the tested
initialization and active-module error cases, including retries.
Dependencies implementing their own cryptography or using remaining MD5/entropy services
still require individual qualification and restrictions. Default SHA selection does not qualify
an entire authentication or signing construction.
The packaged AWS SDK v2 `AbstractAwsSigner` uses a default-provider SHA-256 thread-local.
The focused signing test verified BCFIPS for that digest and its HMAC, and rejected signing
with a failed module both before and after first use. No remote AWS request was sent.
The full connector signing/digest inventory and external service qualification remain open.

FE and embedded JDBC scans reject PostgreSQL MD5 password authentication before sending a
password response. The shared policy checks driver support and URL options, preventing the
bundled driver's SUN MD5 path from bypassing approved-only mode.
See [the authentication and restart contracts](java-fips-tls.md). Other JDBC mechanisms are
not qualified merely by rejecting MD5.

FE's bundled MariaDB 3.3.2 driver also serves MySQL metadata catalogs. Both FE and embedded
MariaDB scans apply `restrictedAuth` before creating a pool, rejecting Ed25519, GSSAPI and PAM
authentication switches. The driver performs its initial native-password response outside its
plugin loader, the restricted JDK provider therefore covers that direct SHA-1 call as well as
the native-password and caching-SHA2 plugin digests. The runtime regression exercises actual
handshake encoding and plugin processing, including failed-module retries before any response
bytes are sent. Caching-SHA2 coverage is its fast response, complete authentication, remote TLS,
externally supplied MySQL Connector/J and other drivers remain unqualified.

Oracle FE and embedded JDBC pools require `oracle.jdbc.allowedLogonVersion=12a` and
`oracle.net.useJCEAPI=true`. Driver-parsed URL properties cannot override the policy.
The 12a authentication helper uses BCFIPS PBKDF2-HMAC-SHA512 for its session key and the
runtime test rejects any MD5 operation. See the Oracle credential requirements in
[the TLS handoff](java-fips-tls.md#oracle-jdbc-authentication).

Focused tests verify actual BCFIPS/BCJSSE selection, approved-only execution and disabled BC
native services on Java/native workers and reader classloaders.
RSA-1024 generation is rejected directly by BCFIPS and RSA-2048 succeeds without an
application key-size precheck. SUN entropy and certificate collections are available.

Transport selection is controlled by application configuration.
FE HTTP and internal HTTP callers are available. HTTPS is optional.
MySQL uses `ssl_force_secure_transport` and its existing localhost exception.
A missing key store is rejected when MySQL secure transport or HTTPS is configured.
Configured HTTP and LDAP remain available. TLS consumers verify certificates and hostnames.
HTTPS-to-HTTP redirects are rejected in the FIPS HTTP client. A failed TLS operation is not retried
through another provider or a plaintext connection.

The FIPS package excludes the audited Tencent connector bundle containing ordinary BC.
There is no vendor-wide ban on configuration keys, paths or classes.
S3/S3A aliases remain available. Tencent connector dependencies are absent from all builds.
Using the excluded connector implementation remains unsupported.

## Build and verification commands

Run from the repository root. Set `JAVA_HOME` to the prepared Temurin runtime described in
[the image handoff](java-fips-dependencies.md#runtime-handoff-for-the-image-task), including its
certificate-only JKS `jssecacerts`. Set `THRIFT` to a Thrift 0.24.0 compiler executable.
The native harness also requires the production libhdfs sources prepared by the third-party
build, a C compiler, Make and ripgrep. Use a new temporary directory for each runtime fixture.

```bash
python3 -m unittest discover -s security/tests -p 'test_java_*.py'
mvn -B -f java-extensions/pom.xml clean package -DskipTests
mvn -B -f fe/pom.xml -pl fe-server -am clean package -DskipTests \
  -Dstarrocks.thrift="$THRIFT"
python3 security/check-java-fips.py --providers fe/fe-server/target/lib fe/fe-server/target/lib
python3 security/check-java-fips.py --providers java-extensions/hadoop-lib/target/hadoop-lib java-extensions
mvn -B -f fe/pom.xml -pl fe-core -am test \
  -Dtest=JavaTlsTest,LdapTlsTest,JavaCryptoProviderTest,JDBCAuthenticationPolicyTest,AwsCloudConfigurationTest,CloudConfigurationFactoryTest,S3FileSystemTest,MysqlPasswordTest,MysqlSslEngineConfigTest,HttpSslContextBuilderTest,MysqlJsseTlsCiphersTest,SslUtilTest,NormalKeyTest,KeyMgrTest,OpenIdConnectAuthenticationTest,LDAPAuthProviderTest,LDAPMemberOfDirectoryTest,LDAPGroupProviderStaleCacheTest,LDAPGroupProviderCaseInsensitiveTest,HttpUtilsTest,EsRestClientTest,EsRestClientTlsTest,RangerCredentialProviderTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dmaven.clean.skip=true \
  -Dstarrocks.thrift="$THRIFT"
mvn -B -f java-extensions/pom.xml -pl java-utils,hadoop-ext,jdbc-bridge,udf-extensions -am test \
  -Dtest=CloudConfigurationApplierTest,OverwriteAwsDefaultCredentialsProviderTest,AssumedRoleCredentialProviderTest,PostgresJdbcPolicyTest,MariaDbJdbcPolicyTest,OracleJdbcPolicyTest,JDBCScannerTest,UDFHelperDirectBufferTest \
  -Dsurefire.failIfNoSpecifiedTests=false
bash security/tests/run-dependency-crypto.sh "$JAVA_HOME" /tmp/starrocks-dependency-crypto
bash security/tests/run-embedded-jvm.sh "$JAVA_HOME" /tmp/starrocks-embedded-jvm
```

`run-dependency-crypto.sh` exercises Netty, ABFS/S3A, Paimon's isolated Hadoop TLS factory,
Hadoop AES-CTR/randomness, SASL, FE HTTP, default AWS transports, shared/isolated S3A authentication,
Paimon's AWS v1 client and default AWS v2 transport, and MariaDB and Oracle authentication.
The tests include missing providers and module failures. They also check certificate constraints
through explicit and default BCJSSE TLS contexts and JCA PKIX services. OCSP checks cover
valid, revoked, unknown and unrelated responses, plus CRL fallback through PKIX and BCJSSE.
The HTTP authentication tests open loopback servers and proxies and require local socket access.
The native helper builds a caller against production libhdfs JVM-creation code. It runs
Java/native workers and Hadoop extension operations, then invokes the BE/CN launchers with
the harness as their executable. This does not start full BE/CN services or perform remote I/O.

Assemble a disposable FE fixture after packaging. The live helper creates temporary credentials
and requires empty metadata. `mktemp` supplies a fresh fixture directory:

```bash
fe_fixture=$(mktemp -d /tmp/starrocks-fips-fe.XXXXXX)
mkdir -p "$fe_fixture"/{bin,conf,lib,security,meta,log}
cp bin/common.sh bin/start_fe.sh "$fe_fixture/bin/"
cp conf/hadoop_env.sh conf/udf_security.policy conf/java-fips.security conf/core-site.xml "$fe_fixture/conf/"
cp security/check-java-fips.py security/java-fips-pinned-dependencies.json "$fe_fixture/security/"
cp fe/fe-server/target/starrocks-fe.jar fe/fe-server/target/lib/*.jar \
  java-extensions/hadoop-ext/target/starrocks-hadoop-ext.jar "$fe_fixture/lib/"
python3 security/check-java-fips.py --providers "$fe_fixture/lib" "$fe_fixture"
python3 security/tests/fe-listener-smoke.py --home "$fe_fixture" --java-home "$JAVA_HOME"
python3 security/tests/launcher-faults.py --home "$fe_fixture" --java-home "$JAVA_HOME"
```

Use `--no-tls` with `fe-listener-smoke.py` on a separate fresh fixture to test startup with TLS
disabled. FE unit tests install approved-only providers through `CryptoTestExtension`, because
Surefire uses an isolated test classloader. JDK providers remain available to independent
fixture peers, so consumer tests must establish explicit provider selection. Launcher and
native-harness tests use the packaged security overlay without that test extension.

## Verification status

The builds and runtime tests passed on Temurin `17.0.21+9` on Ubuntu 24.04 x86_64.
This includes Paimon's TLS and HTTP authentication paths in StarRocks. Paimon was used as
a prebuilt dependency. Its separate source build and tests were not rerun.
Full BE/CN services and deployment images still need testing.

| Check | Evidence and scope |
| --- | --- |
| Python package/options/launcher tests | 20 passed, including malformed inherited options, JVM option files, environment parsing, required native-TLS flags and nested/stale WildFly bindings |
| Java-extension build and regression tests | Maven clean/package and Checkstyle passed. All 72 focused tests passed: 44 Hadoop extension, 21 JDBC scanner and 7 UDF direct-buffer tests |
| FE regression tests | 197 focused tests passed through Maven against the fresh source build, covering key handling/reload, all three JDBC policies, TLS, LDAP, Elasticsearch and Ranger credentials |
| PostgreSQL JDBC authentication | The 42.7.12 driver rejects MD5 before a password response. URL overrides and unsupported drivers fail. Stricter policies remain. FE and embedded pool guards passed with the production overlay and after injected BCFIPS failure. Initial SCRAM negotiation passed. A complete remote SCRAM exchange was not tested |
| MariaDB JDBC authentication | The 3.3.2 initial handshake and native-password/caching-SHA2 fast responses match BCFIPS-computed bytes. Ed25519/GSSAPI/PAM switches and incompatible URL policies are rejected. Missing-provider and readiness/active module faults reject three retries before authentication bytes are emitted. Both pool guards passed |
| Restricted JDK services | Java/native workers assert the exact six-service SUN registration. Default SHA-1/SHA-256/SHA-384/SHA-512 operations reject missing BCFIPS and repeated readiness/active faults. Entropy, certificate collections, policy/JAAS and nonsecurity MD5 compatibility remain available |
| Java key reload | `JavaCryptoProviderTest` passed, including two fresh JVMs using the production overlay to persist/replay keys, decrypt an existing wrapped key and create a new wrap with an internal IV. Parquet compatibility passed. Full FE crash recovery and native BE rotation were not tested |
| Hadoop AES-CTR and randomness | Stream round trips and actual BCFIPS cipher/RNG identity passed, incompatible configuration, missing providers and module errors rejected, including cached encryptors/decryptors, randomness and retries |
| S3A proxy authentication | Actual Hadoop AWS clients rejected Digest/NTLM challenges. A loopback proxy confirmed that Digest received no authentication response and Basic completed authentication. Bucket and JVM proxy credentials retained the restriction. Fresh and cached clients passed readiness/active module-failure checks. Missing authentication-binding dependencies failed without restoring SDK defaults |
| FE and AWS HTTP authentication | Actual FE shared/cost clients, default AWS S3/STS/Glue transports, Iceberg S3 and the isolated Paimon factory reject Digest, NTLM, Negotiate and Kerberos challenges. Loopback exchanges verify Basic authentication and rejection of MD5 and MD5-sess challenges, including cached clients after a module failure. Failed initialization prevents new clients from falling back |
| Oracle JDBC authentication | Both pools enforce 12a and JCE settings. Driver-parsed URL downgrade tests pass. The actual 19.18.0.0 authentication helper produces a session key matching BCFIPS PBKDF2-HMAC-SHA512 with MD5 operations blocked. Missing-provider and readiness/active module faults reject three retries without a password response. Remote login and TCPS were not tested |
| OCSP response binding | Valid responses pass through PKIX validation/building and BCJSSE. Revoked, unknown, wrong-serial and wrong-issuer responses are rejected. CRL fallback accepts a valid certificate and rejects a revoked certificate. Caller response maps remain unchanged. The embedded libhdfs and native-worker harness runs the same checks |
| Certificate constraints | Sixteen chain cases passed through default/explicit BCJSSE trust managers, TLS 1.2/1.3 handshakes and default PKIX building/validation. Email/URI constraints and trailing-dot DNS names were rejected. Supported chains, mixed trust anchors and caller-supplied checkers behaved as expected |
| Dependency TLS | ABFS/S3A, Paimon's isolated Hadoop factory, Azure and packaged Netty copies selected BCJSSE and generated ClientHello messages. Fresh and cached contexts rejected injected module errors and retries. Explicit Hadoop native TLS was rejected |
| Netty native-TLS controls | Removing the unshaded, gRPC or GCS flag re-enabled native TLS and failed the regression check. Kudu native TLS was absent locally, startup validation requires its disable flag |
| SASL | JDK APIs, Hadoop direct factories and RPC delegation-token negotiation rejected unsupported mechanisms before and after module failure, PLAIN/EXTERNAL behavior passed |
| Embedded JNI and readers | Harness linked to production libhdfs passed lazy JVM creation, native-worker attachment and certificate-policy checks. All six reader classloaders passed provider/codec checks, loaded the shared S3A policy and constructed a default AWS S3 client with the restricted authentication registry. BE/CN launcher runs passed with this harness as their executable |
| Live FE listeners | Freshly packaged FE passed HTTP, HTTPS and MySQL TLS requests plus wrong-host and untrusted-peer rejection. A separate fresh FE with TLS disabled passed HTTP and plaintext MySQL. Both tested addresses were loopback (`127.0.0.1` and `127.0.1.1`), so this does not prove nonloopback plaintext rejection |
| Launcher failures | Missing or modified provider, altered overlay, legacy approved-mode/native-TLS/trust overrides, unrestricted AWS transport selection and conflicting inherited JVM options were rejected before FE startup. The signed provider and configuration were restored after the tests |
| Elasticsearch | Five TLS tests passed, including real loopback TLS requests, trusted peers accepted, expired/untrusted/wrong-host peers, downgrade redirects, invalid stores and missing providers rejected |
| Ranger | 4 tests and Checkstyle passed: absent optional settings, readable credentials, missing aliases, unsupported JCEKS and corrupt stores. Tests construct the actual TLS client without remote requests |
| Dependency enforcement | Both reactors validated, dependency trees exclude WildFly and Enforcer rejects its reintroduction. Ordinary BC artifacts are also banned |
| Paimon packaging | FE and reader use the prebuilt `1.4.2-fips-deepbi1` bundle, S3 and OSS JARs. S3 contains the restricted HTTP factory and isolated AWS service. No ordinary BC classes, WildFly classes/native libraries or provider registrations remain. The TLS and HTTP authentication integration tests passed on Java 17 |
| Fresh packaging | Both complete StarRocks Maven reactors passed clean/package and Checkstyle using Thrift 0.24.0 for FE. Packaging copies the Paimon FIPS artifacts without a rewrite hook |
| Package inspection | Fresh FE and Java-extension artifacts passed full package inspection and pinned provider hash checks. The core provider reported `READY` and its expected internal HMAC |
| Static checks | Shell syntax and diff whitespace checks passed |

FE requires a Thrift 0.24.0 compiler matching the pinned Java library. Thrift 0.20 generates
incompatible service APIs. Tests use the standard JaCoCo and Byte Buddy settings.
FE Surefire and live listener tests need local socket access.
The runtime harness keeps reader classloaders alive until JVM exit so Hadoop shutdown hooks
can load their classes.

[guide]: https://downloads.bouncycastle.org/fips-java/docs/BC-FJA-UserGuide-2.1.1.pdf

[policy]: https://csrc.nist.gov/CSRC/media/projects/cryptographic-module-validation-program/documents/security-policies/140sp4943.pdf
