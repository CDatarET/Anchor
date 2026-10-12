#!/bin/sh
# Compile the project with plain javac (no Maven needed). Output: out/main
set -e
cd "$(dirname "$0")/.."
rm -rf out/main && mkdir -p out/main
javac -d out/main $(find src/main/java -name '*.java')
echo "Compiled to out/main"
