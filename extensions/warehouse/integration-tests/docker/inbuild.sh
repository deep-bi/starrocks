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

# Runs inside a container of the official StarRocks image: compiles the extension and its tests against
# that image's own fe/lib with the image's JDK, then runs every test class in its own JVM, as the Maven
# build does.
set -e
set -o pipefail
cd /work
FELIB=/data/deploy/starrocks/fe/lib
JAVA=$JAVA_HOME/bin
rm -rf out && mkdir -p out/main out/test out/pom

(cd out/pom && $JAVA/jar xf $FELIB/fe-core-*.jar META-INF/maven/com.starrocks/fe-core/pom.properties)
FEVER=$(grep '^version=' out/pom/META-INF/maven/com.starrocks/fe-core/pom.properties | cut -d= -f2)
echo "fe-core version from the image jar: $FEVER"
echo "javac: $($JAVA/javac -version 2>&1)"

mkdir -p out/main/bi/deep/starrocks/extension/warehouse
# Maven fills this file in from the project version. Here it is written by hand.
printf 'fe.version=%s\n' "$FEVER" > out/main/bi/deep/starrocks/extension/warehouse/build-info.properties

find src/main/java -name '*.java' > out/main.list
$JAVA/javac -nowarn -cp "$FELIB/*" -d out/main @out/main.list
echo "main classes compiled: $(find out/main -name '*.class' | wc -l)"

find src/test/java -name '*.java' > out/test.list
ls RunTests.java >> out/test.list
$JAVA/javac -nowarn -cp "out/main:$FELIB/*:testlibs/*" -d out/test @out/test.list
echo "test classes compiled: $(find out/test -name '*.class' | wc -l)"

rc=0
for src in src/test/java/bi/deep/starrocks/extension/warehouse/*Test.java; do
  cls=bi.deep.starrocks.extension.warehouse.$(basename "$src" .java)
  $JAVA/java -Xmx2g -javaagent:testlibs/jmockit-1.49.4.jar -cp "out/test:out/main:$FELIB/*:testlibs/*" RunTests "$cls" 2>/dev/null | grep -E '^(RESULT|FAIL)' || rc=1
done
exit $rc
