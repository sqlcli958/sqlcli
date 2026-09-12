#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_DIR"

mvn -q -P\!web -DskipTests package

java -jar target/sql-cli.jar --help >/dev/null
java -jar target/sql-cli.jar --version >/dev/null
java -jar target/sql-cli-lite.jar --help >/dev/null
java -jar target/sql-cli-lite.jar --version >/dev/null

echo "CLI distribution smoke tests passed"
