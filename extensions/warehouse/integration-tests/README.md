# Integration tests

These scripts run the Warehouse Extension for StarRocks in real FEs and check what the FEs do. One command starts the clusters, runs the checks and stops everything again.

They are not part of the Maven build. They need a built FE distribution and Linux namespaces, and one suite needs Docker.

## Run it

Build the FE first, as described under Build in the module's README. Then, from this directory:

```bash
./run.sh
```

That runs the three host suites. It takes the FE distribution from `output/fe` of this source tree, and the newest extension jar from the module's `target` directory or `output/fe/lib`. Other choices:

```bash
./run.sh drop-guards    # one suite
./run.sh docker         # the suite on the official image
./run.sh all            # all four suites
./run.sh --fe-dist /path/to/starrocks/fe --jar /path/to/starrocks-warehouse-ext.jar
```

`./run.sh --help` lists every option.

Each check prints one `PASS` or `FAIL` line. The run ends with a summary per suite. The exit status is 0 when every check passed, 1 when one failed, and 2 when the run could not start.

## Suites

| Suite | Cluster | Checks | Time |
|---|---|---|---|
| `lifecycle` | 3 FEs on this host | 80 | 6 minutes |
| `lost-metadata` | 3 FEs on this host | 31 | 4 minutes |
| `drop-guards` | 1 FE on this host | 30 | half a minute |
| `docker` | Official image: 1 FE, 2 compute nodes, MinIO | 64 | 4 minutes |

The times are from one run on one machine, with both Docker images already pulled.

`lifecycle` follows warehouses from `CREATE` to `DROP`. It covers the statements, restart from the journal and from the image, the settings, followers, a leader change, privileges, and removing the jar cleanly.

`lost-metadata` makes an FE without the jar write the checkpoint. It then checks the alarm and the repair.

`drop-guards` checks that nodes and resource groups stop `DROP WAREHOUSE`, and that the hints in the error messages work.

`docker` is the only suite with running compute nodes. It checks where queries, `INSERT` and Stream Load run, what happens when a node goes down, and the `USAGE` gate. It also compiles the extension and runs its unit tests inside the image, against the image's own jars.

## Requirements

Host suites:

- Linux with unprivileged user namespaces (`unshare -rn` must work). No root and no `sudo`.
- `unshare`, `nsenter`, `setsid`, `ip`, `curl`, `md5sum`, `python3`.
- A JDK 17 in `JAVA_HOME`. On Debian and Ubuntu the script finds it by itself.
- An FE distribution of the version the jar was built for. The scripts only read it. A copy of the extension jar inside it is ignored: the suites decide which FE gets the jar.

Docker suite:

- Docker. The memory limits of the containers add up to about 10 GB.
- Two images, pulled if they are missing: `starrocks/allin1-ubuntu:<FE version>` and `cgr.dev/chainguard/minio:latest`. The MinIO tag is not pinned.
- The extension jar. It does not need the FE distribution.
- For the build inside the image: the JUnit and JMockit jars in `~/.m2`. They are there after an FE build on the same machine. Without them that one step is skipped.

## What a run touches

- Host suites: every FE runs in its own network namespace on a private bridge, `10.86.0.0/24`. Nothing listens on the host, and nothing is reachable from the network. Files go to the work directory only.
- Docker suite: containers and a network named `whext-*`, on `172.30.86.0/24`. They are removed at the end. Containers left over under these names from an earlier run are removed at the start.
- The Docker suite creates throwaway MinIO keys for each run. They stay in a file with mode 600 in the work directory, are never printed, and are deleted at the end.
- Processes are stopped by the pids the suite recorded, never by name. This also happens after a failure or Ctrl-C.

## Results and logs

The work directory is printed at the start. It is a new directory under `/tmp` unless `--work` names one. Nothing is written into the source tree.

- `<suite>/suite.log` holds the output of the suite.
- When a host suite fails, its FE homes stay in `<suite>/fe1` to `fe3`, with `log/fe.log` and `log/fe.out`.
- When the Docker suite fails, the FE's `fe.log` is copied to `<suite>/fe.log` before the containers are removed.
- `--keep` keeps these files for suites that passed too.

## Limits

- The host suites start no compute nodes. Nodes are registered by address only, so nothing in them runs a query or a load.
- The suites wait with fixed sleeps in places. A slow machine can fail a check that would pass a few seconds later.
- When all FEs of a cluster start at the same time, one can win the leader election and lose it again a moment later. A stock FE exits in that case. `lifecycle` starts such an FE once more, as a process supervisor would, and prints an `INFO` line.
- The Docker suite uses fixed names and addresses, so run only one at a time.
- The cleanup after Ctrl-C has not been tried.
- The versions of the test libraries for the build inside the image are listed in `lib/docker.sh`. They must match the ones `fe/pom.xml` manages.
