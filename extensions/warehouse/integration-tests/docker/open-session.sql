-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     https://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.

-- One session of user loader on one connection, run by the mysql client inside the FE container.
-- The "system" lines run a statement as root on a second connection, between two statements of this session,
-- so the REVOKE arrives while the session still holds the compute resource it acquired.
-- Every query scans the table. A count(*) does not reach a compute node and would prove nothing.
SET warehouse = 'research';
SELECT 'warm', sum(id) > 0 FROM poc.t;
PREPARE ps FROM 'SELECT sum(id) > 0 AS prepared FROM poc.t';
EXECUTE ps;
system mysql -h127.0.0.1 -P9030 -uroot -e "REVOKE USAGE ON WAREHOUSE research FROM USER loader"
SELECT 'after revoke', sum(id) > 0 FROM poc.t;
EXECUTE ps;
INSERT INTO poc.t VALUES (9105, 'after revoke');
system mysql -h127.0.0.1 -P9030 -uroot -e "GRANT USAGE ON WAREHOUSE research TO USER loader"
SELECT 'after grant', sum(id) > 0 FROM poc.t;
EXECUTE ps;
