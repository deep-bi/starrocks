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

# Check helpers shared by all suites. Source this file.
PASS=0; FAIL=0; SKIP=0

ok()      { PASS=$((PASS+1)); echo "PASS  $1"; }
bad()     { FAIL=$((FAIL+1)); echo "FAIL  $1"; [ -n "$2" ] && echo "      got: $(echo "$2" | head -6 | cut -c1-300)"; }
skip()    { SKIP=$((SKIP+1)); echo "SKIP  $1"; }
info()    { echo "INFO  $1"; }
section() { echo "=== $1"; }

# expect <name> <needle> <haystack>: passes when the haystack contains the needle
expect()  { if echo "$3" | grep -qF -- "$2"; then ok "$1"; else bad "$1 (wanted: $2)" "$3"; fi; }
# refute <name> <needle> <haystack>: passes when it does not
refute()  { if echo "$3" | grep -qF -- "$2"; then bad "$1 (did not want: $2)" "$3"; else ok "$1"; fi; }

# Prints the line run.sh reads. Returns non-zero when a check failed.
finish()  { echo "RESULT: $PASS passed, $FAIL failed, $SKIP skipped"; [ "$FAIL" -eq 0 ]; }
# abort <name> [detail]: records a failed check and ends the suite. The suite's EXIT trap cleans up.
abort()   { bad "$1" "$2"; finish; exit 1; }
