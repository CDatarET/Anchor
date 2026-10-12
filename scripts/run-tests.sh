#!/bin/sh
# Compiles and runs the JUnit 5 tests WITHOUT Maven.
# Needs a JDK (17+) and the JUnit console launcher jar:
#   export JUNIT_CONSOLE=/path/to/junit-platform-console-standalone-<version>.jar
# (download from https://github.com/junit-team/junit-framework/releases or Maven Central; on Debian/Ubuntu:
#  `sudo apt install junit5` provides /usr/share/java/junit-platform-console-standalone.jar)
set -e
cd "$(dirname "$0")/.."
JUNIT_CONSOLE="${JUNIT_CONSOLE:-/usr/share/java/junit-platform-console-standalone.jar}"
[ -f "$JUNIT_CONSOLE" ] || { echo "Set JUNIT_CONSOLE to the junit-platform-console-standalone jar"; exit 2; }
rm -rf out && mkdir -p out/main out/test
javac -d out/main $(find src/main/java -name '*.java')
javac -d out/test -cp "out/main:$JUNIT_CONSOLE" $(find src/test/java -name '*.java')
java -jar "$JUNIT_CONSOLE" execute -cp out/main:out/test --scan-classpath out/test --disable-banner --details=summary
