#!/usr/bin/env bash
# Offline plugin build for the Windows dev box: no network, reuses the fork's dependency cache.
# Prefer `gradle jar` (build.gradle.kts) when you have network — this is the fallback.
# Requires: the fork already built (victus-api jar present) + the JDK 25 Gradle provisioned.
set -euo pipefail
cd "$(dirname "$0")"

J25=/e/victus-tmp/gradle-home/jdks/eclipse_adoptium-25-amd64-windows.2/bin
API=../victus-api/build/libs/victus-api-26.2.local-SNAPSHOT.jar

# classpath = victus-api + every dep jar in the gradle module cache, as forward-slash Windows paths
# (cygpath -m; NOT -w — javac argfiles treat backslashes as escapes)
CP=$(cygpath -m "$API")
while IFS= read -r j; do CP="$CP;$(cygpath -m "$j")"; done \
  < <(find /e/victus-tmp/gradle-home/caches/modules-2 -name '*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar')
printf -- '-cp "%s"\n' "$CP" > cp.args

rm -rf out build && mkdir -p out build
"$J25/javac" @cp.args -encoding UTF-8 -d out \
  $(find src/main/java -name '*.java') \
  $(find ../victus-core/src/main/java -name '*.java' ! -name '*SelfTest.java')
cp src/main/resources/plugin.yml out/plugin.yml
(cd out && "$J25/jar" cf ../build/VictusEngine-0.1.0.jar .)
rm -f cp.args
echo "built: build/VictusEngine-0.1.0.jar"
