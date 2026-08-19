#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
CXX=${CXX:-c++}
"$CXX" -std=c++20 -O2 \
  -I"$ROOT/app/src/main/cpp" \
  "$ROOT/tools/native_core_selftest.cpp" \
  "$ROOT/app/src/main/cpp/WavReader.cpp" \
  -o /tmp/stagegrid-native-selftest
/tmp/stagegrid-native-selftest
