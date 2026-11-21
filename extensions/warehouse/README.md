# Warehouse Extension for StarRocks

An FE extension that makes `CREATE WAREHOUSE` work on shared-data StarRocks 4.1+. It changes no FE code. Open-source
StarRocks 4.1 ships one warehouse, `default_warehouse`. The SQL grammar, session variable, load properties and
privileges for warehouses are all there, but the default `WarehouseManager` rejects every warehouse DDL.

This extension replaces that manager. Each warehouse you create owns a StarMgr worker group. Compute nodes added with
`ALTER SYSTEM ADD COMPUTE NODE ... INTO WAREHOUSE` join that group, and a session that runs `SET warehouse = '<name>'`
schedules its queries only on those nodes.

**Status: experimental.** Not ready for production. Its settings and behaviour can change in any release.

## Usage

### Enable

A distribution built from this repository has the jar on every FE.

1. Set `enable_warehouse_extension = true` in `fe.conf` on every FE.
2. Restart the FEs. `fe.log` shows `warehouse_extension loaded`.

The extension loads on every FE, even without the setting. The setting only allows `CREATE WAREHOUSE`.

The jar also runs on an official StarRocks FE of the same version. Copy `starrocks-warehouse-ext.jar` into `fe/lib` on
every FE (`/opt/starrocks/fe/lib` in the official images), then follow the 2 steps above.

### Settings

All properties are defined in `fe.conf`. They do not appear in `ADMIN SHOW FRONTEND CONFIG`, because the extension reads
the file itself.

| Key                                      | Default | Meaning                                                                                                                             |
|------------------------------------------|---------|-------------------------------------------------------------------------------------------------------------------------------------|
| `enable_warehouse_extension`             | `false` | Allows `CREATE WAREHOUSE`. `false` does not switch existing warehouses off: they keep working and can still be altered and dropped. |
| `warehouse_extension_skip_version_check` | `false` | Starts the FE even if the jar was built for another FE version.                                                                     |
| `warehouse_extension_check_usage`        | `true`  | Checks `USAGE` on a user warehouse for every query and load of a user session. See Access control.                                  |

A value other than `true` or `false` stops the FE at start.

### Create and use a warehouse

```sql
CREATE WAREHOUSE research COMMENT 'ad hoc research';
ALTER SYSTEM ADD COMPUTE NODE "cn-research-0:9050" INTO WAREHOUSE research;
SET warehouse = 'research';
SHOW NODES FROM WAREHOUSE research;
```

Compute nodes need no change. Only the FE loads the jar.

### Warehouse properties

Set with `CREATE WAREHOUSE ... PROPERTIES (...)` and `ALTER WAREHOUSE ... SET (...)`. An unknown property is an error.

| Property         | Default | Meaning                                                             |
|------------------|---------|---------------------------------------------------------------------|
| `replica_number` | `1`     | Number of compute nodes of the warehouse each tablet is assigned to |

With `replica_number` above `1`, queries on a tablet are spread over that many nodes, and the tablet uses cache space on
each of them. Do not set it higher than the number of nodes in the warehouse.

### Access control

A user needs `USAGE` on a warehouse to run on it: `GRANT USAGE ON WAREHOUSE research TO USER analyst`. Every user may
run on `default_warehouse` without a grant, as in a StarRocks cluster without the extension.

`USAGE` is checked for every query, `INSERT` and Stream Load on a warehouse other than `default_warehouse`,
whichever way the warehouse was chosen:

- `SET warehouse = '<name>'`
- the `SET_VAR` hint, on a query or on `INSERT`
- the `warehouse` header of a Stream Load
- the default warehouse set on the user (`session.warehouse`)

Without the privilege, a statement fails with the access-denied error 5203. A Stream Load fails with `Access denied` in
its response.

`REVOKE` takes effect at the next statement, also on a connection that is already open. That session stays on the
warehouse it selected, so its statements keep failing until the privilege is back or it moves to a warehouse it may
use. `SET warehouse = 'default_warehouse'` needs `USAGE` on `default_warehouse` like any other `SET warehouse`. A user
without that grant reconnects instead: a new connection starts on `default_warehouse`, unless another default is set
on the user.

With `warehouse_extension_check_usage = false`, only `SET warehouse` and the hint on a query are checked.

### Drop a warehouse

`DROP WAREHOUSE` refuses a warehouse that is still in use, either by compute nodes or resource groups.

In case of compute nodes, remove each one first with:

```sql
ALTER SYSTEM DROP COMPUTE NODE '<host>:<heartbeat port>' FROM WAREHOUSE <name>;
```

A resource group can be limited to warehouses with its `warehouses` property. In such a case, take the warehouse out of
the property, or drop the resource group:

```sql
ALTER RESOURCE GROUP rg_reports WITH ('warehouses' = 'etl');
```

An empty value lifts the limit, and the group then applies on every warehouse.

### Remove the extension

To remove the jar, drop every warehouse first, wait for a checkpoint (`ALTER SYSTEM CREATE IMAGE` forces one), then
remove the jar from all FEs.

