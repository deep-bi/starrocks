# DeepBI workflows

| Workflow | Trigger | Purpose |
| --- | --- | --- |
| [deepbi-ut.yml](deepbi-ut.yml) | PRs and manual | FE and Java-extension UT, plus C++ formatting |
| [deepbi-fe-ut.yml](deepbi-fe-ut.yml) | Combined UT or manual | FE and Java-extension UT |
| [deepbi-be-ut.yml](deepbi-be-ut.yml) | Manual after remote build | Prebuilt BE UT on Ubuntu AMD64 |
| [deepbi-sql-it.yml](deepbi-sql-it.yml) | Manual after remote build | SQL tests against RC production packages |
| [ci-doc-checker.yml](ci-doc-checker.yml) | PRs to `main` and `branch*` | Documentation checks |
| [ci-vale.yml](ci-vale.yml) | Documentation PRs to `main` and `branch*` | Documentation style |

For manual RC tests, select the branch used for the build in **Run workflow**
Manual workflow definitions must exist on the default branch to appear in the UI

## BE UT

Enter `rc_s3_uri`, for example:

```text
s3://deep-packages/StarRocks/release-candidates/4.1.6-deepbi2/
```

The directory must contain:

- `StarRocks-be-ut-ubuntu-amd64.tar.gz`
- `StarRocks-be-ut-ubuntu-amd64.sha256`, referencing the archive's basename

The remote build must package Debug test executables, native libraries, and Java
extensions using the same image as `RUNTIME_IMAGE` in the BE workflow
The job downloads through `packages.deep.bi`, verifies the checksum, and runs
`run-be-ut.sh --run-only` with shared-data and local S3 tests enabled
Test data and the launcher come from the selected branch

## SQL IT

| Input | Value or default |
| --- | --- |
| `rc_version` | Required, for example `4.1.6-deepbi2` |
| `platform` | `centos-amd64` (default) or `rhel9-arm64` |
| `cluster_mode` | `shared_nothing` (default) or `shared_data` |
| `exclude_components` | `aws,oss,hdfs,hive,kafka,trino-client,spark-client,hive-client` |
| `exclude_datasets` | `ssb,tpch,tpcds` |

The package and its sibling `.sha256` file must be available at:

```text
https://packages.deep.bi/StarRocks/release-candidates/<rc_version>/StarRocks-<rc_version>-<platform>.tar.gz
```

Each dispatch runs one platform and cluster mode with one FE and three BEs or
CNs. Shared-data mode adds RustFS. MySQL is enabled by default. Add `mysql` to
`exclude_components` to disable its fixture and cases

Concurrent cases run first, then sequential cases, even after a concurrent failure
Missing required dependencies, unknown exclusions, and unexpected SQL runtime skips fail the job

## Test exclusions

These groups describe the workflow defaults and inherited test-selection rules
Changing a dispatch exclusion does not provision the service or data it needs
Configure services in `.github/scripts/sql-it-cluster.sh` and the generated
`sr.conf` in `.github/scripts/sql_it.py` before removing an exclusion

Each run uploads selected case identifiers and the dependency filters in its
case manifests
Use those reports for the exact selection under that run's inputs

### IT services and datasets excluded by default

| Group | How to enable | Blocker |
| --- | --- | --- |
| Real AWS, including role and STS cases | Configure the AWS fields from `test/conf/sr.conf` and remove `aws` from `exclude_components` | AWS resources, credentials, and role permissions are not provisioned by this workflow |
| External object-storage cases using OSS settings | Provide the bucket, endpoint, region, credentials, and expected fixtures, then remove `oss` | The local RustFS shared-data bucket does not supply this suite's external datasets or OSS configuration |
| HDFS and broker cases | Start HDFS and the required broker, seed their files, configure the HDFS fields, and remove `hdfs` | No HDFS cluster, broker, or fixtures are provisioned |
| Hive-backed catalog cases, including Iceberg, Hudi, and Delta Lake | Start the required metastores and storage, seed their tables, configure each catalog URI, and remove `hive` plus any required storage exclusions | No metastores or seeded catalog fixtures are provisioned |
| Kafka and routine-load cases | Start Kafka, provide its tools and test topics, configure broker fields, and remove `kafka` | No Kafka service, tools, or fixtures are provisioned |
| Trino client cases | Provide a Trino service and client settings, then remove `trino-client` and the exclusions for its dependencies | No Trino environment is provisioned |
| Spark client cases | Provide Spark and client settings, then remove `spark-client` and the exclusions for its dependencies | No Spark environment is provisioned |
| Hive client cases | Provide Hive and client settings, then remove `hive-client` and the exclusions for its dependencies | No Hive client environment is provisioned |
| SSB, TPC-H, and TPC-DS dataset cases | Populate the required `ssb`, `tpch`, or `tpcds` directories under `test/common/data/` and remove the matching exclusions | These datasets are not bundled with the repository or downloaded by the workflow |

