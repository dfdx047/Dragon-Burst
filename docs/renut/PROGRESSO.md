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
   cache) ou o c8 chega com outro valor.
   Depois do commit do M3 (22:20-22:35): a rampa (DD93A0DC, `show_texture`) e uma faixa horizontal de cinzentos, igual
   em todas as linhas; a textura de cor (512x512 DXT5, DD939A9C) e em blocos lisos, com o cabelo branco. Logo a cor do
   cabelo sai da coluna da rampa, que o alfa da `tex0` (`tex0.a * c255.z`) empurra para a faixa escura: no Renut o
   cabelo fica na faixa clara. Fetch constants todas normais (swizzle identidade nos DXT, BGRA nos 8888, exponent
   adjust 0, `[drawlog]` slot lines). Proximo passo: o alfa da DXT5 do cabelo (mostrar so o canal A) e o termo de luz
   `r0.x` (`lightpos.w`, `rim_param`, `WorldViewMatrix`) com as constantes no drawlog.
2. **Fundo desfocado** (o Xenia mostra-o nitido). O pos-processamento tem motion blur por reprojecao da profundidade
   (`ps FE8B85F1`, `VelocityMatrix` em c0-c3) e a composicao (`1E001FAB`). A resolve de profundidade (DD92351C) tem a
   cena (vista com `show_texture`). Tentado: deltas desligados, sem efeito. O alvo de velocidade resolvido (DD9238DC):
   o ceu da ~(0,47, 0,5), velocidade zero como deve; o chao, com a camara parada, ~(0,55-0,6) e cresce para perto.
   Hipotese: a reprojecao da profundidade (o jogo le-a como `k_32_FLOAT`, formato 36, num 1, sinais 0x55) espera outra
   codificacao ou outro intervalo do que o alvo float do Renut lhe da (`banjo_gpu_depth_words_view`: 0 binds).

## F1: imagem (2026-10-28, 01:40-03:00 Lisboa)

**Fundo desfocado: resolvido** (`patches/renut/0104`). Nao era a reprojecao: o Renut desenhava a profundidade de
campo, o glow, o motion blur e o soft filter que o caminho emulado salta (`burstlimit_patches.cpp`, cvars
`depth_of_field`, `glow_blur`, `motion_blur`, `soft_filter`, todas desligadas por padrao, pelas draw overrides do SDK
chaveadas pelo hash da ucode do PS). Os shaders do Renut levam agora esse mesmo hash (`HashShaderUcode`, XXH3 da
microcode como o Xenia), e o draw do Renut consulta as mesmas overrides: salta os draws ou troca constantes do PS.
Fundo nitido como no Xenia. Odin (Turnip): luta 60,0 fps (min 60,0), GPU 3,3 ms; campanha 180 s 60,0 fps (min 59,8),
GPU 3,8 ms. Cube (Mali-G57, driver do sistema): luta 37,7 fps (min 33,6), GPU 25,5 ms; campanha 37,9 fps, GPU 25,8 ms.

**c255: nao era.** Upload do PS no Renut: 256 registos (4096 bytes); c255 vem pelos literais (cbuf), device c255 = 0.
Toon `8E5E328A` (Xenia `CF0D1D92`): c255 = (0,5, -1, 0,67, 1), c254 = (0, -2, 0,165, 0); profundidade linear
`E2223552` (Xenia `D9872B5A`): c255 = (10000, 9999, 0, 0). Iguais ao registo `SHADER_CONSTANT_256 + 255*4` do Xenia;
c0-c8 tambem (c8 = (1, 1, 1, 0,5): modo subtrair).

**Cabelo sem cor: por resolver (5 tentativas, parado).** Iguais no Renut e no Xenia, draw a draw: os pares
(rampa no slot 0, cor no slot 1) por numero de indices, o conteudo das rampas DXT1 256x256 e o alfa das DXT5 (primeiros
blocos), as constantes. O HLSL do toon esta certo (subtrair = cor - rampa; forcar multiplicar estraga os fatos, que
no Renut ja estao certos). Com o PS reescrito (`BANJO_HLSL_OVERRIDE`) a mostrar a amostra da rampa, o cabelo le uma
faixa cinzenta. Pista nova: na primeira foto da campanha (transicao desfocada) o cabelo do Goku e do Raditz e a
armadura estao certos (preto, castanho) no Odin e no Cube; 15 s depois, cinzentos. Proximo passo: o que muda entre essa
frame e as seguintes (rampa reescrita pelo jogo em memoria depois do primeiro upload, sem o Renut a reler?).

## F2: Renut no Mali (RG Cube, Mali-G57, 2026-10-28)

