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

### PostgreSQL JDBC authentication

FE metadata connections and embedded JDBC scans apply the same `PostgresJdbcPolicy` before
constructing their connection pools. It requires a driver advertising `requireAuth`, adds
`requireAuth=!md5` when no policy is configured, and rejects a configured policy that permits
MD5. Stricter authentication policies are preserved. The effective restriction is placed in the
JDBC URL because PostgreSQL URL options take precedence over connection properties.

The packaged PostgreSQL driver is 42.7.12. External drivers that silently ignore `requireAuth`
are rejected. PostgreSQL servers requesting MD5 password authentication fail before a
password response is sent. Configure and qualify a permitted authentication mechanism before
upgrading such catalogs. Other JDBC drivers and PostgreSQL mechanisms still need their own
provider/TLS qualification this restriction closes the identified MD5 path only.

### MariaDB JDBC authentication

FE uses MariaDB 3.3.2 for MariaDB and MySQL metadata catalogs. FE and embedded scans using
that driver apply `MariaDbJdbcPolicy` before pool creation. The permitted plugin list
is `mysql_native_password,caching_sha2_password`. Ed25519's independent curve implementation,
GSSAPI and PAM plugins are rejected. A configured native-password-only list is preserved.
The driver parses the URL before and after policy insertion, including duplicate keys.
Drivers without `restrictedAuth` support and incompatible policies fail before opening a pool.

MariaDB always computes native-password in the initial handshake, before consulting its
plugin restriction. A policy excluding native-password therefore cannot be enforced by this
driver and is rejected. Its special initial `mysql_clear_password` response over TLS performs
no local cryptography and retains the driver's behavior. Clear-password authentication switches
are excluded. This is not a new TLS requirement or qualification of remote database authentication.

The restricted JDK provider removes competing SHA implementations. The driver's initial
SHA-1 response and native-password/caching-SHA2 plugin responses therefore use BCFIPS and fail
after missing-provider or module errors, including retries. Runtime tests encode real driver
handshakes and process native-password/caching-SHA2 fast responses, compare healthy bytes, and
require no emitted response bytes on failure. Full caching-SHA2 authentication and remote TLS
exchanges, and external MySQL Connector/J, remain separately unqualified.

### Oracle JDBC authentication

FE metadata connections and embedded JDBC scans apply `OracleJdbcPolicy` before pool creation.
The bundled `ojdbc10:19.18.0.0` driver receives `oracle.jdbc.allowedLogonVersion=12a` and
`oracle.net.useJCEAPI=true`. The driver requires the server's O7L_MR capability and a 12C
password verifier. It derives the session key with PBKDF2-HMAC-SHA512 instead of MD5 and uses
JCE for encryption. The configured JCA providers route these operations to BCFIPS.

The policy parses URL options with the actual Oracle driver, preserves other connection
properties and rejects authentication or JCE overrides that weaken these settings. It passes
the resolved URL and required properties to Hikari so URL options cannot restore a downgrade.
Oracle OCI URLs are rejected because they select a native driver outside this Java configuration.

Use Oracle Database 12.1.0.2 or later with a 12C password verifier for the catalog account.
Check `DBA_USERS.PASSWORD_VERSIONS`. If the account lacks a 12C verifier, configure
`SQLNET.ALLOWED_LOGON_VERSION_SERVER=12a` and reset its password through the database's
credential-management procedure. Update the catalog credential to match. BCFIPS enforces a 112-bit minimum password input
length for PBKDF2. For ASCII passwords this requires at least 14 characters.
The [Oracle JDBC authentication documentation](https://docs.oracle.com/en/database/oracle/oracle-database/19/jjdbc/client-side-security.html)
describes the server and password-version requirements.

The runtime test uses the bundled authentication helper with MD5 operations blocked. It
checks the generated session key against BCFIPS PBKDF2 and tests provider failures and retries.
A remote Oracle login and TCPS connection still require integration testing.

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
| PostgreSQL JDBC authentication | FE and embedded scans reject MD5 authentication through the driver's `requireAuth` policy, including URL overrides and drivers lacking this capability. Other authentication mechanisms remain subject to qualification |
| MariaDB JDBC authentication | Both pools reject unqualified negotiated plugins, including Ed25519. Initial handshake and native-password/caching-SHA2 digests use BCFIPS without SUN SHA fallback. Remote authentication/TLS and other external drivers remain separate qualification work |
| Oracle JDBC authentication | FE and embedded pools require Thin-driver 12a authentication and JCE cryptography. URL downgrades fail before pool creation. A 12C password verifier is required. Remote Oracle authentication and TCPS remain integration tests |
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
