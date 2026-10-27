#!/usr/bin/env bash
# Prepara external/burstlimit: o Burst Limit Recompiled (iExplosiveRage) na versao
# fixada em tools/versions.env, com o SDK dele (submodulo thirdparty/rexglue-sdk) e
# so os submodulos de terceiros que o Android usa.
#
# Idempotente: com o external/burstlimit ja na versao certa, so confere os
# submodulos. Com outra versao, para e diz o que fazer (nunca apaga trabalho).
#
# Uso (a partir da raiz do repositorio):
#   bash tools/setup-external.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=versions.env
source "$ROOT/tools/versions.env"
BL="$ROOT/external/burstlimit"
SDK="$BL/thirdparty/rexglue-sdk"

mkdir -p "$ROOT/external"
if [ ! -d "$BL/.git" ]; then
  echo "== clonar o Burst Limit Recompiled"
  git clone "$BL_REPO" "$BL"
fi

HEAD="$(git -C "$BL" rev-parse HEAD)"
if [ "$HEAD" != "$BL_COMMIT" ]; then
  if [ -f "$BL/.dragonburst-patches" ] || [ -n "$(git -C "$BL" status --porcelain --untracked-files=no)" ]; then
    echo "ERRO: external/burstlimit esta em $HEAD (esperado $BL_COMMIT) e tem alteracoes." >&2
    echo "  Para recomecar do zero: rm -rf external/burstlimit && bash tools/setup-external.sh" >&2
    exit 1
  fi
  echo "== checkout $BL_COMMIT"
  git -C "$BL" fetch origin
  git -C "$BL" -c advice.detachedHead=false checkout "$BL_COMMIT"
fi

echo "== SDK (thirdparty/rexglue-sdk)"
if [ ! -d "$SDK/.git" ] && [ ! -f "$SDK/.git" ]; then
  git -C "$BL" submodule update --init thirdparty/rexglue-sdk
fi
# O BL v6+ aponta o submodulo para um SDK com o renderizador nativo de PC (D3D12); o
# Android fica na base do SDK unico (SDK_COMMIT), contra a qual os patches sao escritos.
SDK_HEAD="$(git -C "$SDK" rev-parse HEAD)"
if [ "$SDK_HEAD" != "$SDK_COMMIT" ]; then
  if [ -f "$SDK/.dragonburst-patches" ] || [ -n "$(git -C "$SDK" status --porcelain --untracked-files=no)" ]; then
    echo "ERRO: SDK em $SDK_HEAD (esperado $SDK_COMMIT) e tem alteracoes." >&2
    echo "  Para recomecar do zero: rm -rf external/burstlimit && bash tools/setup-external.sh" >&2
    exit 1
  fi
  echo "== SDK: checkout $SDK_COMMIT"
  git -C "$SDK" fetch origin
  git -C "$SDK" -c advice.detachedHead=false checkout "$SDK_COMMIT"
fi

# Submodulos do SDK que o build Android usa (moltenvk, tracy e catch2 ficam de fora:
# macOS, profiler e testes).
SUBMODULES=(
  libmspack glslang FFmpeg tomlplusplus simde xxHash spdlog fmt utfcpp
  vulkan-headers vulkan-memory-allocator imgui spirv-tools spirv-headers cli11
  o1heap sdl3 inja vulkan-loader
)
for M in "${SUBMODULES[@]}"; do
  if [ -e "$SDK/thirdparty/$M/.git" ]; then
    continue
  fi
  echo "   submodulo $M"
  git -C "$SDK" submodule update --init --recursive "thirdparty/$M"
done
# libjuice (ICE do online) vem dentro do SDK, sem submodulo.

echo
echo "external/burstlimit pronto. Proximo passo:"
echo "  bash tools/apply-patches.sh"
echo "  bash tools/codegen.sh /caminho/para/default.xex"
