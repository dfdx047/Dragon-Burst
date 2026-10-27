# Renut no Burst Limit: progresso

Renut = o renderizador nativo de Direct3D do mgr-nx-deko (reNut + plume, Vulkan), trazido do Zandatsu
(github.com/dfdx047/Zandatsu, o port do MGR). Escolhe-se no arranque com `swordman_renderer = renut` (padrao
`xenos`). Branch `renut/burst-limit`. Aparelho: Odin 2 Mini (Adreno 740).

## Como esta montado

| Peca | Onde |
|---|---|
| mgr-nx-deko no commit do Zandatsu (ba1a837) | `external/mgr-nx-deko` (`tools/setup-external.sh`, `tools/versions.env`) |
| Patches do Zandatsu, sem alteracoes | `patches/renut/0001-0026` |
| Layout do device e hooks do BL (XDK 6683) | `patches/renut/0100-burst-limit-layout-and-hooks.patch` |
| Aplicar / verificar | `tools/apply-renut-patches.sh [--check]` (o CMake verifica) |
| Gerar um patch do Renut | `tools/renut-diff.sh <copia do estado anterior>` |
| Cola (escolha, driver, captura, handshake, vblank, GPU perdida) | `app/src/main/cpp/renut/renut_glue.cpp` |
| Fases da app (OnPreSetup, OnFinalizePaths, ImGui, janela, saida) | `app/src/main/cpp/renut/db_renut_app.h` + `patches/game/0006-android-renut.patch` |
| Build | `cmake/renut.cmake` (`DB_RENUT`, ligado por padrao) |
| Shaders | `tools/renut-shaders.sh` -> `out/renut` (nunca no git nem no APK) |

Shaders: `XBOX360.NSH` do `LONG2DATA_US.CPK` (139 unicos) e o `default.xex` (14, dos quais 9 novos: os do arranque)
-> 148 entradas na cache. O XEX e XEX2 com cifra normal e compressao basica (`extract_bl_shaders.py`).

## Driver

O Renut precisa de `shaderInt64`, que o driver Qualcomm do Adreno 740 nao tem: com ele fica o Xenia (log
"o driver nao tem shaderInt64", `renut_support.txt` = no). Com o Turnip (Mesa 26.3, `files/driver/`) liga. Nota de
teste: um force-stop antes dos 30 s marca o driver como "fechou o jogo no arranque" (`driver_boot_pending`) e o
arranque seguinte usa o do sistema, isto e, o Xenia.

## Diferencas do BL em relacao ao MGR (resolvidas)

1. **Layout do device**: o do N&B ate 0x4158, a cauda propria do BL (`AVALIACAO_M1.md` §2.2). O snapshot de deltas
   (`queued.cpp`) tinha as palavras privadas do MGR (11008-12223); no BL sao 10880-11775 (39 chunks lidos, MGR 40).
2. **Criacao do device**: `Direct3D_CreateDevice` esta inline no jogo; o hook e o setup de hardware `0x820B7230`.
3. **CBlocker::Check (0x820B10E8)** ja tinha um hook do jogo (a espera com sleep do v6): passa a partilhado e o do
   jogo entrega ao do Renut quando ele esta ativo (`patches/game/0006`).
4. **GPU perdida**: o `gpu_reset.cpp` do Zandatsu e substituido pelo caminho do Dragon Burst (SDK 0188,
   `game_state_jni.cpp`).
5. **VBlank**: o BL anda ao ritmo do vblank. O handler do D3D (`820B5CF8`) chama o callback do jogo
   (`device+16544` = `820EAA20`), que acorda a thread do tique (`820E7870`); sem `GraphicsSystem` ninguem o chamava e
   o jogo parava no primeiro frame (vigia: `820E7920` em `SignalAndWait`). Agora uma XHostThread chama o handler a
   60 Hz (`swordman_renut_vblank`).
6. **Handshake da frame** (`822A2E50`, como o renderizador nativo do PC): timeout de 8 ms nas tres esperas
   (`swordman_renut_handshake_ms`) e os callbacks do InsertCallback corridos antes de bloquear.

## M1: compila e liga (2026-10-27, 20:46 Lisboa)

Log do Odin (`swordman_renderer = renut`, Turnip):

```
Renut: Turnip Adreno (TM) 740 (Vulkan 1.4), geometryShader sim
graphics: Renut (Direct3D nativo do mgr-nx-deko, plume/Vulkan), sem PM4 nem EDRAM
[renut] pipeline cache .../renut_pipelines.bin, key "renut|layout 7|spirv 53723 247578|entries 148"
Burst Limit XDK 6683: all 27 guest interface guards match
Guest D3D device (Burst Limit) set up at 0x40001980
Renut hooks installed (Burst Limit XDK 6683): device 820B7230 820AE868 820B5F90 820AE690 820AE430 820AD570
  820AD898 820ADAA0 820ADC38 820AEAF0 820B64C0 820AE790 820B10E8; draw 820A8A20 820A8E10 820A8538 820BF910
  820BB348 820A9C38; shader 820A27B8 820A24E8 820F9CF8 820A2C78; resource 820A9268; glue 822A2E50 + VBlank
Render thread started
Renut: VBlank do D3D a 60 Hz (600 vblanks, device 40001980)
[renut] 10.0 s: 477 game frames (47.66 fps), render thread busy 0.51 ms a frame, GPU 0.39 ms a frame; 1422 draws
```