O Renut ja corria no Cube com o driver do sistema; o que o Zandatsu faz pela Mali ja esta no renderer (mesma camada):
texturas BC descomprimidas por compute (`BC textures: decoded on the GPU (compute) (device BC formats: no,
swordman_renut_bc_decode = auto)`), descriptor sets compactos (`maxBoundDescriptorSets 7` -> `compact descriptor sets
(7 bound at most)`) e o aviso sem `vertexPipelineStoresAndAtomics` (so log; o BL nao usa memexport). A verificacao do
driver (`renut_glue.cpp`) ja e pelo que o driver tem, nao pela marca. Nada do que o Renut exige falta a Mali-G57.
Novo: o launcher mostra o motivo no ecra Drivers quando o Renut nao arrancou ("Na ultima tentativa do Renut, o driver
nao tinha o que ele precisa (shaderInt64) e o jogo usou o Xenia"), lido do `renut_support.txt` que o jogo escreve.
No Odin com o driver Qualcomm do sistema: `Renut: o driver nao tem shaderInt64; a usar o Xenia`.

Cube, mesma cena (luta Goku x Krillin 120 s, campanha "Saiyan Arrival" 180 s com cutscenes), log do jogo:

| Cena | Xenia | Renut |
|---|---|---|
| Luta | 31,7 fps (min 27,1), GPU 31,2 ms, pior frame 150 ms | 37,7 fps (min 33,6), GPU 25,5 ms |
| Campanha | 33,6 fps (min 27,4), GPU 29,5 ms, pior frame 150 ms | 37,9 fps (min 33,6), GPU 25,8 ms |

Titulo, menu, luta, campanha com cutscene: sem travar nem `device lost`. Mesmo defeito do cabelo que no Odin.

## F3: matriz de testes (2026-10-28, 03:00-04:00 Lisboa)

Luta CPU x CPU Goku x Krillin (Plains) 120 s e campanha "Saiyan Arrival" 180 s (duas cutscenes), medidas do log do
jogo: fps de jogo (media e minimo das janelas de 5/10 s), GPU ms por frame, e o tempo de CPU que o log tem (Renut:
render thread por frame; Xenia: `cp_wait` por frame). O CPU ms da thread principal so aparece no overlay (fotos).

| Aparelho, caminho, driver | Luta: fps med / min, GPU ms, CPU ms | Campanha: fps med / min, GPU ms |
|---|---|---|
| Odin, Renut, Qualcomm (sistema) | nao arranca: `Renut: o driver nao tem shaderInt64; a usar o Xenia` (fica o Xenia; aviso no ecra Drivers) | - |
| Odin, Renut, Turnip 26.3 | 60,0 / 60,0, GPU 3,3, render thread 1,1 | 60,0 / 59,8, GPU 3,8 |
| Odin, Xenia, Qualcomm | 34,3 / 29,8, GPU 25,7, cp_wait 9,4, pior frame 66 ms | 34,3 / 29,5, GPU 25,6, pior 84 ms |
| Odin, Xenia, Turnip 26.3 | 38,8 / 30,5, GPU 21,9, cp_wait 10,2, pior 50 ms | 35,6 / 30,4, GPU 24,0, pior 50 ms |
| Cube, Renut, Mali (sistema) | 37,7 / 33,6, GPU 25,5, render thread 3,6 | 37,9 / 33,6, GPU 25,8 |
| Cube, Xenia, Mali | 31,7 / 27,1, GPU 31,2, cp_wait 9,3, pior 150 ms | 33,6 / 27,4, GPU 29,5, pior 150 ms |

O Odin esteve sempre quente (CPU 93-95 graus no overlay, no carregador): o Xenia mediu 34-39 fps, abaixo dos 59,8 do
M3. **Perfil 480+: bloqueado.** O `PServerBinder` existe (`service check`: found) mas o hlprofile recebe
`DeadObjectException: Transaction failed on small parcel`; sem root nao ha como limitar CPU/GPU, e reiniciar perderia o
adb por Wi-Fi (so volta por cabo, proibido). Nenhum numero do 480+.

**dmesg.** Sem hangs da GPU nos dois. No Odin com o Turnip: `kgsl kgsl-3d0: CP: AHB bus error` ~120 por minuto
(limitado pelo log), no Xenia e no Renut, durante toda a sessao com o Turnip (02:51-03:02 e desde 03:12); zero com o
driver do sistema (03:03-03:12). Nao trava nem baixa o fps: e do Turnip neste aparelho, nao do Renut.

**Regressao Renut.** Odin (Turnip) e Cube: titulo, dialogo do save (carregado no arranque), menu principal, Versus,
escolha do cenario, luta de varios rounds (fim de round com a fala do vencedor, "PLAYER 2 WINS!" no Odin e a luta
seguinte depois do A), campanha com duas cutscenes. Sem `device lost`, sem travar, sem retrato. Nao chegou a uma
transicao de mapa da campanha (os 180 s acabam dentro da primeira luta).

Defeito novo (visto no Odin, duas vezes em 480 s; nas fotos do Cube nao calhou): num golpe especial aparecem quadrados vermelhos e verdes solidos (particulas aditivas
desenhadas como quads cheios). Ver abaixo.

Quadrados: o censo do drawlog (`patches/renut/0105`: com `banjo_renut_diag_draw_log_s` o primeiro draw de cada PS
fica no log) mostra os PS das particulas, todos DrawVerticesUP com blend aditivo (`00010106`, src alpha + one):
`46D3C37E` (cor do vertice x textura 64x64 8888 em 1182D000, escreve profundidade), `A08CA5F4`, `15B2691F`,
`B61AE365`. Todos com textura ligada. Com `banjo_renut_cpu_memos = false` (para ver os slots) nao apareceu em 480 s,
mas o efeito e raro: nao prova nada. Proximo passo: drawlog nesse golpe (gravar com o drawlog continuo so desses PS) e
comparar com o Xenia.

## F4: limpeza (2026-10-28, 04:10 Lisboa)

`patches/renut/0105`: os diagnosticos `[db-decl]` e `[db-tex]` (0101-0103) so com `banjo_renut_diag_draw_log_s`, como
o `[db-ramp]` e o c255 do 0104; o caminho normal nao escreve nenhum (0 linhas no log da luta, Odin e Cube) nem faz a
procura num set em cada draw. O aviso de declaracao nao registada fica. Regressao final com o build do 0105: Odin
(Turnip) 60,0 fps (min 59,9), GPU 3,8 ms; Cube 37,6 fps (min 34,2), GPU 25,3 ms.

Fim da noite (04:10-04:25): 480 s de luta no Xenia (Odin, Turnip: 34,5 fps) e no Renut (Cube: 36,1 fps) sem os
quadrados (3.a tentativa, sem conclusao). Outra diferenca vista nessas fotos: nos golpes de perseguicao o Xenia
desenha um fundo azul-escuro com linhas de velocidade, o Renut um fundo branco com faixas azuis largas (Odin e Cube).
Como o cabelo, fica mais claro do que devia; o mapeamento dos blends do Renut (`format.cpp`, incluindo o reverse
subtract) esta certo. Aparelhos restaurados: Odin com o `cvars.txt` original e sem `files/driver` (driver do sistema),
hlprofile desinstalado; Cube sem `cvars.txt`; `burstlimit.toml` igual ao guardado nos dois.

## M5: manha de 28/10 (06:40-12:00 Lisboa, RP5 + Cube)

**Mali (Cube, Mali-G57, 720p, luta CPU x CPU).** Censo (`renut_gpu_profile`): ~27 ms por frame; numa GPU por blocos o
fragmento de cada passe aparece no comando seguinte, por isso mediu-se por subtracao. O que veio do Zandatsu e o que
se fez:

| Mudanca | fps | GPU ms |
|---|---|---|
| Antes (0105) | 36,2 | 26,4 |
| renut/0106 (o 0027 do Zandatsu: clear do alvo inteiro abre o passe com `CLEAR`) | 38,1 | 25,3 |
| renut/0107 (passes sem teste de profundidade abrem so com a cor) + `-Bsymbolic-functions` | 39,3 | 24,1 |
| 0107 desligado (`swordman_renut_depth_drop = false`) | 35,7 | 27,0 |
| game/0007 (passe de velocidade saltado com motion blur e profundidade de campo desligados), por subtracao | | -0,6 |
| 540p (`sa2_render_height_handheld = 540`) | 46,9 | 17,2 |

Sem ganho: mips lazy ou sem mips nos resolves, D24S8, handshake a 2 ms. Passes partidos: de 5,0 para 2,7 por frame com o
0107; os que sobram sao resolves da cena a meio do passe (do jogo). A 720p o limite e a GPU: frame de 28,3 ms com
25,7 ms em `BlockOnFence` (CPU do jogo ~2,6 ms). 60 fps a 720p pedem ~16 ms de GPU; a 540p fica nos 17 ms.

**RP5: os 48 fps nao eram CPU.** O toml do RP5 tem `frame_rate = "30"`; no Xenia isso e um vblank do convidado a 30 Hz
(`SetGuestVblankRate`), mas a thread de VBlank do Renut era fixa a 60 Hz, e o temporizador do jogo (1,2 vblanks) dava
~48 fps. Agora segue o ritmo pedido: `frame_rate` 30 -> 30,0 fps, 60 -> 60,0 fps (GPU 6,9 ms, Turnip PurpleVK).

**shaderInt64.** Nenhum dos 150 shaders do jogo o usa (SPIR-V sem `OpCapability Int64`); so o `point_sprite_gs` (endereco
do SharedConstants no push constant) e o `bc_decode_cs` (so sem BC nativo). renut/0108: variante `point_sprite_ubo_gs`
que le o SharedConstants do UBO do set 7; o Renut so exige shaderInt64 sem BC nativo.

**Cube depois de tudo (b8, 720p, luta):** 40,3 fps (min 37,5), GPU 23,0 ms, CPU do jogo ~2 ms: o limite e a GPU. A 540p
(preset Leve): luta ate ao fim 48,1 fps (min 41,6, GPU 16,6), campanha 55,0 (min 46,5, GPU 15,3). O ecra do Cube e
720x720 e o jogo ocupa 720x405, por isso 540p ja tem mais pixels do que se ve; o EASU nem corre la (a saida e menor que
o frame, so o RCAS). No Zandatsu o MGR faz 37-41 fps no Cube a 540p.

**Efeitos (game/0007).** O passe de velocidade (Xenia 29D1F5CC913EBB07) so serve o motion blur e a profundidade de
campo: sem os dois, salta nos dois renderizadores (-0,6 ms no Cube). Motion blur com a camara parada (luta em pausa,
so o motion blur ligado contra tudo desligado): o fundo do Renut fica igual (nitidez das arestas 26,6/26,9 no ceu,
14,5/16,2 no chao). Nao ha borrao de fundo por velocidade errada em repouso.

**480+ no RP5 (`device-profile.sh limit sd480p`, 540p, 60 fps pedidos).** Renut: luta 39,1 fps (min 35,4), campanha
53,9 (min 35,1). Xenia: 34,6 e 37,1 (GPU 23,8 e 22,3 ms). Limite: GPU a 305 MHz (a thread do jogo passa 20,4 dos
24,6 ms a espera da GPU; trabalho de CPU ~4 ms). 480p a 60: 50,3 (min 47,4); 540p a 30: 30,0 (min 30,0). Nos Adreno
os timestamps contam mal o trabalho em GMEM (7,95 ms medidos contra ~20 ms de espera).

**shaderInt64 / driver Qualcomm (renut/0108).** Alem do point sprite, o arranque no driver da Qualcomm (Vulkan 1.1)
caia em dois sitios, um de cada vez: `getDeviceAddress` (o trampolim do core 1.2 do loader do Android salta para
uma entrada nula; agora usa o KHR primeiro) e depois `vkCreatePipelineLayout` dentro do driver no `BuildDrawLayout`:
esse driver liga so 4 descriptor sets e o layout tem 8. O modo compacto do Mali (renut/0020) esta desligado em Adreno
porque o Turnip diz 4 mas liga 8. Proximo passo: compactar no driver proprietario (nome "Adreno (TM) ..." sem
Turnip/PurpleVK). Parado aqui pela regra das 3 falhas seguidas.

**Particulas em quadrados.** 15 min de luta no Cube com `banjo_renut_cpu_memos = false`: os quadrados continuam nos
golpes especiais. Os memos nao sao a causa.

**Cabelo cinzento: nao e o tempo, e o shader.** Despejo do estado completo (`banjo_renut_cpu_memos = false` +
`banjo_renut_diag_draw_log_s`) no inicio da campanha (cabelo preto) e 100 s depois (cinzento): no estado preto os
personagens sao desenhados por outro pixel shader (A08CA5F4CB866037, 91 draws); o toon 8E5E328AC8EA3309 so entra
depois, e ai o cabelo fica cinzento logo no primeiro draw (nas lutas e assim desde o "FIGHT!"). Estado do toon estavel:
slot 0 ramp DXT1 256x256 (dword0 82024802, clamp), slot 1 cor DXT5 512 ou 1024, c0 modo 1 (3 no passe 1024x1024),
c7 lightcolt 0, c8 ambiente (1,1,1,0.5). Nas lutas o toon nao le o resolve 1024x1024 (`banjo_renut_diag_resolve_trace`:
esse resolve so aparece em 3 frames da sessao), por isso o clear 4x/1x e o resolve MSAA dessa passada ficam de fora.
Proximo passo: a traducao do 8E5E328A (SPIR-V no `shader_cache.cpp`, entrada 129336/3025) contra o que o Xenia faz
com o mesmo ucode, a comecar pela amostragem da ramp e pelo rim (c6).

Aparelhos no fim: RP5 e Cube sem `cvars.txt`, `burstlimit.toml` igual ao guardado, RP5 sem `files/driver` (o PurpleVK
foi so para os testes), perfil 480+ desfeito (`device-profile.sh status`: no profile active). APK do branch instalado
nos dois.
