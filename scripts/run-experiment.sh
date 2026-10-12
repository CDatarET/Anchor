#!/bin/sh
# Runs the repeatable performance experiment (all five scenarios) on loopback.
cd "$(dirname "$0")/.."
[ -d out/main ] || ./scripts/build.sh
exec java -cp out/main reliableudp.ExperimentRunner "$@"