## Operations

### Monitoring

The extension watches for lost warehouse metadata. Once an FE has loaded its metadata, and then every minute, it looks
for nodes whose warehouse it does not know. It reports on that FE in 2 places:

- A gauge on the FE's metrics endpoint, `starrocks_fe_warehouse_extension_orphaned_nodes`: the number of nodes whose
  warehouse this FE does not know. It is 0 on a healthy FE. Alert on a value above 0. Each FE reports its own value, so
  watch every FE, not only the leader.
- An ERROR line in `fe.log` that names the nodes, repeated every 10 minutes while the condition lasts. When it ends,
  the log says `Every node belongs to a known warehouse again`.

A lost warehouse that had no nodes is not detected.

### Limits

- Shared-data mode only.
- Once warehouses exist, every FE  must have the jar. If the extension jar would be missing, FE skips the
  warehouse entries in the image and the journal, and shows only `default_warehouse`. If the jar is put again before the
  next checkpoint, FE will restore them. However, if an FE without the jar writes a checkpoint, the warehouse
  information in the new image will be lost. In such a case, drop the affected nodes with
  `ALTER SYSTEM DROP COMPUTE NODE`, create the warehouses again and add the nodes back.
- Background work stays on `default_warehouse`: compaction, vacuum, automatic statistics. Keep compute nodes in it.
- `SUSPEND WAREHOUSE`, `RESUME WAREHOUSE` and CN groups are not implemented.
- The Kubernetes operator's `StarRocksWarehouse` resource needs the FE to report a `multi-warehouse` feature. The FE
  does not, so register compute nodes by SQL.

## How it works

### How it loads

1. At start the FE scans `fe/lib` for files whose name ends in `ext.jar`.
2. It calls `onLoad` on every class annotated `@SRModule`. Here that is `WarehouseExtension`.
3. `onLoad` reads its settings from `fe.conf`, checks the FE version, registers the Gson subtype for persisted
   warehouses, and registers `UserWarehouseManager` as the FE's `WarehouseManager`.
4. If any of that fails, the extension stops the FE. Without this, the FE would only log a warning and start with its
   default manager, which ignores persisted warehouses.

On a shared-nothing FE the extension does nothing. It also refuses to load when another warehouse manager is already
active, for example one from a different extension.

### Stored metadata

Warehouses are stored in the FE image and edit log under the Gson label `bi.deep.UserWarehouse`, with a format version.
The label never changes. A jar that meets a format newer than it knows stops the FE.

With no warehouses left, the extension writes nothing to the image, so the image is the same as in case of FEs without
the extension.

## Development

### Build

The extension is a Maven module of the FE build. Its version is the FE version, and the jar loads only into that FE
version.

```bash
./build.sh --fe
```

This builds `extensions/warehouse/target/starrocks-warehouse-ext.jar` and copies it into `output/fe/lib`.

Or in Docker, from the repository root (use the tag of the FE version):

```bash
docker run --rm -v "$PWD:$PWD" -v "$HOME/.m2:/root/.m2" -w "$PWD" starrocks/dev-env-ubuntu:4.1.7 \
    bash -c "git config --global --add safe.directory '*' && ./build.sh --fe"
```

### Tests

`build.sh` skips tests. To run the extension's unit tests in the FE build:

```bash
cd fe
mvn verify -pl ../extensions/warehouse -am -DskipTests -Dwarehouse.ext.skipTests=false
```

Or, using docker, in the root directory:

```bash
docker run --rm -v "$PWD:$PWD" -v "$HOME/.m2:/root/.m2" -w "$PWD/fe" starrocks/dev-env-ubuntu:4.1.7 \
    bash -c "git config --global --add safe.directory '*' && mvn verify -pl ../extensions/warehouse -am \
    -DskipTests -Dwarehouse.ext.skipTests=false"
```

`-DskipTests` skips the tests of the FE modules that are built first. The second property turns the extension's tests
back on. The run fails if the tests leave any line or branch of the extension uncovered. The coverage report is in
`extensions/warehouse/target/site/jacoco`.

`run-fe-ut.sh` runs these tests together with the FE's own. It always passes a test filter, and a filtered run cannot
reach full coverage, so the coverage check is off there.

The sources are checked with the FE's `checkstyle.xml`, except its file header rule.

### Integration tests

The scripts in `integration-tests` run the extension in real FEs: 4 suites on the local host and one on the official
image with compute nodes. They are not part of the Maven build. After `./build.sh --fe`, on the host and not inside the
build image:

```bash
extensions/warehouse/integration-tests/run.sh
```

See `integration-tests/README.md` for the suites, the requirements and what a run touches.

### Moving to a new FE version

3 lines outside this directory hook the module in: the `<module>` entry in `fe/pom.xml`, and the `FE_MODULES` entry
and one `cp` line in `build.sh`. On a new version branch, carry over these lines and this directory, and set the parent
version in `pom.xml` to the new FE version.

The extension calls FE classes that are not a public API. After an upstream change to them, the FE build will stop at
this module or its tests will fail.
