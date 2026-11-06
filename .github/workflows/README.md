# DeepBI workflows

## Active workflows

| Workflow | Trigger | Runner |
| --- | --- | --- |
| `deepbi-fe-ut.yml` | PRs and manual | GitHub-hosted Ubuntu 24.04 |
| `deepbi-be-ut.yml` | PRs and manual, includes C++ formatting | GitHub-hosted Ubuntu 24.04 |
| `deepbi-sql-it.yml` | Manual | GitHub-hosted Ubuntu 24.04, AMD64 or ARM64 |
| `deepbi-ci-checks.yml` | Called by FE UT, BE UT, and SQL IT, also manual | GitHub-hosted Ubuntu 24.04 |
| `ci-doc-checker.yml` | PRs to `main` and `branch*` | GitHub-hosted Ubuntu |
| `ci-vale.yml` | Documentation PRs to `main` and `branch*` | GitHub-hosted Ubuntu |

There are no scheduled workflows or self-hosted runner jobs in this directory

Upstream runner, review, backport, reporting, merge, and maintenance workflows
have been removed, with their definitions retained in Git history

Apply the cleanup to the default branch and other maintained branches whose
workflow definitions can still run
Delete any additional obsolete workflows that exist only on those branches
Local file changes do not cancel existing runs or delete run history

## Unit tests

Select **DeepBI FE Unit Tests** or **DeepBI BE Unit Tests**, then **Run workflow**
and the branch to test
FE UT and BE UT also run when a pull request is opened, reopened, or updated with new commits

GitHub requires the workflow definition on the default branch for the manual
trigger to appear in the UI
Keep the definitions on the release branches where they will run as well

| Suite | Build and execution |
| --- | --- |
| FE | Java 17, checksum-verified Thrift 0.24.0, `./run-fe-ut.sh -j 2` |
| Java extensions | `./run-java-exts-ut.sh` after FE UT, including when FE tests fail |
| BE | Pinned StarRocks 4.1.6 development image, Debug build, two build workers, shared-data and S3 tests enabled |

Both Maven reactors run their registered unit tests and continue independent
modules after a failure
BE generates C++ sources and runs this command:

```bash
./run-be-ut.sh -j 2 --enable-shared-data --with-aws --keep-going --without-debug-symbol-split
```

A pinned RustFS container supplies the BE S3 fixture with disposable credentials
and a port bound to loopback
The hosted runner's AWS CLI creates its bucket
Java extensions are packaged for JNI and Hadoop tests
All normal BE test binaries are attempted even after an earlier failure
Any test failure fails the workflow