A case can depend on several excluded groups
Removing one exclusion may leave another dependency blocking that case
The combined external environment has not been validated within hosted-runner
memory, disk, or execution limits

### SQL selection and result-check exclusions

| Group | How to enable | Blocker |
| --- | --- | --- |
| Cases for the other cluster mode | Dispatch `shared_data` for `@cloud` cases or `shared_nothing` for `@native` cases | One dispatch provisions one mode, and the opposite mode's tags are filtered out |
| Repository-ignored SQL files | Investigate the cases and remove the relevant entries from `test/lib/skip.py` | Five files are ignored, including Iceberg AUTO-refresh cases whose mode is not exposed to users, while the other ignore reasons are not documented in that list |
| Unchecked SQL results | Review `[UC]` statements and `skip_res_cmd` in `test/lib/skip.py`, then add stable assertions where possible | These statements execute but some result comparisons are deliberately suppressed, including nondeterministic output |

Sequential tags do not exclude cases from the workflow
They move those cases to the second pass
MySQL and shared-data RustFS cases are enabled by default

### Unit-test exclusions

| Group | How to enable | Blocker |
| --- | --- | --- |
| Conditional UT skips and assumptions | Satisfy the condition reported in the XML or JSON summary and rerun | Some cases depend on optional libraries, profiling settings, fault injection, platform support, or fixture capabilities |
| FE query-dump regression and rewrite tooling | Supply the dump configuration and run the dedicated `run-fe-ut.sh --dumpcase` path, or explicitly invoke the rewrite utility with its fixtures | The default FE launcher filters this tooling, and the regression class is also excluded by the default Surefire configuration |
| BE benchmarks and standalone function fuzzing | Build and invoke the relevant targets explicitly with bounded workloads | The normal BE test launcher excludes benchmark and standalone fuzz binaries |
| BE test sources outside the CMake test lists | Register the intended sources in `be/test/CMakeLists.txt` and resolve their build dependencies | Existing build omissions are preserved |
| BE HDFS tests when Hadoop libraries are absent | Restore the Java-extension Hadoop package at the path expected by `run-be-ut.sh` | The archive must include the Hadoop runtime to prevent this launcher fallback |
| UT for other architectures, platforms, or build flags | Add matching build images, runners, and configurations | The UT workflows cover Linux AMD64 with the configured build options, without an ARM or sanitizer matrix |

BE S3 and shared-data unit tests are enabled through `--with-aws` and
`--enable-shared-data`
Runtime UT skips are recorded, but a suite with no executed tests still fails

### Integration scenarios not wired into these workflows

These are coverage gaps, not case filters that can be removed at dispatch

| Group | How to enable | Blocker |
| --- | --- | --- |
| Restart and persistence | Add a phase that writes data, restarts services, and verifies the retained state | No restart-and-verify phase is implemented |
| Crash recovery and fault injection | Add controlled process termination or injected faults with recovery assertions | No fault orchestration or recovery criteria are implemented |
| Multi-FE failover and availability under node loss | Provision multiple FEs and add failure and recovery scenarios | The fixture has one FE and no failover orchestration |
| Rolling upgrades and version compatibility | Supply two package versions and test staged replacement with existing data | The workflow accepts one RC and starts a fresh cluster |
| Performance, load, endurance, and standalone fuzz campaigns | Add bounded workloads and explicit thresholds on suitable runners | No workload baselines or performance gates are implemented |
| Upstream ADMIT and inspection suites | Provide their external suite, configuration, and orchestration | This fork's workflow invokes the repository SQL-tester only |
| Production OS and deployment combinations | Add runs on the target OS or deployment images | The IT runtime is Ubuntu 24.04 even when the RC was built for CentOS or RHEL |

Vulnerability scanning belongs in a separate workflow and is outside the scope
of these UT and IT jobs

ARM64 IT is available through `platform: rhel9-arm64`, but requires a separate
dispatch from AMD64
Full-suite completion on hosted runners and ARM64 execution remain unverified
