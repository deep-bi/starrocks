# Java FIPS dependencies and packaging

This branch uses the pinned FIPS Java dependencies unconditionally.
Package validation is an explicit post-build step.
The [runtime handoff](java-fips-runtime.md) records implemented startup enforcement, live FE
checks and the remaining qualification limits. This is not a production FIPS qualification.

## Supported dependency scope

The FIPS package excludes `hadoop-cos` and `cos_api-bundle` because the audited bundle contains
ordinary BC classes that Maven exclusions cannot remove. This is a dependency incompatibility.
There is no blanket Tencent configuration, URI or class-name ban.
Tencent connector dependencies are absent from all builds. Existing S3/S3A aliases remain unchanged.
The excluded connector implementation is unsupported in the FIPS package.
The separate Broker distribution is outside this FE/embedded-JVM change.

Keep the signed BC-FIPS provider unchanged and retain package checks for actual crypto conflicts.

## Pinned inputs

[The artifact manifest](../java-fips-pinned-dependencies.json) contains download URLs, versions and
expected SHA-256 values from [the baseline](fips-baseline.md). Verify these values for the
JDK archive and provider JARs used in the distribution.

| Artifact | SHA-256 |
| --- | --- |
| `OpenJDK17U-jdk_x64_linux_hotspot_17.0.21_9.tar.gz` | `8b9b611d917e3dfd8419d3566b5949226f69b052fed7e109c1368add1dfe8711` |
| `bc-fips-2.1.1.jar` | `a430d935ad6cec6d045930758457740f5a5f8f9715894e347f6800f7926a7321` |
| `bcutil-fips-2.1.8.jar` | `f44904f61552c823bcc3686a24052ee3135212d547754eaa2ff6532e210103cb` |
| `bctls-fips-2.1.25.jar` | `af604aa5b337725872651067a819ebc03aa298685572b0b2ad5343c786faad0a` |
| `bcpkix-fips-2.1.13.jar` | `cf161ba2f6b4505816f4661663b3e0771184a50ee5b178a0018c817b336f0e5c` |

The release and qualification baseline is Eclipse Temurin HotSpot `17.0.21+9` for Linux x86_64
on Ubuntu 24.04. These deployment pins do not impose exact JDK vendor, patch version or OS checks
at startup. Other environments still require qualification.
Both Maven reactors compile for Java 17, producing class-file major version 61.

Keep `bc-fips` unchanged, signed and external to fat JARs. The companion versions do not
repair the core vulnerabilities recorded in the baseline. PKIX is provided for consumers such
as Netty's PEM and certificate helpers.

## Provider integrity and policy erratum

`org.bouncycastle.util.DumpInfo` is a diagnostic class in the signed provider JAR, not a
generated StarRocks file. Its normal output computes the module HMAC through
`FipsStatus.getModuleHMAC()`. The `-c` option recomputes and prints that value without
modifying the JAR. Both that calculation and `META-INF/HMAC.SHA256` contain
`0f861e85a3fdc00707c9d164f8335bc81d81723944575d5ac80b4273c0d930a5` the whole-JAR SHA-256
matches the pin above and the normal diagnostic reports `READY`.

