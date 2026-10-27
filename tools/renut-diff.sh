#!/usr/bin/env bash
# Patch do Renut a partir de um estado guardado: tools/renut-diff.sh <pasta com app/src/renut_engine antes> > patch.
# A pasta e uma copia de external/mgr-nx-deko/app/src/renut_engine depois dos patches anteriores.
set -euo pipefail
BASE="$1"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT/external/mgr-nx-deko"
diff -ruN "$BASE/app/src/renut_engine" app/src/renut_engine \
  | sed -E "s#^--- $BASE/(\S+).*#--- a/\1#; s#^\+\+\+ (\S+).*#+++ b/\1#; s#^diff -ruN $BASE/(\S+) (\S+)#diff --git a/\1 b/\2#" || true