The workflows add no test-name blacklist, changed-file filter, or slow-test
exclusion
Existing exclusions and their enablement steps are listed under
[Test exclusions](#test-exclusions)

Report validation rejects missing, empty, malformed, or failed reports and runs
where no tests executed
FE, Java extensions, and BE are checked separately
JSON summaries list framework skips and GoogleTest-disabled tests
The report checks do not independently reconstruct the build system's test inventory

Update the BE image when native dependencies change
Both UT workflows reject a Thrift version mismatch before testing
Update the FE compiler version and checksum together when changing `fe/pom.xml`

## C++ formatting

The `cpp-format` job belongs to `deepbi-be-ut.yml` and runs on PRs and manual dispatch
It uses clang-format 11.1.0 and `build-support/check-format.sh` to check `be/src`
and `be/test`

The repository's `.clang-format` and `build-support/excludes` define the scope
The check covers the whole configured tree, including existing violations, and
prints the required diff without modifying source files
BE UT still runs if formatting fails, and either failure fails the workflow

## SQL integration tests against an RC

Select **DeepBI SQL Integration Tests**, choose the branch containing the RC's
test cases, and enter the RC version, such as `4.1.6-deepbi2`

| Input | Choices or default | Effect |
| --- | --- | --- |
| `platform` | `centos-amd64` or `rhel9-arm64`, default `centos-amd64` | Select the package and matching native runner architecture |
| `cluster_mode` | `shared_nothing` or `shared_data`, default `shared_nothing` | Select one cluster mode per dispatch |
| `exclude_components` | `aws,oss,hdfs,hive,kafka,trino-client,spark-client,hive-client` | Exclude cases requiring these services |
| `exclude_datasets` | `ssb,tpch,tpcds` | Exclude cases requiring these datasets |

The package platform describes where the RC was built
Both architectures execute the RC in an Ubuntu 24.04 and Java 17 runtime
The workflow downloads anonymously from these URLs:

```text
https://packages.deep.bi/StarRocks/release-candidates/<version>/StarRocks-<version>-<platform>.tar.gz
https://packages.deep.bi/StarRocks/release-candidates/<version>/StarRocks-<version>-<platform>.sha256
```

SHA-256 is verified before extraction or execution, followed by a backend ELF
architecture check
The workflow does not build, promote, or publish the RC

| Mode | Disposable cluster | Startup probe |
| --- | --- | --- |
| `shared_nothing` | One FE and three BEs | Create a table with three replicas, insert data, and query it |
| `shared_data` | One FE, three CNs, and RustFS | Create a table on `builtin_storage_volume`, write to RustFS, and query it |

Both modes provision MySQL 8.4 for the MySQL and JDBC cases
Add `mysql` to `exclude_components` to omit that fixture and its cases
No real cloud credentials are needed for the default fixtures

The workflow invokes the existing `test/run.py` suite under `test/sql`, without
a case-name filter
Its cases cover SQL behavior, HTTP Stream Load, Arrow Flight SQL, MySQL/JDBC,
transactions, schema changes, materialized views, indexes, and compaction
Shared-data cases also exercise object storage through RustFS
Arrow ports, Java 17 direct-buffer access, and small JIT caches are configured

Each mode runs concurrent cases with two workers, then sequential cases with
one worker, retaining the project's three successful repetitions per case
A failed concurrent pass still permits the sequential pass
Storage-volume cases that change global settings run sequentially and restore
those settings in cleanup, including after a failed assertion

Missing dependencies outside the explicit exclusions fail collection
Unknown exclusion names also fail
An empty exclusion list requests all dependencies
Unexpected runtime skips and missing, empty, incomplete, or failed SQL reports
fail the job

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
| BE HDFS tests when Hadoop libraries are absent | Restore the Java-extension Hadoop package at the path expected by `run-be-ut.sh` | This is a launcher fallback, while the workflow normally builds the required Java extensions |
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

## CI validation

FE UT, BE UT, and SQL IT first call `deepbi-ci-checks.yml`
It validates active workflow YAML with checksum-verified actionlint, checks each
CI shell helper with `bash -n`, and runs the focused Python tests under `.github/scripts`
A failed CI check prevents the test jobs from starting

The helpers cover archive validation, cluster configuration, test selection,
failure propagation, and report validation
They do not replace the product's UT or IT suites

## Resources and reports

FE UT, BE UT including formatting, and SQL IT share the `deepbi-tests`
concurrency group across branches
Only one of those workflows executes at a time
With `queue: max`, up to 100 runs can wait without replacing each other
Additional runs are cancelled if the queue is full
See [GitHub concurrency queues](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax#example-queueing-multiple-pending-runs)

UT jobs have six-hour limits and builds use two workers
SQL IT has a six-hour job limit and up to 150 minutes per SQL pass

Each StarRocks IT container is limited to 3 GiB, including a 2 GiB FE heap
MySQL and shared-data RustFS each have a 1 GiB limit
With MySQL enabled, container limits total 13 GiB for shared-nothing and 14 GiB
for shared-data, plus memory needed by the host and SQL-tester
These settings do not establish that every selected case fits a hosted runner
Timeouts and resource failures fail the run

The workflow checks extraction space, omits debug symbols, and removes the
compressed archive after verification and extraction
BE and CN package copies share files through hard links to reduce disk use
Unused preinstalled SDKs are removed before the BE build or SQL IT setup

FE and BE share a Maven dependency cache, and FE also caches its Thrift compiler
BE outputs, RC packages, container images, and database storage are not cached
or uploaded

Test reports and logs are uploaded after failures and retained for three days
UT artifacts include XML reports, skip summaries, and the BE S3 service log
SQL IT artifacts include selection manifests, dependency exclusions, JUnit
results, the RC checksum, test source commit, container states, and bounded logs
Build and test console output remains in the Actions job logs
