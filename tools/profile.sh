#!/usr/bin/env bash
# Mede onde o jogo gasta CPU, com o simpleperf do Android.
#
# Uso (com o jogo aberto no aparelho, a meio de uma luta):
#   bash tools/profile.sh            # 20 segundos de amostragem
#   bash tools/profile.sh 30         # 30 segundos
#
# Resultados em perf/:
#   threads.txt       percentagem de CPU por thread
#   main_thread.txt   funcoes mais pesadas da "Main XThread" (o jogo)
#   audio_worker.txt  funcoes mais pesadas do "Audio Worker"
#   gpu_commands.txt  funcoes mais pesadas da thread "GPU Commands"
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NDK="${NDK:-$HOME/Android/Sdk/ndk/28.2.13676358}"
HOSTPERF="$NDK/simpleperf/bin/linux/x86_64/simpleperf"
PKG="com.dfdx047.dragonburst"
DURATION="${1:-20}"
OUT="$ROOT/perf"

if [ ! -x "$HOSTPERF" ]; then
  echo "ERRO: simpleperf do NDK nao encontrado em $HOSTPERF" >&2
  exit 1
fi

PID="$(adb shell pidof "$PKG" | tr -d '\r')"
if [ -z "$PID" ]; then
  echo "ERRO: o jogo nao esta a correr no aparelho. Abre-o e entra numa luta." >&2
  exit 1
fi

# Bibliotecas com simbolos (antes de o Gradle as despir para o APK).
LIBMAIN="$(find "$ROOT/app/build/intermediates/cxx/Release" -name libgame.so -path '*arm64-v8a*' | head -1)"
if [ -z "$LIBMAIN" ]; then
  echo "ERRO: libgame.so com simbolos nao encontrada. Compila primeiro (./gradlew assembleRelease)." >&2
  exit 1
fi
SYMDIR="$(dirname "$LIBMAIN")"

mkdir -p "$OUT"
echo "A gravar $DURATION s do processo $PID..."
# cpu-clock (evento de software): muitos aparelhos bloqueiam os contadores de
# hardware (cpu-cycles) a apps que nao sao do sistema. --app usa o modo
# "profileable" do APK.
adb shell simpleperf record --app "$PKG" -e cpu-clock --duration "$DURATION" -f 1000 \
  -o /data/local/tmp/dbz3.perf.data
adb pull /data/local/tmp/dbz3.perf.data "$OUT/dbz3.perf.data" > /dev/null
adb shell rm -f /data/local/tmp/dbz3.perf.data

report() {
  "$HOSTPERF" report -i "$OUT/dbz3.perf.data" --symdir "$SYMDIR" "$@" 2>/dev/null
}

report --sort comm --percent-limit 0.5 > "$OUT/threads.txt"
report --comms "Main XThread (F" --sort dso,symbol --percent-limit 0.3 > "$OUT/main_thread.txt"
report --comms "Audio Worker (F" --sort dso,symbol --percent-limit 0.3 > "$OUT/audio_worker.txt"
report --comms "GPU Commands (F" --sort dso,symbol --percent-limit 0.3 > "$OUT/gpu_commands.txt"

echo "Pronto. Ficheiros em $OUT:"
ls -1 "$OUT"/*.txt
