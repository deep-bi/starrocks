# Java TLS and key handling

## Startup contract

Consumers use providers installed by the JVM security configuration.
They never register a provider or retry an operation with another provider.

- Service options set `-Dorg.bouncycastle.fips.approved_only=true` and `-Dorg.bouncycastle.native.cpu_variant=java` before loading BC.
- The security overlay installs the unchanged BCFIPS provider and BCJSSE configured with
  `fips:com.starrocks.connector.share.security.FipsTlsCryptoProvider`.
- `-Dorg.bouncycastle.jca.enable_jks=true` enables certificate-only JKS trust stores before provider initialization.
- The overlay configures JCA selection for dependency code, `PKIX` key/trust managers and BCJSSE TLS.
- Launchers check pinned artifacts, the security overlay and effective options. BCFIPS performs its integrity/self-tests when used runtime tests check actual provider selection in FE and embedded JVMs.

There is no application mode switch or package mode marker. Consumer provider selection and
store restrictions are unconditional. BCFIPS enforces its operation restrictions without
consumer-side readiness state. See [the runtime handoff](java-fips-runtime.md) for startup
configuration and actual executing-thread/provider verification.

## Guidance

The [BC provider guide](https://downloads.bouncycastle.org/fips-java/docs/BC-FJA-UserGuide-2.1.1.pdf),
sections 7, 13 and appendix G, permits BCFKS private keys and read-only certificate-only JKS.
The [module policy](https://csrc.nist.gov/CSRC/media/projects/cryptographic-module-validation-program/documents/security-policies/140sp4943.pdf),
sections 2.2 and 2.3, governs internally generated GCM nonces and store password requirements.
The shared certificate policy mitigates the name-constraint bypass by rejecting CA chains
with email or URI name constraints. Other certificate advisory mitigations remain open.

### Certificate name constraints

The pinned core can accept an excluded email or URI host when the certificate name ends
in a dot. `StarRocksPKIX` rejects CA certificates and trust anchors with either constraint
type before accepting a path. This also rejects otherwise valid email/URI-constrained
chains. Use a chain without those constraint types. DNS names in subject alternative names
and name constraints must not end in a dot. Other DNS name constraints remain supported
and are still validated by BCFIPS. An unrelated restricted trust anchor does not prevent
validation through a supported anchor in the same trust store.

`FipsTlsCryptoProvider` routes BCJSSE certificate paths through this policy while leaving
cryptographic operations in BCFIPS. The policy also handles default JCA PKIX lookups.
It preserves existing path checkers and revocation settings. It does not change the signed
provider JAR. The OCSP response check below addresses response binding. ASN.1 advisory work remains separate.

## Configuration and migration

| Setting | Default | Requirement |
| --- | --- | --- |
| `ssl_keystore_type` | `BCFKS` | Must be `BCFKS`, shared by MySQL/JDBC listener TLS and HTTPS |
| `ssl_keystore_location`, `ssl_keystore_password`, `ssl_key_password` | Existing settings | Deployment-controlled BCFKS file and valid store/key passwords |
| `ssl_truststore_type` | `JKS` | Certificate-only `BCFKS`, `JKS` or `PEM`, independent of private-key type |
| `ssl_truststore_location`, `ssl_truststore_password` | Existing settings | MySQL listener trust, when configured |
| `authentication_ldap_simple_ssl_conn_trust_store_type` | Empty, explicit type required | Explicit `BCFKS`, `JKS` or `PEM` when a login trust path is supplied, also a security integration property |
| Group provider `ldap_ssl_conn_trust_store_type` | Empty, explicit type required | Explicit type when a group trust path is supplied |
| Login `authentication_ldap_simple_ssl_conn_allow_insecure` | `true` | Set `false` to use verified LDAPS |
| Group `ldap_ssl_conn_allow_insecure` | `true` | Set `false` and use `ldaps://` to enable verified LDAPS |
| `com.sun.jndi.ldap.object.disableEndpointIdentification` | JDK , | Startup rejects empty or `true` values. LDAP sockets explicitly enable endpoint identification |
| `javax.net.ssl.trustStore`, `javax.net.ssl.trustStoreType`, `javax.net.ssl.trustStorePassword` | JDK ,s | Default trust for HTTPS, outbound HTTP and LDAP without a separate trust path |

Without an explicit JVM trust path, the FIPS loader reads `java.home/lib/security/jssecacerts`
if present, otherwise `cacerts`, with type `JKS` and password `changeit` unless overridden.
The runtime launchers require certificate-only JKS for global JSSE trust and a
nonempty password for an explicit JVM trust path. Convert global PEM or BCFKS anchors offline
as described in [the runtime contract](java-fips-runtime.md). The application-specific HTTPS
and LDAP trust loaders retain certificate-only BCFKS, JKS and PEM support.
The BC certificate-only JKS reader must be enabled before installing BCFIPS.
The pinned Temurin archive actually ships PKCS12 `cacerts`. Image installation converts those
public anchors offline to certificate-only JKS `jssecacerts`, using the commands in
[the dependency handoff](java-fips-dependencies.md#runtime-handoff-for-the-image-task).
There is no type detection or retry in the FIPS store path.
Private-key entries in trust stores, PKCS12 trust stores and empty store/key passwords
are rejected in that path.

Migrate legacy private stores offline using a controlled copy and the pinned JDK/provider.
The source JKS private-key decryption is a legacy migration operation outside the approved
service path. The destination is BCFKS. Retain a protected backup for rollback.
For example, this command prompts for passwords instead of putting them on the command line:

```bash
"$JAVA_HOME/bin/keytool" -importkeystore \
  -srckeystore /secure/legacy.jks -srcstoretype JKS \
  -destkeystore /secure/server.bcfks -deststoretype BCFKS \
  -providerclass org.bouncycastle.jcajce.provider.BouncyCastleFipsProvider \
  -providerpath /opt/starrocks/fe/lib/bc-fips-2.1.1.jar \
  -J-Dorg.bouncycastle.fips.approved_only=true \
  -J-Dorg.bouncycastle.native.cpu_variant=java
```

If an existing private-key password is too short for BCFIPS, re-protect that key in the
offline JKS copy with a suitable password before importing it.
Use distinct private-key and certificate-only trust stores.
After migration, inspect aliases and certificate chains and test both listener loaders before
restarting the service. For PEM trust, use certificate files only, never private-key PEM files.

BCFKS store/key passwords must meet BCFIPS PBKDF requirements, including at least 14 encoded bytes.
Use high-entropy generated secrets. Length alone does not establish strength.
Protect configuration files and stores with service-owner permissions and a controlled secret
distribution mechanism. The existing Config fields remain strings in memory.
Temporary password arrays used by the loaders are cleared after use.
Tests generate keys/passwords in temporary directories. No private keys are checked in.

## Consumer behavior

- MySQL and HTTPS explicitly select BCJSSE contexts and `PKIX` key/trust manager factories.
- Netty receives the actual selected JSSE provider and BCFIPS randomness.
- Cipher filtering starts with enabled BCJSSE ,s.
- FIPS listener engines use TLS 1.2 or 1.3 and AES-GCM suites. User cipher filters can only narrow this set.
- Empty permitted cipher sets, wrong store types/passwords, corrupt stores, missing providers and expired private credentials fail loading.
- Configured LDAPS login and group lookup use verified provider-backed contexts. Endpoint-check overrides are rejected.
- Transport remains configurable. Selecting the FIPS provider does not force HTTP or LDAP to use TLS.
- `HttpUtils` verifies trust and hostnames. FIPS HTTPS requests reject HTTPS-to-HTTP redirects.
- Elasticsearch HTTPS uses BCJSSE with the configured trust anchors and OkHttp hostname verification,
  including explicit HTTPS node URLs when the legacy SSL flag is false. Cross-scheme redirects are disabled.
- FIPS JWKS downloads use that HTTP client. Local JWKS files keep their existing path.
- Nimbus RSA key construction and JWT verification explicitly use BCFIPS, with a minimum 2048-bit RSA key.
- MySQL challenge randomness and SHA-1 digests explicitly use BCFIPS. Digest failures propagate instead of producing null output.
- FE AES keys use BCFIPS key generation. Wrapping uses BCFIPS AES-GCM with a module-generated nonce.
- FE random string, UUID seed and Iceberg temporary-file helpers use the same randomness selection.

MySQL password hashes and challenge/reply bytes keep the existing protocol.
A permitted SHA-1 digest does not by itself qualify the MySQL password authentication
construction. No password hash migration or new authentication mechanism is introduced.

Key wrapping keeps `12-byte nonce || ciphertext || 16-byte tag`, without a length prefix.
This matches Parquet `encrypt(false, ...)` and `be/src/fs/encrypt_file.cpp`.
New Java wraps can be read by the ordinary Java implementation, and FIPS Java can read old
wraps. The Java restart contract is described below. Native execution and cross-process
FE/BE rotation remain integration work. Byte-format compatibility is not that qualification.

### Key reload after JVM restart

FE reloads the configured master key and persisted `EncryptionKeyPB` records into a fresh
`KeyMgr`. Each wrap or unwrap imports the recovered parent bytes into BCFIPS using a new
`SecretKeySpec`. The application does not serialize provider key objects, ciphers or DRBG state.
For new wraps, BCFIPS generates a fresh 96-bit random IV internally existing wraps retain their
stored IV for decryption. A missing or changed configured master key remains an error.

The module policy's section 2.2 calls for key redistribution after power restoration. This
integration's restart behavior is fresh key import into the new module instance, preserving the
keys needed to read existing data. Do not infer a requirement to replace every persisted key:
[NIST IG C.H, Scenario 2](https://csrc.nist.gov/CSRC/media/Projects/cryptographic-module-validation-program/documents/fips%20140-3/FIPS%20140-3%20IG.pdf)
covers internally generated random IVs. The explicit counter-restoration/new-key alternatives
belong to Scenario 3a's deterministic IVs. This distinction is not a vendor endorsement of the
StarRocks deployment retain the module policy with the release qualification evidence.

`JavaCryptoProviderTest` exercises the production key serialization/replay and wrap/unwrap
paths in two fresh JVMs. It checks recovery of an existing wrapped key, generation of a new
module IV and compatibility with the existing Parquet representation. No key-format migration
or automatic master-key replacement is introduced.

### LDAP and outbound JDBC coverage

The shipped Java security overlay selects approved-only BCFIPS cryptography and BCJSSE TLS.
LDAP applies provider, certificate and hostname controls in StarRocks. JDBC driver selection,
authentication settings, TLS options and FIPS qualification are the operator's responsibility.
StarRocks does not maintain a FIPS driver allowlist, block Oracle, or rewrite driver-specific
security settings. An unreviewed driver is not rejected merely because it is unreviewed.

| Path | StarRocks controls |
| --- | --- |
| FE LDAP direct DN bind, service-account search and user bind, DN patterns | `LDAPAuthProvider`, `SslUtils` and `LdapSslSocketFactory` apply shared readiness checks and verified BCJSSE LDAPS to global configuration and security integrations |
| FE LDAP memberOf lookup and separate group provider | `LDAPGroupProvider` checks readiness for directory connections, refreshes and cached group reads. LDAPS verifies certificates and hostnames |
| FE JDBC metadata catalogs | Checks readiness before Hikari initialization and new or replacement physical connections. Cached borrows do not recheck readiness |
| FE Iceberg JDBC catalogs | Checks readiness before catalog initialization and new or replacement physical connections. URLs and `jdbc.*` driver properties pass through without security-policy rewrites |
| Embedded BE/CN JDBC scans | Checks readiness in `open()` and before physical connection creation. The configured driver and URL pass through without security-policy rewrites. Result reads do not recheck readiness |

Existing connector compatibility rules still apply. FE metadata uses its existing database
schema resolvers and maps MySQL metadata URLs and drivers to MariaDB. Embedded scans keep
the configured external driver. Iceberg catalog compatibility remains Iceberg's contract.

### JDBC configuration requirements

The operator must select a compatible driver and configure its authentication, cryptographic
providers, trust stores, client keys and server identity verification. The shared Java runtime
does not guarantee that arbitrary driver code uses approved cryptography. Drivers can use
private implementations, native libraries or explicitly selected providers. The compatibility
provider retains MD5 for nonsecurity checksums, so an MD5 password implementation does not
necessarily fail automatically. Plaintext connections remain configurable and do not provide
FIPS-protected transport.

The following observations apply to the inspected artifacts. They are configuration guidance
and known limitations, not restrictions enforced by StarRocks or complete driver qualification.

| Inspected driver | Configuration and known limitations |
| --- | --- |
| PostgreSQL 42.7.12 | Configure `requireAuth` to exclude MD5 and unqualified GSS/SSPI mechanisms, and disable GSS transport encryption with `gssEncMode=disable`. For TLS use `sslmode=verify-full` and `sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory` with JVM stores |
| MariaDB 3.3.2 | `restrictedAuth` can select `mysql_native_password,caching_sha2_password`. The initial handshake computes native-password before consulting this setting. Full caching-SHA2 needs verified TLS with the current BCFIPS configuration. Use BCFKS client keys and `trustStoreType=BCFKS` for PEM `serverSslCert` anchors |
| MySQL Connector/J 8.2.0 | Configure authentication plugins explicitly. For verified TLS use `sslMode=VERIFY_IDENTITY`, BCJSSE SSL context and manager providers, `keyStoreProvider=BCFIPS` and `fipsCompliantJsse=true`. Plaintext RSA password encryption is incompatible with the current approved-only module |
| Oracle Thin ojdbc10 19.18.0.0 | The artifact accepts unsolicited RADIUS/Kerberos despite an empty offered list. RADIUS can reach private DES, MD5 and `java.util.Random`, and `useJCEAPI=true` does not cover that implementation. TCPS still leaves unsolicited Kerberos reachable. Complete FIPS qualification is not established, and StarRocks does not block this artifact |
| SQL Server 12.4.2.jre11 | SQL password authentication and Java TLS were examined. The driver's `fips=true` option disables its hostname verifier. Connection-level trust-store password encryption supplies an AES-GCM IV that the current module rejects. Integrated/Azure authentication, Always Encrypted and native paths require separate qualification |
| ClickHouse 0.4.6 | The TLS fixtures use `http_connection_provider=HTTP_URL_CONNECTION` and strict SSL verification. Explicit `server_version` and `server_time_zone` avoid server-supplied JDBC configuration overrides. Other transports and custom clients require separate qualification |

Use direct `jdbc.*` keys or URL options for case-sensitive Iceberg driver settings such as
`jdbc.requireAuth`. Existing `iceberg.catalog.*` handling lowercases property names.

Missing providers or a module error stop JDBC operations at the readiness boundaries above.
Provider construction performs startup checks. Repeated checks read the module error state
without taking the startup lock while the module is healthy. These checks do not validate the
driver's cryptographic behavior.

FE metadata cached borrows and Iceberg actions on pooled connections may continue after
provider removal or a module error. Embedded scans do not recheck during `hasNext()` or
`getNextChunk()`, so an already-open result set may remain readable. Driver-internal retries,
Hikari validation and keepalive calls can run without another StarRocks readiness check.

Focused tests and the embedded-JVM harness exercise explicitly configured protocol branches.
They do not qualify every driver configuration, remote databases, full BE/CN services or the
product for FIPS certification.

### HTTP authentication

FE `HttpUtils`, cost prediction and profile downloads use the shared HTTP authentication
policy. The default AWS SDK v2 transport uses `FipsAwsHttpService`, selected by the required
`software.amazon.awssdk.http.service.impl` JVM property. This covers independently constructed
S3, STS and Glue clients, including Iceberg and SDK credential clients. Hadoop S3A explicitly
installs the same registry. Paimon's FIPS build supplies an Apache `HttpClients` factory that
restricts HTTP challenge authentication for both bundled AWS SDK versions. Its plugin-local
`FipsAwsHttpService` applies that restriction to default SDK v2 transports, including credential
clients. These sources live in the Paimon repository and ship in its S3 artifact.

These clients permit Basic authentication for HTTP targets and proxies. Digest, NTLM,
Negotiate and Kerberos challenges receive no authentication response. Proxy credentials from
Hadoop configuration, bucket overrides and SDK settings retain the restriction. Startup rejects
a missing or conflicting AWS transport property. Failure to load the selected transport stops
client construction instead of restoring the SDK default.

Use Basic authentication over an appropriately secured connection, or a proxy without
authentication. Basic and unauthenticated requests remain available. AWS request signing is
unchanged. Tests cover the actual clients, loopback exchanges, cached clients after module
failure and default AWS transport selection in all six embedded reader classloaders.

## Audited boundaries and restrictions

| Path | Finding and handoff |
| --- | --- |
| FE/BE key wrapping | Java implementation and key reimport after JVM restart are explicit. Native implementation is unchanged. Cross-process existing-data and FE/BE rotation qualification remains open |
| `HttpUtils` callers | Configured HTTP remains available, HTTPS verifies certificates and hostnames and rejects downgrade redirects. Internal FE HTTP routing is unchanged |
| Elasticsearch | Uses `TlsUtils` global trust configuration and verifies hostnames. Install the cluster CA in the configured certificate-only trust store before connecting. Untrusted, expired and wrong-host certificates are rejected. Configured HTTP remains available |
| Azure/Netty | Native TLS is disabled by a required startup option. The , Azure/Reactor TLS context uses BCJSSE. Focused tests cover provider selection and module-error rejection, not remote Azure I/O |
| Other outbound clients | Cloud SDK, Iceberg, JDBC, Hive and Hadoop clients have independent factories. Remaining paths still need compatibility and provider/trust qualification |
| Java extensions | The Hadoop codec selects BCFIPS explicitly. JDBC, UDF and other reader code delegates to dependencies or user code, those independent operations are not covered by the FE helper |
| Outbound JDBC | StarRocks checks shared runtime readiness at connection boundaries. The operator configures and qualifies driver authentication and TLS. Driver-specific security settings are not rewritten or rejected |
| ODPS | Both FE `OdpsConnector` and embedded `OdpsSplitScanner` construct `AliyunAccount` with access ID/key. These callers do not select HMAC-SM3 directly. The SDK's ordinary-BC HMAC-SM3 signer is incompatible with BCFIPS. Dependency dispatch and live requests still need checking |
| Hadoop/JNI | SASL DIGEST-MD5, CRAM-MD5 and NTLM are rejected at the provider factory boundary, including direct Hadoop factory calls. Delegation-token/HDFS transfer paths requiring them are unsupported. PLAIN/EXTERNAL retain JDK behavior. AES-CTR uses the explicit BCFIPS codec/RNG without fallback, incompatible overrides and SM4/native codecs are rejected. Kerberos and arbitrary drivers remain separate qualification work |
| Hadoop cloud TLS | WildFly OpenSSL is excluded, including the copy embedded in the packaged Paimon S3 plugin. ABFS/S3A and Paimon's isolated Hadoop , factories select BCJSSE, explicit native TLS fails. Azure Data Lake Gen1 is unsupported because its SDK requires WildFly at initialization |
| HTTP authentication | FE HTTP clients, the default AWS SDK v2 transport, Hadoop S3A and Paimon's isolated AWS client expose only Basic authentication. Digest and NTLM cannot use SUN MD5 through their registries, including cached clients after a module error |
| Ranger credentials | Configured unreadable stores or missing credentials throw before TLS client construction. Unsupported JCEKS therefore fails explicitly, no migration is added. Absent optional credential stores retain their existing behavior |
| Non-security digests | Query caches, SQL compatibility hashes and artifact checksums still have SHA-256/MD5 calls outside the changed authentication/key paths. Classify their uses before altering compatibility |
| Certificate mitigations | Configured BCJSSE and default PKIX validation reject email/URI-constrained CA chains and DNS names ending in a dot. Supplied OCSP responses must match the certificate serial and issuer name/key hashes. Invalid bindings trigger a new validation attempt without that response, preserving configured OCSP/CRL fallback. ASN.1 advisory work remains open |
| Startup | Startup checks pinned provider artifacts, security configuration and required options without enforcing exact OS/JDK identity. Focused tests verify actual providers and mode in executing threads. The checked overlay installs BCJSSE in FIPS mode with the shared certificate policy. See the runtime handoff |
| Packaging | Tencent connector dependencies are excluded from all builds. Independent S3 dependencies remain |

The existing transport selection remains configurable.
MySQL requires a key store when `ssl_force_secure_transport=true`, with the existing localhost exception.
HTTPS requires a key store when enabled. Invalid configured credentials fail without provider fallback.
The provider configuration does not force HTTPS-only listeners, MySQL TLS or internal FE HTTPS routing.
Live checks and current scope verification are recorded in [the runtime handoff](java-fips-runtime.md).

The test bootstrap adds BCFIPS and BCJSSE after the JDK providers deliberately, so explicit
consumer selection is exercised without relying on provider priority.

The LDAP socket factory uses JNDI's named-socket path with BCJSSE.
It connects the transport with the configured timeout and then wraps it using the explicit
peer hostname. This avoids BCJSSE losing the peer name when JNDI connects an unconnected
SSL socket. The real JNDI login and group-bind tests cover both timeout paths.

## Verification

See [the runtime handoff](java-fips-runtime.md) for current commands and evidence.
FE tests always register the approved-only providers through the test-only JUnit extension.
The real startup checks use the packaged overlay, independent of that test setup.

The checks cover provider selection, certificate-only trust stores, BCFKS private stores,
wrong passwords, expired/untrusted/wrong-host peers, LDAP binds, TLS sockets, live FE listeners,
MySQL password compatibility, RSA JWT verification, key wrapping and missing/failed providers.
Generated test credentials stay in temporary directories. Surefire properties do not contain
private-store passwords.

Cross-process FE/BE key exchange, existing-data restart/rotation, remote connectors, complete test
suites and performance/deployment qualification remain open. The runtime handoff distinguishes
live FE startup from the embedded-JVM harness and records the remaining qualification limits.