This HMAC matches the [BC-FJA 2.1.1 user guide, section 2.5][guide]. The NIST-hosted
[security policy, section 1.1][policy] instead prints
`d231036b466c2216d352c4c1ee9df602bc6e05514817bdfea2eab8a376566e90`.
Bouncy Castle maintainer David Hook [identifies this as a security-policy typo and confirms
that the lab has been notified](https://github.com/bcgit/bc-java/discussions/2405).
Retain this vendor explanation with release evidence. Do not replace the embedded HMAC,
rebuild, re-sign or otherwise modify the validated provider to match the policy's printed value.
The module remains pinned to 2.1.1 the maintainer's recommendation to use a newer release
requires a separate validation and compatibility decision.

## Build and package validation

Both reactors use FIPS dependencies by default. `build.sh` runs `package` for FE and Java
extensions. No mode flag is required or supported. Neither `build.sh` nor the Maven lifecycle
invokes the Python security utilities.

Run these from the repository root. Set `JAVA_HOME` to the prepared Temurin runtime and
`THRIFT` to a compiler matching the existing `libthrift` dependency, currently 0.24.0.

```bash
mvn -B -f java-extensions/pom.xml clean package -DskipTests
mvn -B -f fe/pom.xml -pl fe-server,plugin/hive-udf -am clean package \
  -DskipTests -Dstarrocks.thrift="$THRIFT"
```

FE and Hadoop-lib package the pinned provider JARs. Tencent COS and CHDFS dependencies are
excluded. Use a clean build: dependency copying does not delete stale JARs, and the package
checker rejects ordinary or duplicate providers.
WildFly OpenSSL is also excluded and banned in both reactors and by package inspection.
Hadoop ABFS/S3A use BCJSSE, explicit OpenSSL mode fails. Legacy Azure Data Lake Gen1 remains
unsupported because its SDK initializes the excluded native binding unconditionally.
FE and the reader use Paimon `1.4.2-fips-deepbi1`, built from `deep/1.4.2-deepbi3` in
[`deep-bi/paimon`](https://github.com/deep-bi/paimon). The FIPS branch is
`deep/1.4.2-fips-deepbi1`. Its Hadoop and S3 builds exclude ordinary BC and WildFly before
packaging, so StarRocks copies the published artifacts directly.

Paimon's S3 module owns the Apache `HttpClients` source override used by both bundled AWS
SDK versions. It restricts custom, default and system-property clients to Basic HTTP challenge
authentication. This prevents Digest and NTLM from using SUN MD5 for authentication. AWS
signing, proxy credentials and connection management are unchanged.
The module also supplies `com.starrocks.connector.share.security.FipsAwsHttpService` for the
plugin's isolated SDK v2 interface. StarRocks retains its shared implementation for the host
SDK. The two classloaders require separate implementations of that interface.

Paimon's S3 options and FileIO implementation remain available. Its isolated Hadoop TLS
factory uses the host's BCJSSE provider. The signed BC JARs remain separate and unchanged.
The FIPS Paimon artifacts must be published to the DeepBI Maven repository before a clean
CI build can resolve them. For local development, install `paimon-bundle`, `paimon-s3` and
`paimon-oss` from the FIPS branch using the build command in its S3 module README.
Direct Maven builds do not require a native FIPS build.

To record dependency trees without requiring installed sibling modules, append
`dependency:tree -Dincludes=org.bouncycastle -DoutputFile=/absolute/tree.txt -DappendOutput=true`
to a `package` invocation. Use a fresh output file for each reactor.

The existing `build.sh` also builds native prerequisites. Use the direct Maven commands for
independent Java work. Native OpenSSL and Docker configuration belong to separate tasks.

`build.sh` copies the repository's `conf/java-fips.security`, service configuration and startup
validators into each distribution. There is no preparation step or generated JVM configuration.
Validate the finished JARs explicitly before deployment.

```bash
python3 security/check-java-fips.py --providers output/fe/lib output/fe
python3 security/check-java-fips.py --providers output/be/lib/hadoop/common output/be
```

The backend package serves both BE and CN. Repeat validation after changing packaged JARs.
These commands do not modify the package, rebuild anything or run the runtime test suites.

## Dependency and classpath contract

The parent POMs supply four actual `provided` dependencies, with pinned dependency
management. FE server and Hadoop lib change their scopes to `compile` so the service packages
own the provider copies. Reader fat JARs exclude provided dependencies through the existing
assembly rules. Extension dependency copying is restricted to runtime scope.

| Consumer | Build output containing the four provider JARs | Distribution location |
| --- | --- | --- |
| FE | `fe/fe-server/target/lib` | `fe/lib` |
| BE and CN JVM | `java-extensions/hadoop-lib/target/hadoop-lib` | `be/lib/hadoop/common` |
| Reader, JDBC and UDF libraries | No private provider copies | Use the service JVM's providers |

`start_fe.sh` places the FE library JARs on the application classpath.
Both service classpaths must include `starrocks-hadoop-ext.jar`. It supplies the restricted SASL
factory and restricted JDK provider named in `conf/java-fips.security`, the BCFIPS Hadoop codec,
and the PostgreSQL/MariaDB authentication policies used by FE and embedded JDBC scans.
`conf/hadoop_env.sh` and `start_backend.sh` place Hadoop common on the embedded JVM's parent
classpath. Reader classloaders explicitly delegate BC classes to that parent. The existing JDBC
loader also delegates to its parent. No BC provider classes are shaded, relocated, stripped of
signatures or unpacked into another JAR.

The [module security policy, section 11.1][policy] permits the unchanged JAR on the classpath
or module path. The [provider guide, section 2][guide] recommends the module path on newer
JDKs. These services use the application classpath. The runtime checks exercise parent provider
loading the runtime handoff identifies the tested JDK and the deployment qualification boundary.
Duplicate provider artifacts are checked by explicit post-build validation.

Ordinary BC is excluded from the Hadoop dependency edge. The ODPS reader ships no ordinary
provider and receives the pinned BC classes from its parent. HMAC-SM3 remains unsupported.
Maven Enforcer rejects every `org.bouncycastle` artifact except the exact four selected
coordinates, including all ordinary jdk15on, jdk15to18, jdk18on and TLS variants.

`check-java-fips.py` inspects the assembled service distribution, including the FE's
packaged Spark/Hive artifacts. The checker walks nested JARs, recognizes relocated
`org/bouncycastle` classes, checks all four hashes and rejects missing, altered or extra provider
copies. It does not establish the absence of every possible non-BC cryptographic implementation.
It also rejects WildFly's native TLS provider inside ordinary or nested archives.

External JDBC drivers, user UDF JARs, custom Hadoop classpaths and remote Spark/Hive runtimes
are not qualified by these checks. Startup checks the pinned provider files and uses parent
loading for provider classes. Deployment classpath changes need separate qualification.

## Runtime handoff for the image task

Download the exact archive URL in the manifest. During image installation, verify and extract
it into a new directory, then convert its public CA truststore before starting services.

```bash
set -e
jdk_archive=/path/to/OpenJDK17U-jdk_x64_linux_hotspot_17.0.21_9.tar.gz
jdk_destination=/path/to/new-java-home
python3 security/check-java-fips.py --jdk-archive "$jdk_archive"
mkdir -- "$jdk_destination"
tar -xzf "$jdk_archive" --strip-components=1 -C "$jdk_destination"
"$jdk_destination/bin/keytool" -importkeystore -noprompt \
  -srckeystore "$jdk_destination/lib/security/cacerts" -srcstoretype PKCS12 -srcstorepass changeit \
  -destkeystore "$jdk_destination/lib/security/jssecacerts" -deststoretype JKS -deststorepass changeit
```

The commands check SHA-256 before extraction and refuse to merge into an existing directory.
The image task should install this tree as `/opt/java/openjdk`, with these required files:

- `bin/java`, `bin/javac`, `bin/keytool` and `bin/jarsigner`
- `lib/server/libjvm.so`
- `conf/security/java.security` and the vendor `release` file

Set `JAVA_HOME=/opt/java/openjdk` consistently for FE and every BE/CN JVM. Keep the full JDK
layout, including the converted certificate-only JKS `lib/security/jssecacerts`, and
use the embedded JVM from that tree. The [runtime handoff](java-fips-runtime.md) gives the
required packaged scripts, overlay, options and configuration permissions.

Service configuration sets `org.bouncycastle.fips.approved_only=true` and
`org.bouncycastle.native.cpu_variant=java` before crypto initialization, installs BCFIPS before
BCJSSE and configures BCJSSE with
`fips:com.starrocks.connector.share.security.FipsTlsCryptoProvider`. Its crypto operations
select BCFIPS and its certificate paths use the shared constraint policy.
Startup validates the required options after
configuration overrides and rejects missing or altered provider files before launch.
Required native-TLS disable options for Netty and its separately shaded gRPC, GCS and Kudu
copies prevent their retained native TLS libraries from bypassing BCJSSE, including Azure's
default HTTP transport. The exact properties are in the runtime handoff and service configuration.
Explicit BCFIPS operations and the qualified TLS paths reject module errors. `FipsJdkProvider`
retains only SUN OS entropy, nonsecurity MD5 checksums, certificate collections and JDK policy/JAAS
configuration. Removing its SHA services prevents default SHA digest fallback after BCFIPS failure.
Remaining MD5/entropy services and independent dependency implementations still require consumer checks.
The runtime handoff records the tested consumers and the remaining signing/digest inventory.

## Compatibility restrictions

| Dependency or behavior | Current configuration and qualification boundary |
| --- | --- |
| Tencent COS and CHDFS | These dependencies are excluded from both reactors. The incompatible COS chain is `com.qcloud.cos:hadoop-cos:3.3.0-8.3.2 -> com.qcloud:cos_api-bundle:5.6.137.2` the bundle contains ordinary and relocated BC classes, including `META-INF/versions/9/org/bouncycastle/jcajce/spec/DHDomainParameterSpec.class`. Maven exclusions cannot remove classes inside a bundle. S3/S3A aliases remain available the excluded connector implementation is unsupported |
| Aliyun HMAC-SM3 | `aliyun-java-auth:0.1.13-beta`, reached through `odps-sdk-core:0.48.7-public`, uses ordinary BC's lightweight `HMac` and `SM3Digest` in `SignAlgorithmHmacSM3`. Loading that signer fails with `NoClassDefFoundError: org/bouncycastle/crypto/Digest` on the selected classpath. FE `OdpsConnector` and embedded `OdpsSplitScanner` construct `AliyunAccount` with access ID/key and do not select SM3 directly. SM3 is unsupported dependency dispatch and live requests still require qualification |
| BCJSSE algorithm restrictions | `conf/java-fips.security` uses unconditional SHA-1 and DSA exclusions in TLS/certificate-path policy because BCJSSE does not parse every compound Temurin clause. Unsupported handshake-signature identifiers can still produce diagnostic warnings. The runtime and TLS handoffs record policy tests creating an SSL engine alone does not establish equivalent TLS policy |
| PostgreSQL JDBC authentication | The bundled driver is `42.7.12`. `PostgresJdbcPolicy` rejects MD5 authentication in FE and embedded scans using `requireAuth`, including URL overrides and drivers lacking this capability. External drivers and other authentication mechanisms require separate qualification |
| MariaDB JDBC authentication | The bundled driver is `3.3.2`, also used for FE MySQL metadata. Both pools restrict negotiated plugins to `mysql_native_password,caching_sha2_password` using driver-parsed `restrictedAuth`, Ed25519/GSSAPI/PAM switches are unsupported. The initial native-password response and supported plugins use default SHA digests, whose only configured implementation is BCFIPS. External MySQL Connector/J remains separately unqualified |
| Oracle JDBC authentication | The bundled `ojdbc10:19.18.0.0` driver uses Thin-driver 12a authentication and JCE cryptography in both pools. The policy resolves URL properties before enforcing `allowedLogonVersion=12a` and `useJCEAPI=true`. A 12C password verifier is required. The runtime test checks PBKDF2 session-key derivation with MD5 blocked |
| Hadoop authentication and crypto | The shared SASL provider exposes only PLAIN/EXTERNAL from SunSASL JdkSASL remains configured separately. DIGEST-MD5, CRAM-MD5 and NTLM are unavailable. Delegation-token/HDFS transfer paths requiring them are unsupported. AES-CTR uses the BCFIPS codec and RNG. GSSAPI and unrelated Commons Crypto consumers remain unqualified |
| HTTP authentication | FE HTTP clients, default AWS SDK v2 transports, Hadoop S3A and Paimon's AWS v1/v2 clients permit only Basic HTTP challenge authentication for targets and proxies. Their registries reject Digest, NTLM, Negotiate and Kerberos. The required AWS transport property selects the restricted transport for S3, STS, Glue and Iceberg clients, including Paimon's default v2 transport. AWS request signing is unchanged |
| Certificate constraints | The shared certificate policy rejects email/URI-constrained CA chains and trailing-dot DNS names. Other DNS constraints remain supported. Supplied OCSP responses must bind to the certificate and its actual issuer. Invalid responses are removed before retrying the configured revocation policy. BCJSSE cryptography and certificate-path validation still use the unchanged BCFIPS module |
| Native Java TLS bindings | WildFly is excluded, including the copy inside the packaged Paimon S3 plugin. ABFS/S3A and Paimon's isolated Hadoop TLS factory select BCJSSE explicit OpenSSL mode fails. Azure Data Lake Gen1 is unsupported. Required Netty disable flags cover its unshaded, gRPC, GCS and Kudu copies |
| JAR signature verification | The four pinned signatures report `jar verified`. The core signer chain is not trusted by the stock JDK truststore. Hash checks establish byte identity with the selected upstream artifacts this is not a claim that jarsigner authenticates the publisher through that truststore |

The core advisories in [the baseline](fips-baseline.md) require their documented mitigations
and qualification. Upgrading companion libraries does not repair defects inside `bc-fips`.

## Verification and evidence boundaries

[The runtime handoff](java-fips-runtime.md) is the source for current build/test results, test
counts, runtime identity and limitations. It distinguishes complete Maven packaging, focused
provider and TLS checks, live FE listeners and the libhdfs/JNI harness from full BE/CN service
qualification. Package inspection does not establish remote connector compatibility or the
absence of every non-BC cryptographic implementation.

After building the Java extensions, run provider integrity and embedded classloader checks:

```bash
python3 security/check-java-fips.py --providers java-extensions/hadoop-lib/target/hadoop-lib \
  --java-home "$JAVA_HOME" java-extensions
bash security/tests/run-embedded-jvm.sh "$JAVA_HOME" /tmp/starrocks-packaging-check
```

The first command checks provider hashes and package contents, verifies the provider JAR
signatures with `jarsigner`, and invokes the vendor integrity/self-test diagnostic. The JNI
harness exercises JVM creation, native-worker attachment and six reader classloaders. Use a
new temporary directory for each harness run. These checks do not start full BE/CN services.

Run the dependency-ban regression after resolving both reactors' dependencies:

```bash
python3 security/tests/reject-ordinary-bc.py
```

This test creates temporary children of both workspace parents with a direct
`bcprov-jdk18on:1.85` dependency. It runs Maven offline and requires the specific
banned-dependency error, rather than accepting any build failure.

Remote JDBC/ODPS/Hadoop operations, a complete PostgreSQL SCRAM exchange, full BE/CN startup,
cross-process FE/BE key exchange and rotation, deployment performance, Docker integration and the
host/CPU/VM/container entropy configuration remain separate qualification work. Local build
and runtime logs are validation artifacts, not release qualification by themselves.

[policy]: https://csrc.nist.gov/CSRC/media/projects/cryptographic-module-validation-program/documents/security-policies/140sp4943.pdf
[guide]: https://downloads.bouncycastle.org/fips-java/docs/BC-FJA-UserGuide-2.1.1.pdf
