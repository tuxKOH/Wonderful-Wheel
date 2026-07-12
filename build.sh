#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build/classes
find src/main/java -name '*.java' | sort > build/sources.txt
javac -encoding UTF-8 -d build/classes @build/sources.txt
jar --create --file build/wwheel.jar --main-class com.wheel.app.ui.Main -C build/classes .
echo "Built build/wwheel.jar"
echo "Run with: java -jar build/wwheel.jar"
