#!/usr/bin/env bash
# Compila as classes de protocolo do app e roda o teste de fumaça contra o projetor.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p ../build/phone-test
javac -encoding UTF-8 -d ../build/phone-test src/com/paulo/hyremote/AdbKey.java src/com/paulo/hyremote/AdbConnection.java \
    src/com/paulo/hyremote/Controller.java test/com/paulo/hyremote/AdbSmoke.java
java -cp ../build/phone-test com.paulo.hyremote.AdbSmoke "${1:-192.168.0.15:5555}"
