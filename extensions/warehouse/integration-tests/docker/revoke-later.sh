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

# Runs inside the FE container: revoke-later.sh <seconds>
# Returns at once. That many seconds later it revokes USAGE on the research warehouse from user loader, as
# root on a connection of its own. A session script calls it right before a slow statement, so the REVOKE
# arrives while that statement runs.
(sleep "$1"; mysql -h127.0.0.1 -P9030 -uroot -e "REVOKE USAGE ON WAREHOUSE research FROM USER loader") >/dev/null 2>&1 &
