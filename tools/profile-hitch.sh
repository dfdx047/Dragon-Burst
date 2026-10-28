#!/usr/bin/env bash
# Grava os travoes (transformacao, ki, especial) com a pilha de chamadas, para
# saber QUEM chama o kernel e o que o jogo esta a fazer durante cada travao.
#
# Uso (com o jogo aberto, ja dentro de uma luta):
#   bash tools/profile-hitch.sh          # 30 segundos
#   bash tools/profile-hitch.sh 45       # 45 segundos
#
# Durante a gravacao: transformar, carregar ki e soltar especial no maximo.
# No fim gera perf/hitch.tar.gz (gravacao + tabelas de simbolos + logcat).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NDK="${NDK:-$HOME/Android/Sdk/ndk/28.2.13676358}"
LLVM_BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
PKG="com.dfdx047.dragonburst"
DURATION="${1:-30}"
OUT="$ROOT/perf/hitch"

if [ ! -x "$LLVM_BIN/llvm-nm" ]; then
  echo "ERRO: llvm-nm do NDK nao encontrado em $LLVM_BIN" >&2
  exit 1
fi

PID="$(adb shell pidof "$PKG" | tr -d '\r')"
if [ -z "$PID" ]; then
  echo "ERRO: o jogo nao esta rodando no aparelho. Abre e entra numa luta." >&2
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT"

echo "Gravando $DURATION s do processo $PID. Transforma, carrega ki e solta especiais agora!"
# --call-graph fp: pilha pelos frame pointers (o NDK compila com eles em arm64).
adb shell simpleperf record --app "$PKG" -e cpu-clock -f 2000 --call-graph fp \
  --duration "$DURATION" -o /data/local/tmp/dbz3_hitch.perf.data
adb pull /data/local/tmp/dbz3_hitch.perf.data "$OUT/hitch.perf.data" > /dev/null
adb shell rm -f /data/local/tmp/dbz3_hitch.perf.data
adb logcat -d -s dbz3 > "$OUT/logcat.txt"

# Tabelas de simbolos das bibliotecas com simbolos (antes de o Gradle as despir).
for LIB in libgame.so librexruntime.so librexgpu-xenos.so; do
  FILE="$(find "$ROOT/app/build/intermediates/cxx/Release" -name "$LIB" -path '*arm64-v8a*' | head -1)"
  if [ -z "$FILE" ]; then
    echo "AVISO: $LIB com simbolos nao encontrada" >&2
    continue
  fi
  "$LLVM_BIN/llvm-nm" -n --defined-only -C "$FILE" > "$OUT/$LIB.syms"
  "$LLVM_BIN/llvm-readelf" -lW "$FILE" > "$OUT/$LIB.phdr"
done

tar -czf "$ROOT/perf/hitch.tar.gz" -C "$ROOT/perf" hitch
echo "Pronto: $ROOT/perf/hitch.tar.gz"
ls -lh "$ROOT/perf/hitch.tar.gz"
