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

## T1: tarde de 28/10 (Lisboa, RP5 + Cube)

**Distribuicao (item 1).** Do Zandatsu (93e6d8b, 12127b8), sem mudar o metodo: o jogo (codigo gerado, hooks, Renut,
shaders traduzidos) passa a `libgame.so`, que so entra no APK encriptado (`assets/libgame.zdat`, AES-256-GCM, chave =
SHA-256 da imagem PE do `default.xex`; `tools/pack-libgame.py`, chamado pelo CMake). O `libmain.so` fica so o leitor do
XEX (`app/src/main/cpp/loader/`); o `GameLib.kt` desencripta no import (ISO ou pasta), no JOGAR e no `GameActivity`
quando o APK traz outro (`libgame.json` "id"). `tools/check-clean.py <apk> <libgame.so em claro> <default.xex>`:

| APK | Resultado |
|---|---|
| main 0.1.2 | `libmain.so` com o jogo em claro: 2772 de 3000 pedacos, nomes `sub_8...`, `PPCFuncMappings` |
| branch b12 (manha) | o mesmo (2784) e o cache de shaders do Renut (`g_shaderCacheEntries`, SPIR-V e DXIL) |
| branch b13 (cifrado) | 0 ficheiros com codigo ou nomes do jogo, sem `libgame.so`; repositorio e 737 blobs do historico limpos: LIMPO |

Import do `.iso` pelo launcher: RP5 10 s, Cube 6 s ate `import: libgame.so pronto (29 MB, id ..., title 424107DC)`
(os ficheiros com o tamanho certo nao se copiam outra vez); o jogo arranca depois nos dois. O APK cresce de 63 para
83 MB (o `.zdat` nao comprime; o `libmain.so` antigo comprimia).

**Renut no driver da Qualcomm (item 2, renut/0109).** O driver do sistema do Adreno 650 liga so 4 descriptor sets; o
layout compacto da Mali tinha 5. Com o nome "Adreno ..." (sem Turnip) e menos de 5 sets, os samplers juntam-se a tabela
de texturas no set 0 (binding 1) e as constantes vao para 1-3; o SPIR-V dos draws e renumerado (os samplers passam a
binding 1). O `point_sprite_ubo_gs` passa a automatico sem shaderInt64 (o Renut so o exige sem BC nativo). Log:
`Draw layout: textures and samplers in one set, 4 sets (4 bound at most)`.
RP5 com o driver do sistema: titulo, save, menu aos 66 s, luta ate ao fim do round 30,0 fps (min 29,8, GPU 8,3 ms; o
toml do RP5 tem frame_rate 30), campanha com as duas cutscenes 30,0 (min 29,9, GPU 8,6 ms), 0 GPU perdida, imagem igual
a do Turnip (o cabelo cinzento de sempre). Cube (Mali, layout de 5 sets intocado) 51,8 / 50,5 fps.

**Particulas em quadrados (item 3).** Hipotese do point sprite sem coordenada: cai. O censo do drawlog passou a ser por
(PS, tipo de primitiva) para apanhar um point list depois do primeiro draw de um PS (`render/draw.cpp`, diagnostico
so com `banjo_renut_diag_draw_log_s`). 10 min de luta no Cube com golpes especiais: 32 pares, todos `prim 6` (triangle
strip) ou `prim 13` (quad list), nenhum `prim 1`. As particulas sao DrawVerticesUP de 6 vertices em strip (VS
`95884550`, `8AD484C5`, `97E96260`; declaracao 0, o caminho dos vfetches), com a coordenada no vertice. Nenhum VS do
jogo escreve `oPts`, logo o `point_sprite_gs` (0108) nao corre para o jogo. Os raios vermelhos e verdes que apareceram
nas fotos sao do golpe (tem gradiente), nao os quadrados; o `46D3C37E` nao entrou nesses 10 min.
Foto do defeito (RP5, Renut, driver do sistema, luta aos 240 s, `reg_rp5_auto_240.png`): um quadrado centrado no Goku
num golpe, preto no canto de cima a esquerda, vermelho em cima a direita, verde em baixo a esquerda, amarelo em baixo a
direita: a cor e o (u, v) do proprio quadrado. 2.a hipotese, as vfetches remendadas pelo D3D do jogo lidas na
reproducao (`PatchedFetchDeclarationLocked`, um bind mais novo daria a cor a partir do UV): as palavras foram guardadas
na thread do jogo em cada draw e passadas em ordem; 260 s de luta e a campanha no RP5 e 260 s no Cube sem uma unica
vez em que mudassem entre o draw e a reproducao, nem um VS religado a outro layout. Cai; o codigo foi retirado. Sem
correcao, sem foto depois. Proximo (3.a): um quadrado com as cores do seu UV parece o mapa de distorcao de um golpe
(offsets de textura escritos num alvo a parte) a cair no alvo da cena: drawlog continuo nesse golpe, ver o alvo e o
EDRAM base desse draw contra os da cena.

