#!/bin/bash
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Builds the Docker cluster from scratch: MinIO, 1 FE with the extension jar, 2 compute nodes (one per
# warehouse). Needs SUITE_DIR, EXT_JAR and IMAGE in the environment.
set -e
D=$SUITE_DIR
IMG=$IMAGE
d() { timeout 120 docker "$@"; }
for c in whext-fe whext-cn-default whext-cn-research whext-minio; do d rm -f $c >/dev/null 2>&1 || true; done
d network inspect whext-net >/dev/null 2>&1 || d network create --subnet 172.30.86.0/24 whext-net >/dev/null
# Throwaway MinIO keys for this run only. They stay in this file and are never printed.
printf 'MINIO_ROOT_USER=whext%s\nMINIO_ROOT_PASSWORD=%s\n' "$(head -c6 /dev/urandom | od -An -tx1 | tr -d ' \n')" "$(head -c18 /dev/urandom | od -An -tx1 | tr -d ' \n')" > "$D/minio.env"
chmod 600 "$D/minio.env"
. "$D/minio.env"
d run -d --name whext-minio --network whext-net --ip 172.30.86.5 --memory 512m --env-file "$D/minio.env" \
  --tmpfs /data:rw,uid=65532,gid=65532,size=2g cgr.dev/chainguard/minio:latest server /data >/dev/null
sleep 3
d run --rm --network whext-net -e K="$MINIO_ROOT_USER" -e P="$MINIO_ROOT_PASSWORD" --entrypoint bash $IMG -c \
  'curl -s -o /dev/null -w "create bucket http=%{http_code}\n" --aws-sigv4 "aws:amz:us-east-1:s3" --user "$K:$P" -X PUT http://172.30.86.5:9000/whext'
# configs come from the image itself
d run --rm --entrypoint cat $IMG /data/deploy/starrocks/fe/conf/fe.conf > "$D/fe.conf"
d run --rm --entrypoint cat $IMG /data/deploy/starrocks/be/conf/cn.conf > "$D/cn.conf"
sed -i 's/-Xmx8192m/-Xmx2048m/; s/^enable_load_volume_from_conf.*/enable_load_volume_from_conf = false/' "$D/fe.conf"
printf '\n# --- integration test ---\npriority_networks = 172.30.86.0/24\nrun_mode = shared_data\ncloud_native_meta_port = 6090\nenable_warehouse_extension = true\n' >> "$D/fe.conf"
printf '\npriority_networks = 172.30.86.0/24\n' >> "$D/cn.conf"
d create --name whext-fe --hostname whext-fe --network whext-net --ip 172.30.86.10 --memory 3g --entrypoint bash $IMG -c 'exec /data/deploy/starrocks/fe/bin/start_fe.sh' >/dev/null
d cp -q "$D/fe.conf" whext-fe:/data/deploy/starrocks/fe/conf/fe.conf
d cp -q "$EXT_JAR" whext-fe:/data/deploy/starrocks/fe/lib/
i=21; for w in default research; do
  d create --name whext-cn-$w --hostname whext-cn-$w --network whext-net --ip 172.30.86.$i --memory 1800m --entrypoint bash $IMG -c 'exec /data/deploy/starrocks/be/bin/start_cn.sh' >/dev/null
  d cp -q "$D/cn.conf" whext-cn-$w:/data/deploy/starrocks/be/conf/cn.conf
  i=$((i+1))
done
d start whext-fe whext-cn-default whext-cn-research >/dev/null
for n in $(seq 1 60); do d exec whext-fe mysql -h127.0.0.1 -P9030 -uroot -e 'show frontends' >/dev/null 2>&1 && break; sleep 3; done
d exec whext-fe bash -c 'grep -h "warehouse_extension loaded" /data/deploy/starrocks/fe/log/fe.log | sed "s/.*warehouse_extension loaded/extension loaded/" | cut -c1-220'
# storage volume: the statement carries the throwaway MinIO keys, so it is not echoed
d exec -i whext-fe mysql -h127.0.0.1 -P9030 -uroot --force <<SQL
CREATE STORAGE VOLUME test_volume TYPE = S3 LOCATIONS = ("s3://whext/data/")
PROPERTIES ("enabled" = "true", "aws.s3.region" = "us-east-1", "aws.s3.endpoint" = "http://172.30.86.5:9000",
 "aws.s3.access_key" = "$MINIO_ROOT_USER", "aws.s3.secret_key" = "$MINIO_ROOT_PASSWORD",
 "aws.s3.use_instance_profile" = "false", "aws.s3.use_aws_sdk_default_behavior" = "false", "aws.s3.enable_path_style_access" = "true");
SET test_volume AS DEFAULT STORAGE VOLUME;
CREATE WAREHOUSE research COMMENT 'ad hoc research';
ALTER SYSTEM ADD COMPUTE NODE '172.30.86.21:9050';
ALTER SYSTEM ADD COMPUTE NODE '172.30.86.22:9050' INTO WAREHOUSE research;
SQL
for n in $(seq 1 40); do [ "$(d exec whext-fe mysql -h127.0.0.1 -P9030 -uroot -N -e 'show compute nodes' 2>/dev/null | grep -c 'true')" = 2 ] && break; sleep 3; done
d ps -a --filter name=whext- --format '{{.Names}}\t{{.Status}}'
