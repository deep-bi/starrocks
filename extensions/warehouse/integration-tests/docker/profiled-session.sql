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

-- One session of user loader on one connection, with query profiles on.
-- revoke-later.sh revokes USAGE three seconds after it is called. sleep(8) keeps the statement that follows
-- running for eight seconds, so the REVOKE arrives while that statement runs.
-- With a profile on, the FE asks for the session's compute resource once more after the statement has its
-- result. That question must not turn a committed INSERT, or a SELECT that has sent its rows, into an error.
SET warehouse = 'research';
SET enable_profile = true;
SELECT 'warm', sum(id) > 0 FROM poc.t;
system bash /tmp/revoke-later.sh 3
INSERT INTO poc.t SELECT 9106, IF(sleep(8), 'revoke while running', 'x');
SELECT 'after the insert', sum(id) > 0 FROM poc.t;
system mysql -h127.0.0.1 -P9030 -uroot -e "GRANT USAGE ON WAREHOUSE research TO USER loader"
system bash /tmp/revoke-later.sh 3
SELECT 'slow select', sleep(8), sum(id) > 0 FROM poc.t;
SELECT 'after the select', sum(id) > 0 FROM poc.t;