**Cabelo cinzento (item 4), 4 tentativas.** (1) A traducao do toon `8E5E328A` (XenosRecomp, `ps_c3506b35fe163675.hlsl`)
contra o disassembly do Xenia do mesmo ucode (`CF0D1D92`, `dump_shaders`): as 52 instrucoes batem uma a uma, incluindo
`c_abs[8].w` (v da rampa), `-c255.w - -r0.x` (subsc), `max_sat`, `setp_gt -c8.w` (multiplica ou subtrai a rampa), o rim
(`log`/`exp` com c6.zy, `-r0.x * c6.x + r0.w`), os clamps a [-1, 1] e o tf0 = rampa / tf1 = cor (texture1 no slot 0).
O `FLT_MIN` dos clamps e -FLT_MAX: certo. (2) Uma cvar de debug que trocava constantes do PS pelo hash nos dois
caminhos (`draw_overrides.h`): c2.w = 0 mudou o Renut (personagens escuras e planas, cabelo ainda cinzento) e nao o
Xenia. (3) `swordman_skip_ps` / `swordman_renut_skip_ps` deste PS: as personagens somem nos dois, logo os dois as
desenham com ele. (4) c1 = (1,0,0,1) (a cor do fade a 100%, devia pintar tudo de vermelho): nao mudou nenhum dos dois.
A troca de constantes nao chega de forma fiavel a este PS (no Xenia nunca, no Renut so a c2), por isso nao da para por
r0.x e u na saida assim; a cvar foi retirada. Fica: a traducao esta certa, a diferenca vem das entradas (interpolantes
do VS `59D0D3EA`, texturas ou constantes). Proximo, sem trocar constantes: o mesmo debug escrito no proprio HLSL
(`oC0 = float4(r0.x*0.5+0.5, u, 0, 1)` sob uma palavra do SharedConstants) e, no Xenia, no translator de SPIR-V para
este hash; e comparar os VS `59D0D3EA` dos dois lados.

**Cube a 60 fps (item 5).** a) Censo por passe (`renut_gpu_profile`, luta a 540p, 19,4 ms): cena 6,65 ms (252 draws),
7 passes de pos em ecra inteiro 5,41 ms, resolves 4,77, apresentacao 0,85, resolves de profundidade 0,81, 480x270
0,55, sombra 0,31. Por shader (`banjo_renut_diag_gpu_shaders`) a Mali junta o passe inteiro no primeiro PS; os passes
de pos custam 0,4-0,65 ms cada. Os resolves sao ~20 por frame (13 de 960x540, 6 de 480x270, 1 de 1024x1024, 1 de
profundidade). Ataque: desenha-los em vez de copiar (`swordman_renut_draw_resolves`, renut/0110, o caminho do deko3d):
os 19 por frame desenhados, resolves 5,2 ms como antes: o tempo e o passe que acabam e a largura de banda, nao a
copia. Fica desligado. b) "Resolucao da tela": o Renut so desenha nas alturas da tabela de escalas (360, 480, 540, 720,
1080, 1440, 2160; 405 e uma escala nova que obrigaria a refazer a cache de shaders), logo a opcao usa a mais perto da
tela (no empate a menor): no Cube 405 -> 360 (o Xenia faz a exata, 56%). Medido no Cube, luta / campanha:

| Altura | Luta | Campanha | GPU |
|---|---|---|---|
| 540 | 51,8 (min 42,5) | 50,5 (min 43,9) | 15,8 ms |
| 480 | 59,1 (min 52,3) | 59,1 (min 57,8) | 13,3 ms |
| 360 ("Tela") | 59,9 (min 59,5) | 59,9 (min 58,8) | 11,7 ms |