Faltavam 2 shaders que nao estao no NSH nem no XEX (`vs 0DCC266CED2A159C`, `ps 5AAB5CA1B2C235F3`: o jogo monta-os em
tempo de execucao). O Renut captura-os em `files/shader_capture/`; copiados para `out/renut/captured/` o
`renut-shaders.sh` junta-os: 150 entradas.

## M2: imagem ate ao menu principal (2026-10-27, 21:10 Lisboa)

Os quads 2D (BeginVertices: logos, titulo, menus) desenham **sem declaracao de vertices** (`device+11820` = 0): o D3D
do XDK 6683 liga o VS a uma declaracao num draw anterior (`820B06F8` -> `820B05B8` / `820B0398`) e reescreve os vfetch
do shader na memoria do guest. Os vfetch do contentor estao por patchar (formato 0), por isso esses draws caiam em
`NoDeclaration` e nao escreviam nada (ecra preto). `patches/renut/0101`: o draw le as entradas dos vfetch ja
patchados do objeto do VS (os elementos do contentor dao o endereco de cada instrucao), como a GPU faria.

Logos (Atari, Bandai Namco, CRIWARE), titulo, dialogos do save e menu principal iguais ao Xenia. No Odin:

| Ecra | Xenia | Renut |
|---|---|---|
| Logos | 46-48 fps | 47-48 fps, GPU 0,5 ms |
| Titulo | 60 fps, GPU 2,2 ms (overlay) | 60 fps, GPU 1,5-1,75 ms |
| Menu principal | 60 fps | 60 fps, GPU 1,0-1,3 ms |

Os logos correm a ~48 fps nos dois (o jogo e assim nesses ecras).

## M3: luta e campanha (2026-10-27, 22:20 Lisboa)

O jogo corre no Renut: luta CPU x CPU (Goku x Krillin, Plains, 120 s) e campanha (Z Chronicles, "01. Saiyan Arrival",
cutscenes e luta, 180 s), sem travar nem perder frames. Medido no Odin, na mesma cena (fps do jogo e GPU por frame,
do log: `[renut] 10.0 s` / `dbz3: perf`):

| Cena | Xenia | Renut |
|---|---|---|
| Luta CPU x CPU, 120 s | 59,8 fps (min 59,3), GPU 12,5 ms (max 15,5) | 60,0 fps (min 59,7), GPU 4,5 ms (max 5,1) |
| Campanha + cutscene, 180 s | 59,9 fps (min 59,9), GPU 10,6 ms (max 11,5) | 60,0 fps (min 59,5), GPU 4,4 ms (max 5,0) |

### Bloqueios de imagem (por resolver)

1. **Cabelo e partes dos fatos sem cor** (cabelo do Goku/Raditz branco-cinzento com os tracos pretos, camisa do Krillin
   branca). Shader das personagens `ps 8E5E328AC8EA3309`: cor = `tex0` (slot 1, DXT5 1024) menos ou vezes a rampa
   (`texture1`, slot 0, DXT1 256x256, uma por personagem) lida em `(luz + tex0.a * c255.z, |ambientcolt.w|)`; o sinal de
   `ambientcolt.w` (c8) escolhe subtrair (cabelo preto = branco - rampa) ou multiplicar. O resultado e o da `tex0` crua.
   Tentado sem efeito: as heuristicas do Banjo desligadas (`resolve_skip`, `upload_cached`, `store_cards`, `cpu_memos`,
   `shared_dedupe`, `pipeline_memo`, `depth_words_lazy`, `alpha_to_mask_test`, `half_pixel_offset`, `device_swizzle`);
   os literais como imediatos (`BANJO_RENUT_LITERALS_CBUF=0`); os deltas do snapshot desligados
   (`sa2_renut_snapshot_deltas`). Visto no log: formatos usados 2/6/18/19/20, todos suportados; as duas texturas
   ligadas em cada draw (nenhuma nula). Hipotese: a rampa lida devolve ~0 (conteudo da DXT1 256x256: destiling ou
   cache) ou o c8 chega com outro valor; proximo passo: `show_texture` da rampa (DD93A0DC) e as constantes c0-c8 no
   drawlog.
2. **Fundo desfocado** (o Xenia mostra-o nitido). O pos-processamento tem motion blur por reprojecao da profundidade
   (`ps FE8B85F1`, `VelocityMatrix` em c0-c3) e a composicao (`1E001FAB`). A resolve de profundidade (DD92351C) tem a
   cena (vista com `show_texture`). Tentado: deltas desligados, sem efeito. Hipotese: a escala da profundidade
   (o jogo pode esperar outra codificacao) ou o alvo de velocidade (`40AFEA00`, formato 34) lido com outro formato.