c) No primeiro arranque numa tela com menos de 540 linhas de imagem o launcher pergunta (foto no Cube: "Esta tela
mostra o jogo em 404 linhas ... desenhado a 360p"); "Usar 360p" grava `sa2_render_height_handheld = 360`,
`draw_resolution_percent = 56`, `swordman_resolution_choice = "screen"`.

**Auto por omissao (item 6).** O "auto" (ja o valor por omissao) passa a pedir o Renut em todas as GPUs, menos a
Adreno 710/720 com o driver do sistema (SIGBUS, Zandatsu); o jogo usa o Xenia quando o driver nao tem o que o Renut
precisa (como antes). Novo: o `GameActivity` deixa `renut_boot.pending` nos primeiros 30 s de um arranque do Renut
pedido pelo "auto"; se o launcher o encontra ao abrir, o jogo fechou: `renut_boot.failed`, o "auto" passa ao Xenia e o
ecra Drivers diz porque (escolher o renderizador outra vez limpa-o). Testado no RP5 com o marcador posto a mao:
toml `swordman_renderer = "xenos"`, aviso no ecra Drivers, o jogo no Xenia ate ao menu (69 s); limpo, volta ao Renut.
Regressao com o "auto" (b20): RP5 Renut (driver do sistema, 4 sets) luta 30,0 (min 29,9), campanha 30,0 (min 29,9);
Cube Renut 640x360 luta 59,9 (min 59,5), campanha 59,9 (min 59,0); Cube no Xenia (56%) luta 44,0, campanha 43,5.

## N1: noite de 28/10 (Lisboa, RP5 + Cube)

**Cabelo cinzento: corrigido (renut/0111).** Diagnostico novo, `swordman_renut_diag_vs = "59D0D3EA"`
(`render/draw.cpp`): uma vez por layout, os elementos da declaracao, o que o host liga (stride, endian, formato,
conversao, troca de metades) e as vfetches remendadas (`DescribePatchedFetch`: formato, sinal, inteiro, rf, exp_adjust,
swizzle, offset, stride). Os draws do toon nao tem declaracao (VA 0): vao pelo `PatchedFetchDeclarationLocked`, com
dois layouts: pos float3 + normal float3 (stride 32) e cor 8888 + uv 16_16_FLOAT; ou stride 28 com a normal em
FLOAT16_4. rf e exp_adjust a 0 em todos. O D3D remendou o swizzle de destino das vfetches de 16 bits (normal `_xyz ->
_yxw`, uv `xy__ -> yx__`) porque todo o tipo de 16 bits do Xbox 360 e 8in16 e a stream e lida com a troca 8in32; o
shader recompilado guarda o swizzle do contentor, e o caminho das vfetches nao trocava as metades (o da declaracao
troca, `BoundDeclType`/`UnswapHalves`): a normal e o uv chegavam trocados ao toon, logo a rampa caia no cinzento. Com
os formatos 16_16, 16_16_16_16 e os seus FLOAT marcados 8in16 (como a declaracao do mesmo tipo), o host liga `swap 1`
nesses elementos e o cabelo do Goku e do Raditz fica preto na luta e na campanha, no Cube e no RP5, igual ao Xenia.

**Crash do Renut (item 3).** O Auto escolhe sempre o Renut (todas as GPUs, incluindo a Adreno 7xx com o driver do
sistema); o Xenia so pela opcao do jogador. Sem o que o Renut precisa, o jogo regista o motivo
(`renut_support.txt`) e fecha (`db_renut_app.h`), sem passar ao Xenia. O `GameActivity` poe `renut_run.pending`
enquanto o jogo corre no Renut e apaga-o no `onPause` (uma saida limpa zera tambem `renut_crashes.txt`); o launcher
que o encontra conta um crash seguido e mostra "O jogo fechou. Envie os logs para corrigirmos" com Exportar logs, o
link das issues e o motivo do driver, se houver; so a partir do 2.o seguido pergunta "Usar o modo emulado (Xenia)
por agora?" (Continuar no Renut / Usar o Xenia), nunca sozinho. Testado no Cube com o marcador posto a mao: aviso ao
1.o, Exportar logs grava `Download/DragonBurst-logs-*.zip` (18 MB), pergunta so ao 2.o, "Usar o Xenia" grava
`swordman_renderer = "xenos"`; uma sessao normal no Renut apaga o marcador e a contagem.

**Atualizacao por cima da 0.1.2 (item 4), Cube e RP5.** `DragonBurst-v0.1.2.apk` do GitHub (versionCode 3, a mesma chave)
instalado por cima (`adb install -r -d`, a pasta do jogo fica), import do `.iso` na 0.1.2 ("Jogo importado. Pronto
para jogar!"), opcoes mudadas (Carregar ki ligado e rapido, Cenarios extras desligado) e um save novo criado pela
0.1.2 no arranque (o anterior guardado a parte). Depois o build novo por cima (`adb install -r`, sem importar): o
JOGAR mostra "Lendo o disco · default.xex" e "Preparando o codigo do jogo · 28 / 29 MB" (gravacao do ecra; 120 ms no
Cube) e o jogo arranca no Renut ate ao menu com o save da 0.1.2 (md5 igual) e as opcoes mudadas no toml. O
`GameLib` passa a escrever essa linha tambem em `logs/update.log` (vai no Exportar logs). default.xex danificada (4
bytes trocados): a tela inicial dizia "Nao e a versao USA"; agora "O default.xex esta danificado: importe o jogo de
novo", com o JOGAR desligado (o import so aceita a USA, por isso outro SHA-1 ou tamanho depois disso e dano); o
atalho do jogo mostra a mesma mensagem no dialogo do SDL se o libgame tiver de ser refeito. default.xex em falta:
"Jogo ainda nao importado" e o JOGAR desligado; o jogo aberto direto mostra o proprio ecra de instalacao ("The game
files weren't found. Choose your ... (.iso)"), sem crash. Importar de novo apaga e copia sempre o default.xex. RP5: o mesmo com o `.iso` importado na 0.1.2 (Carregar ki
ligado e rapido, Cenarios extras desligado, save novo da 0.1.2) e depois o build novo por cima: o JOGAR mostra "Lendo
o disco · default.xex" com a barra, o `update.log` regista o libgame refeito (id novo, 82 ms) e o jogo arranca no
Renut com o cabelo preto, o save com o md5 igual (0477ad5b) e o toml igual (md5 0fa4aa31). No RP5 a primeira
tentativa nao refez nada porque um build de teste com o mesmo id ja tinha deixado o libgame: o teste valido e o do
build seguinte, com outro id.

**Quadrados e bolas do loading (item 2): corrigido (renut/0113).** Reproducao deterministica: no ecra "Now Loading"
as esferas de dragao saem no Renut vermelhas, verdes e escuras as manchas, e no Xenia laranja translucidas. Pelo censo
do dump em anel (`swordman_renut_frame_dump_ring`, novo, gravado ao pedido `files/frame_dump.req`) e por
`swordman_renut_skip_ps` as esferas sao VS DEE2E6E2 + PS 9C79B65D (14 draws por frame, sphere map), nao o D95AFF9F
(saltado, as esferas ficam). O PS multiplica o resultado pela cor do vertice, e essa cor chegava com a normal. As
vfetches remendadas do VS (diag com o registo de destino) contam a historia: o shader compilado le posicao, cor, uv e
normal (@003-@006, e a tabela de elementos do contentor diz isso por endereco), mas o D3D do jogo, ao ligar o shader a
uma declaracao, reordena as vfetches por offset na stream: @004 passa a escrever r4 com o FLOAT16_4 em +12 (a normal),
@005 r1 com o 8888 em +20 (a cor), @006 r0.zw com o FLOAT16_2 em +24 (o uv). Lendo pelo endereco da tabela, o Renut
dava ao shader recompilado (que e o da ordem compilada: r1 = cor) a normal como cor, a cor como uv e o uv como normal.
O Xenia corre a microcode remendada e nao depende da tabela. Agora o `XGRegisterVertexShader` guarda, antes de
qualquer bind, o que cada elemento escreve (registo e componentes), e cada elemento usa a vfetch remendada que escreve
o mesmo. As palavras sao lidas tambem no thread do jogo quando o draw e posto na fila (`DrawCall::fetch_words`): o
mesmo VS e ligado a outras declaracoes no mesmo frame e o render thread lia o remendo de um bind posterior. Sem
efeito (anotado): decode BC na CPU (`swordman_renut_bc_decode = "cpu"`), so o nivel 0 quando nao ha endereco de mips,
o mapa de direcao BC879F9A (desenha no alvo da cena e e composto a seguir, legitimamente), o reuso de cadeia
(compara layout_hash) e cache de vfetch velha (rele as palavras a cada draw).

**Perfil 480+ (item 5): FSR ate 720p (renut/0114).** RP5 com `device-profile.sh limit sd480p`, `present_effect =
"fsr"` (o toml do dono), 120 s por medicao. Censo de GPU por passe a 540p (t16, `renut_gpu_profile`): 17,5 ms por
frame, dos quais o present 7,9 ms (EASU + RCAS de 960x540 para 1920x1080), os draws da cena 4,5, o pos-processamento
1,9, os resolves 1,7, a sombra 768x768 1,1. Com `bilinear` o present custa 0,8 ms e com `cas` 2,1: o EASU a 1080p e
~5,5 ms na GPU a 305 MHz. RCAS em meia precisao (como o EASU ja esta): 7,94 vs 7,86 ms, sem ganho, revertido. Agora,
num upscale de 1,75x ou mais (os 540p e 480p dos aparelhos fracos num ecra 1080p), o FSR vai ate
`swordman_renut_fsr_max_height` (720 por omissao; 0 = ate ao ecra), EASU e RCAS ai, e a saida bilinear leva-o ao
ecra; os upscales menores (Cube: 540p para 405 linhas) nao mudam. Present 7,9 -> 4,1 ms, GPU 17,5 -> 13,6 ms.
Antes/depois (fps medio / minimo, GPU ms, espera da GPU, CPU do jogo): luta 540p 58,1/56,0, 16,6, 11,6, 5,6 ->
59,9/59,4, 12,4, 5,0, 11,7; campanha 540p 59,0/57,4, 16,3 -> 59,8/59,4, 12,5; luta 480p 59,8/58,3, 15,8 ->
60,0/59,9, 12,1; campanha 480p 59,9/59,5, 15,5 -> 59,8/58,6, 11,4. A base desta noite (t16, mesmo perfil e script)
ja deu 56-60, longe dos 39,1 / 53,9 / 50,3 de 28/10; a causa da diferenca nao foi isolada. As arestas no
menu ficam iguais as do FSR ate 1080p a olho (fotos), e a imagem fica limpa na luta e na campanha.

## B1: o jogo pausa fora da tela (29/10, 07:00-09:00 Lisboa, RP5 + Cube)

Antes (0.2.0 de 28/10, relatorio de 29/10): com o jogo fora da tela (HOME) a luta continuava a 30 fps, o Renut
desenhava sem apresentar, ~140 % de CPU e gpu_busy 22-24 % no RP5. Agora, com `onPause` ou a superficie destruida
(`GameActivity`, `NativeGameState.setBackground`, numa thread propria):

- a logica para no ponto de pausa do tick (a mesma dos menus) e o audio com `AudioSystem::Pause`; o SDL para o
  stream e a thread da UI (bloqueio no pause);
- a thread de render do jogo para no Swap (`rex_vdswap_hook`, Renut e Xenia), o VBlank do Renut e o handshake da
  frame (timeout de 8 ms) esperam a volta (`DragonBurstWaitForeground`, `android_hooks.cpp`, `renut_glue.cpp`);
- SDK 0189 `rex_background_wait`: o vsync do Xenia (na volta conta de novo, sem rajada), o ciclo ocioso e o
  WAIT_REG_MEM do command processor, `KeDelayExecutionThread` e `NtYieldExecution`; jogo 0008: a espera do D3D pela
  GPU (`sub_820B10E8`, sono de 250 us);
- renut/0115: o render thread do Renut espera sem os acordares de 2 ms do `sa2_renut_wake_batch`;
- SDK 0190 (tambem em primeiro plano): o `Transport::TimerLoop` da rede online dormia 1 ms de cada vez sem nada
  para enviar (~1000 acordares/s a sessao inteira); agora espera numa condition variable;
- SDK 0191 (do DBZ3-Android 0049): no Xenia a volta caia sempre (tambem na t19, antes da pausa):
  "VulkanPresenter: Failed to create a swapchain" e SIGSEGV em 0x4, o presenter preso a VkSurfaceKHR da janela
  destruida. MINIMIZED desliga a superficie, RESTORED liga a janela nova; a superficie guarda a sua referencia ao
  ANativeWindow. Fica um "Failed to create a swapchain" no log na volta, logo seguido de "Created ... swapchain".

Prova (t24-t26, luta CPU x CPU, 60 s fora da tela a partir dos 15 s de luta; `out/tmp/w/t/bg.sh`, `qs.sh`):

| Caso | CPU do `dragonburst:game` fora (top / soma das threads) | GPU | Na pausa | Volta |
|---|---|---|---|---|
| RP5 Renut HOME | 0-7,6 % / 1,6 % | gpu_busy 0-4 % (launcher) | 0 ticks, 0 quadros | mesmo PID, swapchain novo, som -21 dB |
| RP5 Renut tela desligada | 0-7,6 % / 1,2 % | 0 % | 1 tick, 0 quadros | idem |
| RP5 Renut recentes (60 s) | 0-7,6 % / 1,8 % | 0-4 % | 1 tick, 0 quadros | idem |
| RP5 Xenia HOME | 0-7,6 % / 3,2 % | 0-4 % | 1 tick, 1 quadro | idem (antes: crash) |
| Cube Renut HOME | 0-3,8 % / 1,1 % | sem contador (ver abaixo) | 1 tick, 1 quadro | mesmo PID, swapchain novo, som -28 dB |
| Cube Renut tela desligada | 0-11,5 % / 0,8 % | idem | 1 tick, 1 quadro | idem |
| Cube Renut recentes (60 s) | 0-3,8 % / 0,8 % | idem | 0 ticks, 1 quadro | idem |
| Cube Xenia HOME | 0-3,8 % / 1,2 % | idem | 1 tick, 3 quadros | idem (antes: crash) |

Troca rapida (5 vezes recentes 1 s e volta, Renut): RP5 e Cube sem crash, mesmo PID, som. O que resta fora da tela
e o relogio de 1 ms do kernel (KeTimeStampBundle, `TimerQueue`): ~1000 acordares/s, 1-1,5 % de um nucleo; parar a
fila de timers dispararia os timers periodicos do jogo em rajada na volta (`due_ += interval_`), por isso fica. No
Cube o `gpu_work_period` do Mali-G57 so diz "houve trabalho neste segundo" (sempre 999999900 ns) e chega com ~30 s
de atraso, e o frontend (cocoonshell) tem a GPU a 100 %: a prova sao os quadros mostrados na pausa (log
`primeiro plano depois de ...`).

Regressao (t26, Renut, `reg.sh` luta CPU x CPU ate ao fim, `camp.sh` 01 Saiyan Arrival 180 s): RP5 (toml do dono,
30 fps) luta 30,0/29,9 e campanha 30,0/29,9, GPU 11,1 e 10,8 ms, como a t19; Cube a 540p (como a t19) luta
47,7/41,3 (t19 45,9/40,0) e campanha 50,2/42,4 (t19 53,8/45,3) com GPU 16,3-16,6 ms, mas acabou a 84 C (a campanha
comecou depois de arrefecer a 62 C so 60 s); imagem igual nas fotos (cabelo preto, sem quadrados, Now Loading).
APK t26: sha256 edbf7a4575a2cee85bc4faa4527be9c6b2bac92bc8bb5e1cb8e6ac963b94d575.

## A: merge com a versao de PC (29/10, 09:15-10:30 Lisboa)

Base deste port: o jogo em v7.3 (`external/burstlimit` 8faff03, igual ao `origin/main` do PC a 29/10: nada de novo
no jogo desde 27/10; a v7.3 ja entrou pela patch de jogo 0005) e o SDK em 465f9ef (12/10). O PC v7.3 aponta o SDK
para 3c41e4b (27/10): 38 commits que o Android nao tinha (so o cf75e40 tinha vindo, na 0186). A 0005 desligava no
Android o que dependia deles (perfil movel, sonda de latencia, vigia da UI, lobby, vblank do ecra, teclado).

| PC | O que e | Android | Porque / onde |
|---|---|---|---|
| e6fabfb | vsync: sem tempestade de interrupts quando o relogio recua, catch-up de 8 vblanks; leitura dos comandos no pedido do jogo (`input_poll_on_read`, menos atraso); sonda de latencia | trazido | SDK 0192; sonda com present = quadros mostrados |
| 9dbb755 | menos CPU no presenter e no vblank | adaptado | o do presenter e so Windows; o vblank dorme com nanosleep ate 0,2 ms antes (era 2 ms a ceder) - 0192 |
| 40f162f | perfil assinado num utilizador movel (Xbox LIVE com qualquer comando) | trazido | 0192 + jogo 0009 (tira a guarda da 0005) |
| a126623 | conquistas por perfil | trazido (sem o menu do PC) | 0192; o ficheiro antigo passa para `achievements/<XUID>/` |
| 92eed19 | packs de texturas: alfa pre-multiplicado, limite 8192, mips, orcamento de RAM, cores ARGB | trazido (parte comum) | 0192; o lado D3D12 fica fora, o Vulkan (0049) ja usa os niveis da chave |
| b5ef429 | vigia da thread da UI | trazido (sem o proxy do Wine) | 0192, e a 0193 cala-o fora da tela (o SDL segura a UI na pausa) |
| 0cac967 | teclado como comando; `hid_generic_mapping` | so o `hid_generic_mapping` | comandos sem mapeamento SDL passam a usar-se; o teclado e de PC |
| 37734bb, fa21d55 | `input_test_pads`; comando `gpu_state`, passe `draws_focus` | trazido | ferramentas de teste e de diagnostico |
| 643fdda | falta de escrita sem VirtualQuery | fora | a 0007 do Android ja nem faz a consulta |
| c4db5a3 | ritmo do presenter com VSync, vblank preso ao ecra | fora | o presenter do Android e outro (0016/0092/0112) e o Renut tem o seu VBlank; fica para o B2 (120 Hz) se fizer falta |
| d0f8368 | lobby pela internet liga no arranque | fora | o cliente do lobby e WinHTTP; no Android e um stub (o lobby ainda nao existe aqui) |
| 297550f, 62bea9d | menu rapido do PC | fora | no Android o menu e o InGameMenu.kt |
| 6aaf31a, 197ff8c, a3ccb32, ffe5575, 3c41e4b, 9a10662, c49a54e e a serie do renderizador nativo (e8940b7 ... 35ec05c, 16 commits) | D3D12, DLSS, adaptadores, renderizador nativo D3D12 | fora | so PC; no Android o renderizador nativo e o Renut |

A serie inteira (194 patches do SDK, 9 do jogo) aplicada de novo sobre o SDK 465f9ef e o jogo 8faff03 da a mesma
arvore (diff -r vazio).

Achado no caminho (fora da tela, t29/t30): algumas vezes uma thread do jogo acordava ~930 vezes por segundo no
handshake da frame (sub_822A2E50, `NtSignalAndWaitForSingleObjectEx`): no Xenia o handshake nao tinha o gancho do
Renut, e a thread de render (820EA188) espera ali em modo alertable, que no SDK do POSIX acorda a cada 1 ms para ver
os APCs (`kAlertablePollSlice`). Agora: o gancho do handshake espera pela volta depois de cada chamada (Renut e
Xenia), a SDK 0194 depois de qualquer espera do kernel que acabe em STATUS_TIMEOUT, e a 0195 entre as fatias de 1 ms
das esperas alertable. Cube Xenia fora da tela (t32): 1,1-1,3 % e 1085-1090 acordares/s, so o relogio de 1 ms do
kernel (antes 1,4-1,7 % e ~2000/s).

Regressao depois do merge (t32, Renut): RP5 luta 30,0/29,9 (GPU 10,5 ms) e campanha 30,0/29,9 (10,7 ms), como antes;
Cube 540p luta 53,4/42,1 e campanha 50,3/41,2 (t26: 47,7/41,3 e 50,2/42,4); Xenia no RP5 (t31) luta ate ao fim com
os golpes certos. Fora da tela (t32): RP5 Xenia 1,6 %, RP5 Renut com a tela desligada 1,0 %, Cube 1,1-1,3 %; mesmo
processo e som na volta, vigia da UI calado, conquistas por perfil no log (`achievements/B13EBABEBABEBABE/`).

## B: AYN Odin 3 (29/10, 13:20-16:25 Lisboa, Adreno 830, tela de 120 Hz)

Renut com o driver do sistema da Qualcomm (Adreno (TM) 830, Vulkan 1.4): arranca sem o Turnip. Luta CPU x CPU de 2 min
e campanha 01 de 3 min (cutscene de 70 s e depois golpes); cada caso comecou com a CPU e a GPU abaixo de 75 C. Durante
as corridas a CPU anda a 87-88 C de media (picos de 93-101 C) e a GPU a 63-80 C (picos de 69-90 C).

B1, 60 FPS (fps medio / minimo, GPU ms):

| altura | luta | campanha |
|---|---|---|
| 720p | 59,8 / 57,6, 3,1 ms | 60,0 / 60,0, 3,1 ms |
| 1080p | 60,0 / 60,0, 7,0 ms | 60,0 / 59,9, 6,8 ms |
| 1440p | 59,8 / 59,3, 11,7 ms | 49,5 / 29,8, 19,4 ms (noutra corrida 60,0 / 59,7, 12,4 ms) |
| 2160p | 23,8 / 19,9, 43,4 ms | 21,3 / 19,6, 47,7 ms |

1080p e a maior que segura 60 nas duas. A 1440p a campanha quente cai: a GPU passa de 12 ms para 19-33 ms com o
gpu_busy a 99 % e o relogio no maximo (1100 MHz); o 4K custa 3,7 vezes o 1440p para 2,25 vezes os pixeis. Fica por
ver se e o driver a sair do modo por blocos (GMEM) acima de um tamanho.

B2, 120 FPS nativo: no Renut o jogo ficava nos 60 com a tela a 120 Hz (game_fps 58,7). O ritmo do Swap
(sa2_renut_pacing) tinha a grelha fixa de 60 Hz; a renut 0117 segue o vblank que o jogo pediu (60, 120, 144). Com ela
(t34, tela a 120 Hz):

| altura | luta | campanha |
|---|---|---|
| 720p | 118,9 / 107,7, 2,9 ms | 119,9 / 119,7, 3,1 ms |
| 1080p | 117,7 / 108,1, 7,4 ms | 119,2 / 117,9, 7,3 ms |
| 1440p | 34,2 / 29,1, 30,9 ms | 37,0 / 29,2, 30,7 ms |

A velocidade do jogo nao muda: a cutscene da campanha 01 corta nas mesmas horas a 120 e a 60 (1,6/1,6, 3,2/3,2,
14,3/14,4, 17,7/17,7, 18,7/18,7, 23,8/23,6, 25,4/25,1 s; fotos a cada ~0,7 s), e o relogio do round desce 10-11 em
10,5-10,8 s nos dois (para durante os especiais). A renut 0116 poe na linha de 10 s o que chega a tela
(`[renut] presented: game_fps= presented_fps=`).

B3, taxa desbloqueada: no Renut fica nos 60 (59,3). Tres tentativas: (1) o VBlank do Renut aceitar ate 1000 Hz (ficou
a 1000 Hz, o jogo nao); (2) mais `renut_vsync = false`; o jogo espera 16,4 ms por frame em `BlockOnFence`, preso ao
present a 60 Hz, com a GPU a 3,5 ms; (3) o Xenia desbloqueado como referencia, que nao chegou a correr. Abandonado:
o Android nao oferece o desbloqueado. O VBlank volta aos 240 Hz, e o log diz agora o ritmo real.

O Odin 3 saiu do adb as 16:25: bateria a 2 % (10,4 W de consumo contra o USB do PC; as 15:00 ja estava a 54 % no cabo).
Ficou a carregar desligado. B4 (120 interpolado), B5 (15 min) e B6 (regressao completa) ficam para quando voltar.

Regressao (t36): RP5 luta 30,0/30,0 (GPU 10,9 ms), campanha 30,0/30,0 (11,0 ms); Cube 540p luta 47,3/41,2 e
campanha 50,2/42,4. Na luta do Cube as primeiras 20 janelas sao as mesmas da t32; depois a luta da t36 ficou no chao
de Plains, mais pesado (GPU 16-18 ms), e a da t32 subiu ao ceu (13-16 ms).

### A thread da interface ocupava um nucleo (29/10, 20:30-21:10)

No Odin 3 a CPU passava dos 100 C com o Renut e ficava nos 65 C com o Xenia. A thread da interface (SDLThread, o
`RunMainMessageLoop`) estava sempre a correr (estado R), a 100 % de um nucleo, nos tres modos (60, 120 e 120
interpolado). No Cube, igual. O simpleperf no Cube (root, com os simbolos da libgame do PC, mesmo build-id) mostra
83 % do tempo dentro do `SDL_WaitEvent`: `SDL_PumpEventsInternal` -> `SDL_UpdateJoysticks` e o relogio, em ciclo. No
Android cada volta da espera do SDL poe na fila o "poll sentinel", e cada evento novo acorda a espera do Android
(`SDL_ANDROID_LIFECYCLE_WAKE`), por isso ela nunca bloqueava. Contagem: ~235 eventos por segundo devolvidos (os
`SDL_PumpEvents` que o `QueueControllerUpdate` pede a cada leitura dos comandos), mas um nucleo inteiro gasto entre
eles. No Xenia a thread passa o tempo bloqueada no present, por isso nao se notava. Nao e do merge: com
`input_poll_on_read = false` e igual.

Prova: `env:SDL_POLL_SENTINEL = 0` no cvars.txt tira a SDLThread da lista das threads quentes. A SDK 0196 desliga o
sentinel com a dica publica `SDL_HINT_POLL_SENTINEL` no inicio do ciclo (o SDL nao e mexido); ninguem aqui usa o
`SDL_PollEvent`. t38: Cube Renut luta 48,5/41,4 e campanha 53,1/44,1 (GPU 16 ms, limitado pela GPU como antes), Cube
Xenia luta 30,3/26,2 a pintar normalmente; RP5 luta 60,0/59,9 (GPU 8,5 ms) e campanha 60,0/59,9. O toml do RP5 mudou
as 19:41 fora destes testes (de 30 para 60 FPS, md5 6de1f04f): fica como esta.

### B no Odin 3 por Wi-Fi (29/10, 19:55-21:45, carregador de parede)

O Odin 3 voltou por USB e passou para o adb por Wi-Fi (`adb tcpip 5555`, 192.168.1.134), para carregar na parede.

- B3, Xenia desbloqueado a 720p: o jogo vai a 700-800 FPS (game_fps), a tela fica nos 60 (presented_fps 59,9).
- B4, 120 interpolado: Renut a 1080p, jogo a 60 e 117 na tela, 14051 quadros gerados na luta e 15245 na campanha,
  0 por gerar, GPU 7,8-8,5 ms; Xenia a 720p, 120,2 na tela, 1 por gerar por corrida, 0,41 ms de GPU por quadro gerado.
- B5, 15 min a 1080p/120 nativo: com a t36 a luta fez 93,5/59,2 e a campanha 83,7/57,6 (a GPU no limite e a CPU a
  103 C de media, com a SDLThread a girar); com a t38 (SDK 0196) a luta fez 119,8/111,5 e a campanha 120,0/119,8,
  CPU a 82 C de media, GPU a 81-82 C, 0 erros, 0 device lost.
- B6: titulo, menus e save (menu.sh), 15 min de campanha (cutscenes, KO, especiais, volta ao Z Chronicles e novo
  inicio), 15 min de lutas com fim e continuar; a campanha aos 30 e 45 s e o Now Loading iguais ao Xenia.

Odin 3 reposto no fim (restore.sh): t38, toml 22622616, saves e conquistas iguais ao backup, ecra como estava, adb
outra vez so por USB.

## W: widescreen na tela do Honor Magic 7 Pro (29/10, 22:00-23:00 Lisboa, Adreno 830, 2800x1280)

O pedido: widescreen pela tela do telemovel do dono (PTP-N49, 2800x1280 deitado, 2,19:1), uma opcao que liga e
desliga quando se quer. Nao foi reinventado: e o widescreen do BL v7.3 do PC (`burstlimit_widescreen.cpp`), que ja vem
no libgame (aspect_ratio, hor+ nas projecoes do jogo, PlaceOverlay a dizer onde vai cada desenho 2D). Faltava o lado do
GPU, que no PC e o D3D12 e aqui nao existia (a SDK 0186 so trouxe a projecao).

- renut 0118: o Renut pergunta ao jogo pela callback de overlay (rex/graphics/wide_aspect.h) em cada desenho sobre o
  alvo principal com o viewport nas 1280 colunas e a profundidade desligada ou sempre a passar, como o D3D12 do PC
  (hash do microcodigo igual ao do Xenia, renut 0104; constantes c0-c7, textura 0, stream 0, depth do registo no modo
  color+depth); o viewport e o scissor levam o x * escala + desvio. O present mostra o quadro na forma que o jogo pediu
  (GetGuestDisplayAspect, esticado na horizontal) e grava o tamanho da superficie para o `auto`.
- App: Imagem passa a Original 16:9 / Esticar / Widescreen nas telas mais largas que 16:9 com o Renut (launcher e
  menu do jogo). Widescreen escreve aspect_ratio = "auto" e swordman_aspect = "original"; no Xenia fica 16:9 (o HUD nao
  e reposto no Vulkan). No menu do jogo muda ao vivo (aspect_ratio e kHotReload, o jogo rele-a a cada 250 ms).
- renut 0119: o Renut fechava ao arrancar no Honor (SIGSEGV em vulkan.adreno.so, vkCreatePipelineLayout): o driver da
  Qualcomm dele (Vulkan 1.3) anuncia 7 descriptor sets e nao aguenta o layout de 8 que o do Odin 3 (1.4) aceitava. O
  driver da Qualcomm passa a ter o layout compacto (o do Mali) quando anuncia 5-7; abaixo de 5 continua fundido (RP5),
  o Turnip continua com 8. O Odin 3 tambem passa a compacto: falta revalida-lo (nao estava ligado).

Honor, Renut, 720p, 60 FPS, widescreen 2,188 (draw_resolution_scale_x 2 para o Xenia; o Renut desenha 1280x720 e
estica):
- luta CPU x CPU 3 min (t40): 60,0 em todas as janelas de 10 s menos uma no carregamento (57,6), GPU 9-10 ms, 48 C.
- campanha 3 min com cutscenes (t41, Saiyan Arrival): 60,0 / min 59,7, GPU 6,8 ms (max 9,9), 54 C. Cutscenes em ecra
  cheio, legendas e Drama Pieces na forma certa, retratos e barras de vida nas pontas, relogio ao centro.
- titulo, demo do titulo, Versus e escolha de palco: o fundo estica ate as pontas, personagens e texto na forma certa.
  O menu principal fica com faixas escuras aos lados (as pecas dele nao cobrem o ecra; e a regra do PC).
- ligar e desligar ao vivo (t41): Widescreen -> Esticar e Esticar -> Widescreen no menu do jogo, a meio da campanha,
  mudam no quadro seguinte. O menu do jogo grava so cvars, por isso a escolha le-se da aspect_ratio com o Renut (t42).

Fica para depois: o Renut desenhar mais colunas em widescreen (hoje 1280 esticadas a 2800; o Xenia ja sobe o
draw_resolution_scale_x), o widescreen no Xenia em Vulkan (o HUD) e revalidar o Odin 3 com a 0119.

## L: launcher e documentacao (29/10, 23:30-00:30 Lisboa)

- Abas numa barra em baixo, de pe e deitado (LauncherApp.kt, BottomTabs): deitado as 10 dividem a largura, de pe a
  barra rola e centra a aba escolhida; rotulos a 11 sp com largura minima de 88 dp (sem cortes no Honor).
- Icone novo (tools/icon_gen.py): esfera laranja com estrela numa aura dourada com faiscas azuis, fundo violeta;
  monocromatico = aura + esfera com a estrela vazada. Visto no ecra inicial do Honor.
- Regressao t44 (so Kotlin e PNGs; as .so e os assets do jogo sao iguais byte a byte aos da t42): RP5 luta 60,0/60,0
  (GPU 8,3 ms) e campanha 60,0/59,9; Cube 540p luta 49,0/43,1 e campanha 49,8 e 50,4. A campanha do Cube na t42
  tinha dado 55,1; A/B logo a seguir, a t42 no mesmo estado do aparelho: 50,4/41,6, igual a t44 (a variacao e do
  aparelho; t26-t38 deram 50,2-53,1). Imagem certa nos mosaicos (cabelo preto, sem quadrados).
- README (PT/EN/ES) e CHANGELOG/notas da 0.2.0 completos, com problemas conhecidos e imagens em docs/img/.

## H: hotfix 0.2.1, Renut sem shaderInt64 (30/10, Galaxy M54, Mali-G68)

- Log de um jogador (0.2.0, Auto): `Renut: o driver nao tem shaderInt64`, o jogo fecha 8 ms depois de abrir. O driver do
  M54 nao tem shaderInt64 nem textureCompressionBC; era a unica coisa em falta (o Cube, Mali-G57, tem shaderInt64).
- Sem os dois, `swordman_renut_bc_decode` passa sozinho a `cpu` (renut_glue.cpp): o `bc_decode_cs` e o unico que precisava
  dele alem do point sprite, e o decode na CPU ja existia (renut/0020). renut/0120: sem shaderInt64 o `point_sprite_gs`
  de 64 bits nem chega a ser criado.
- Sem acesso ao M54 (os aparelhos estavam noutros testes): o APK foi ao jogador e o dono confirmou que funcionou. Sem log
  nem numeros desta versao: falta medir o FPS e o custo do decode na CPU nos carregamentos. Log esperado:
  `BC textures: decoded on the CPU`.
