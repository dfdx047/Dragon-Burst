# Renut no Burst Limit: avaliação do M1

27/10/2026. Análise estática do `default.xex` USA (XDK 2.0.6683) no código gerado de
`external/burstlimit/generated/default/`, comparada com o Renut do mgr-nx-deko (MGR, XDK 21173) em
`ProjectSwordMan/third_party/mgr-nx-deko/app/src/renut_engine/gpu/`. Sem aparelho e sem compilar. Endereços e provas
de cada função: `HOOKMAP_BL.md`.

## 1. Conclusão

O Renut nasceu no **banjo-nx** (Banjo-Kazooie: Nuts & Bolts, XDK 7645). Depois o sa2-nx (SA2, 21076) e o mgr-nx-deko
(MGR, 21173) deslocaram os campos privados do `CDevice` e deixaram o valor do N&B entre parênteses retos no código
(`// [0x2A9C]`). O device do **Burst Limit (6683) tem o layout do N&B**:

- a parte pública (0x0000–0x2AA4: máscaras pendentes, anel, tabelas de render state, `_D3DConstants`, pacotes de
  registos) é igual nos três XDK;
- a parte privada coincide com os valores do N&B **de 0x2A80 até pelo menos 0x4158** (fences, flags, objetos
  ligados, tiling, command buffer, cópia dos params de apresentação, anel/segmentos, fence do Present, secções
  críticas, gama, contador de Swap, side buffer). São dezenas de campos, cada um provado no código na secção 2.2;
- a cauda (a partir de ~0x5420) **difere do N&B**: −4 bytes nos campos de vídeo e nos -1 do título, `BehaviorFlags` em
  22300 (N&B 23980), e o device tem 0x5780 bytes (N&B 0x5E00, MGR 0x6080).

Portanto o Renut **não precisa de layout novo**. Precisa de voltar a ter o layout do N&B, que está todo escrito nos
comentários, e de uma tabela própria só para a cauda. O trabalho pesado do M1 está noutros três pontos:

1. o D3D do Burst Limit foi ligado com **LTCG**: CreateDevice, InsertCallback, XGRegisterPixelShader e Release estão
   embutidos em código do jogo;
2. o jogo usa **tiling predicado** (BeginTiling/EndTiling), coisa que o MGR e o SA2 não fazem; o código do N&B
   para isso existe no Renut, mas não está ligado;
3. os shaders estão num ficheiro do CPK com compressão CRILAYLA de magic zerado.

## 2. O que o Renut lê da memória do guest com offsets fixos

Legenda: **igual** = mesmo offset do MGR; **N&B** = o valor entre `[...]` no código do Renut, provado no BL;
**outro** = nem MGR nem N&B (valor do BL provado); **não provado** = falta prova no BL.

### 2.1 Parte pública do device (`d3d.h` `struct Device`, `d3d_native.h` `off::`)

| Campo | MGR | BL | Situação | Prova no BL |
|---|---|---|---|---|
| `m_Pending[5]` | 0x00–0x27 | 0x00–0x27 | igual | draws `ld r4,0(r31)` … `ld r27,32(r31)`; XenonGpu_Initialize `std r11,0..32(r31)` |
| `m_pRing` / `Limit` / `Guarantee` | 0x30/0x34/0x38 | igual | igual | BeginRingBig `lwz 48`/`lwz 52`; KickOffSegment `lwz 56` |
| `m_ReferenceCount` | 0x3C | igual | igual | `820F92F8 stw r31,60(r29)`; `820EA5B0 lwz r11,60(r31)` |
| `m_Set/GetRenderStateCall[101]`, `m_Set/GetSamplerStateCall[20]` | 0x40/0x224/0x1D4/0x3B8 | igual | igual | InitializeApiState: `addi r8,r11,16` / `137` / `117` / `238` (×4) e `lwz r11,64(r9)` / `468(r9)` + `bctrl` |
| `m_Constants.TextureFetch[26]` | 0x480 | igual | igual | SetTexture `addi r11,r4,48; mulli 24`; XenonGpu_Initialize `addi r11,r31,1152`, 26 entradas |
| `VertexFetch[18]` | 0x6F0 | igual | igual | SetStreamSource `addi r5,r11,222; rlwinm 3` (= 0x6F0 + 8·(17−n)); XenonGpu_Initialize `addi r11,r31,1776`, 18 entradas |
| `VertexShaderF` / `PixelShaderF` | 0x780 / 0x1780 | igual | igual | draws `addi r6,r31,1920; li r5,16384` / `addi r6,r31,6016; li r5,17408` |
| `VertexShaderB…PixelShaderI` | 0x2780 | igual | igual | DrawIndexedVertices `addi r6,r31,10112; li r5,18688` (registo 0x4900) |
| `m_DestinationPacket` | 0x2880 | igual | igual | draws `addi r6,r31,10368; li r5,8192` (0x2000) |
| `m_WindowPacket` | 0x28C0 | igual | igual | o BL, como o MGR, escreve-o direto no anel (WindowScissor) e não no shadow: o hook do Renut serve |
| `m_ValuesPacket` | 0x28CC | igual | igual | `addi r6,r31,10444; li r5,8448` (0x2100); XenonGpu_Initialize `stw 10444` = 0xFFFFFF |
| `m_ProgramPacket` / `m_ControlPacket` | 0x2920 / 0x2934 | igual | igual | `10528`/0x2180, `10548`/0x2200 |
| `m_TessellatorPacket` / `m_MiscPacket` / `m_PointPacket` | 0x2964 / 0x29B8 / 0x2A50 | igual | igual | `10596`/0x2280, `10680`/0x2300, `10832`/0x2380 |
| `ClipControl` / `ModeControl` | 0x2944 / 0x2948 | igual | igual | XenonGpu_Initialize `oris …,8` em `10564`, `oris …,1` em `10568` |

### 2.2 Parte privada do CDevice

| Constante do Renut (ficheiro) | MGR/SA2 | `[N&B]` | **BL** | Situação | Prova no BL |
|---|---|---|---|---|---|
| `kDeviceOffsetGlobalQword` (device.cpp) | 11008 | 10880 | 10880 | N&B | `820F94D4 std r11,10880(r29)` |
| `kGlobalQwordVa` | 0x834FB7E0 | 0x82F39B38 | **0x827252E8** | outro (dado do jogo) | `820F94C8 lis r11,-32142` + `ld r11,21224(r11)` |
| `kDeviceOffsetCreationThreadId` / `OwnerThread` | 11016 | 10888 | 10888 | N&B | `820F94EC stw r11,10888(r29)` após GetCurrentThreadId; CBlocker::Check `lwz r11,10888(r29)` |
| `kDeviceOffsetIsTitleProcess` | 11020 | 10892 | 10892 | N&B | `820F94E0 stw r28,10892(r29)` |
| `kDeviceOffsetFenceCompletion` / `off::FenceCompletion` | 11024 | 10896 | 10896 | N&B | KickOff, BlockOnFence, CBlocker::Check |
| bloco de interrupção/Swap (`Sa2CreateDevice` grava 11028) | 11028 | (10900) | 10900 | N&B pela posição | callback de interrupção `0x820AE9C0` chama `[[10900]+16]`; SynchronizeToPresentationInterval `lwz r10,10900(r31)` |
| `kDeviceOffsetFenceTag` | 11036 | 10908 | 10908 | N&B | KickOff `lwz r11,10908`, BlockOnFence |
| `off::CommandBufferTagMask` | 11040 | 10912 | 10912 | N&B | SetVertexShader `lwz r11,10912(r30); and.` com o Common do recurso |
| `kDeviceOffsetSwapFenceTag` | 11056 | 10928 | 10928 | N&B | `820F9588 lwz r31,10908; stw r31,10928` |
| `off::TilePredication` / `RunPredication` | 11060/11064 | 10932/10936 | 10932/10936 | N&B | InitializeApiState `stw r30,10932` / `10936` |
| `kDeviceOffsetFlags` / `off::Flags` (0x80 sem hardware) | 11068 | 10940 | 10940 | N&B | KickOff `lbz 10940; rlwinm. …,0,0,24` |
| `kDeviceOffsetFlags2` / `SwapFlags` (0x02 fences imediatas, 0x10 swap pendente, 0x20 sem memória) | 11069 | 10941 | 10941 | N&B | KickOff `ori 2`, SynchronizeToPresentationInterval `ori 16`, NewSegment `ori 32` |
| `kDeviceOffsetVertexDeclaration` | 12240 | 11820 | 11820 | N&B | SetVertexDeclaration `820A2B70 stw r4,11820(r3)` |
| declaração padrão do device | +0x2FA0 | 0x2FA0 | 12192 | N&B | InitializeApiState `stw 12192`=5, `stw r29,11820` |
| `kDeviceOffsetIndexBuffer` | 12812 | 12436 | 12436 | N&B | DrawIndexedVertices `lwz r22,12436(r31)` |
| `kDeviceOffsetRenderTargets[4]` / `DepthStencil` | 12816 / 12832 | 12440 / 12456 | 12440 / 12456 | N&B | ClearF `lwz r7,12440(r3)`, `lwz r11,12456(r3)` |
| `kDeviceOffsetStreamSources[16]` / `StreamStrides` | 12836 / 12904 | 12460 / 12528 | 12460 / 12528 | N&B | SetStreamSource `addi r11,r29,3115` (×4); BeginVertices `stb r11,12528` |
| `kDeviceOffsetTextures[26]` | 12920 | 12544 | 12544 | N&B | SetTexture `addi r11,r4,3136` (×4) |
| viewport (`d.F(0x3168+i)`, draw.cpp) | (0x3168) | 12648 | 12648 | N&B | SetScissorRect `lfs f0,12648(r3)` … `12660` |
| `off::ScissorRect` | 13052 | 12676 | 12676 | N&B | SetScissorRect `stw r11,12676(r3)` … `12688` |
| `kDeviceOffsetPixelShader` / `VertexShader` | 13068 / 13072 | 12692 / 12696 | 12692 / 12696 | N&B | SetPixelShader `stw r29,12692`; SetVertexShader `stw r29,12696` |
| `kDeviceOffsetPredication` / `RunPredicationState` / `PredicationSet` | 13084/13088/13092 | 12708/12712/12716 | 12708/12712/12716 | N&B | InitializeApiState `stw r10,12708` (-1), `stw r30,12712`, `stw r30,12716` |
| `off::TilingSurfaces[5]` / `TileCount` / `TileRects` / `TileOrigins` | 13104/13124/13128/13368 | 12728/12748/12752/12992 | igual ao N&B | N&B | BeginTiling `820BA5A8 stw 12728…12744`, `stw r5,12748`; WindowScissor `addi r28,r31,12756` / `addi r27,r31,12996` (−4 no ciclo) |
| `kDeviceSnapshotSize` (o que a cópia do device leva) | 13088 | 0x31A8 | 12716 (até à predicação, inclusive) | N&B pela posição | os campos lidos pelos draws acabam em `12712`/`12716` |
| `kDeviceOffsetInKickOff` | 13600 | 13232 | 13232 | N&B | KickOff `lwz r11,13232(r31)` |
| `kDeviceOffsetVerticesRingEnd` | 13844 | 13444 | 13444 | N&B | BeginVertices `stw r11,13444`; DrawVerticesUP `lwz r11,13444; stw r11,48` |
| `kDeviceOffsetCommandBuffer` | 13920 | 13516 | 13516 | N&B | AllocateInlineData `lwz r30,13516(r31)`; campos do D3DCommandBuffer `+152` (`kBufferFixedMemory` 0x98) e `+168` (`kBufferAllocatorSize` 0xA8) iguais |
| cópia de `D3DPRESENT_PARAMETERS` | 13948 | 0x34E8 | 13544 | N&B | InitializePresentationParameters `addi r3,r29,13544; li r5,124` |
| `kDeviceOffsetPresentationInterval` (sa2_renut_pacing.cpp) | 14000 | (0x351C) | 13596 | N&B | InitializeApiState / SynchronizeToPresentationInterval `lwz 13596` |
| formato do front buffer | (0x3528) | 0x3528 | 13608 | N&B | InitializePresentationParameters `stw r11,13608`; Swap `lwz r10,13608` |
| `off::AutoDepthStencil` / `AutoFrontBuffer` / `AutoBackBuffer` | 15228/15232/15236 | 14824/14828/14832 | igual ao N&B | N&B | InitializePresentationParameters |
| estado do alocador do anel / início do segmento | 15320 / 15332 | — | 14916 / 14928 | N&B pela posição (MGR −404) | RingBufferDeviceAllocate `lwz r11,14916(r3)`; KickOffSegment `lwz r30,14928` |
| `kDeviceOffsetPresentFenceTag` | 15344 | 14940 | 14940 | N&B | Swap `stw r30,14940`; NewSegment espera `14940` |
| `kDeviceOffsetCriticalSection0/1` | 15348/15376 | 14944/14972 | igual ao N&B | N&B | `820B7230 addi r3,r31,14944` / `14972` |
| rampa de gama (`kSa2DeviceOffsetGammaRamp`) | 15408 | 0x3A9C | 15004 | N&B | Swap `addi r28,r31,15004` |
| `kDeviceOffsetSwapCount` / Swaps mostrados | 16968 / 16976 | 16560 / — | 16560 / 16568 | N&B | Swap `lwz/stw 16560`; callback de VBlank `820B5CF8` incrementa `16568` |
| `16712` (zerado no setup) | 16712 | 16712 | 16712 | igual | `820B7230 stw r29,16712` |
| `kDeviceOffsetSideBuffer` (0x12C0) | 17136 | 16728 | 16728 | N&B | `820F94F8 stw r3,16728(r29)` com `li r3,4800` |
| campos de vídeo de InitializePresentationParameters | 0x5428… | 0x5428/0x542C/0x5430/0x5434 | **21540/21544/21548/21552** | outro (N&B −4) | `820B6E38 stw r11,21540` (largura), `21544` (altura), `21548`, `stfiwx → 21552` (refresh) |
| `kDeviceOffsetsMinusOne` (device do título) | 21968/21972 | 21560/21564 | **21556/21560** | outro (N&B −4) | `820B7230 stw r11,21556` / `21560` |
| `kDeviceOffsetPerfFrequency` (+4 = período) | 21996 | 21588 | **21584** | outro (N&B −4) | `820B7230 stfs f0,21584` (frequência) e `21588` (1/f) |
| `kDeviceOffsetTitleMinusOne` (41 palavras) | 23380 | — | **21700** | outro | `820B7230 addi r11,r31,21700; li r10,41` |
| `kDeviceOffsetBehaviorFlags` (= `0x5DAC` de InitializeApiState) | 24392 | 23980 | **22300** | outro | `820F9520 stw r11,22300(r29)`; InitializeApiState `lwz r23,22300` |
| `kDeviceAllocSize` | 0x6080 | 0x5E00 | **0x5780** | outro | `820F94B0 li r5,22400` (memset do device) |
| `kVdGlobalDeviceImportSlot` | 0x820005F8 | 0x820007FC | **0x820005C4** | outro | `820B72C8 lwz r11,1476(0x82000000)`; a imagem tem `0x000101BE` no slot (xboxkrnl 0x1BE = VdGlobalDevice no `export_table.inc` do SDK) |
| `d.W(0x541C)` (`SetFence`) e `0x3A48` | — | 0x541C / 0x3A48 | ? / 14920 | **não provado** / N&B pela posição | `14920` aparece em `820AE348` e no alocador do anel, mas o papel não foi verificado; para `0x541C` faltaria ler o `SetFence` do BL (provavelmente `820AE348`) |

**Endereços absolutos do N&B** no Renut (`kRenderStateTable`, `kSamplerStateTable`, `kFormatTable 0x820052F8`,
`kGameNoLargePagesVa`, `kHiZOwnerVa`, `kSkipExportNanVertex`, `kDeviceCapsVa`, `kFusionVa`, `kFullViewport 0x820018DC`)
só servem o caminho nativo do N&B (CreateDevice/CreateSurface/estado nativos). O caminho do SA2/MGR, que é o que se
copia, usa as funções do próprio jogo para isso, e estes endereços não entram. Se um deles passar a ser usado no BL, tem
de ser procurado de novo; nenhum foi provado.

### 2.3 Objetos (recursos, shaders, declarações)

| O que o Renut lê | MGR/N&B | BL | Situação | Prova no BL |
|---|---|---|---|---|
| `D3DResource` (24 bytes: Common, RefCount +4, Fence +8, ReadFence, Identifier, BaseFlush) | igual | igual | igual | Release `lwarx` em `+4`; Destroy `lwz r4,8(r30)` → BlockOnFence; tipo `clrlwi …,28` |
| tipos: VertexDeclaration 5, VertexShader 6, PixelShader 7, Surface 4 | N&B | 5/6/7/4 | igual | `0x00100005` (`820A2B88`), `li r10,6` (`820A27B8`), `0x00100007` (`820A24E8`), Release testa `==4` |
| tipos 1/2/3 e 16–20 (VB, IB, textura, volume…) | N&B | ? | não provado | faltaria ler a tabela de saltos de `820A9268` (`0x8207FCF0`) |
| `BaseTexture.Format` em +0x1C (fetch constant de 24 bytes) | 0x1C | 0x1C | igual | Swap `addi r4,r26,28; li r5,24` |
| Surface: ColorInfo +0x1C (base de EDRAM), Size +0x24, EdramSize +0x2C | N&B | igual | igual | ClearF `lwz r11,36(r11)` com `>>18` / `>>3`; Destroy `lwz r9,28(r30)` e `lwz r11,44(r30)` / 5120 |
| IndexBuffer: bit 31 de Common (32 bits), Address +0x18 | N&B | igual | igual | DrawIndexedVertices `lwz r11,0(r22); rlwinm. …,0,0,0`, `lwz r8,24(r22)` |
| VertexBuffer: fetch constant em +0x18 (endereço) / +0x1C (tamanho) | N&B | igual | igual | SetStreamSource `lwz r10,24(r30)`, `lwz r9,28(r30)` |
| VertexShader: container em +0x368, parte física em +0x20 | igual | igual | igual | XGRegisterVertexShader `li r5,872`, `stw r30,32(r31)`; CreateVertexShader copia o container para `+872` |
| PixelShader: container em +0x28, parte física em +0x18 | igual | igual | igual | CreatePixelShader `addi r3,r31,40` (memcpy), `stw r29,24(r31)` |
| VertexDeclaration (objeto) | — | 52 + 12·n bytes | não usado | no caminho do SA2/MGR o Renut lê os elementos que o jogo passa a CreateVertexDeclaration, não o objeto |
| D3DVertexShader_Bind (o N&B tinha hook) | N&B 0x82657048 | candidatos `820B0F10` → `820AFAC0` | não provado | o jogo chama `820B0F10` antes de SetVertexShader; o MGR não liga este hook |

### 2.4 Anel, segmentos e fences

- **Anel:** a mesma ideia do Renut serve: um sink que o KickOff rebobina. O jogo tem BeginRing em linha (`lwz 48`/`lwz 56`
  + `bl KickOff`, ex. `820EA188`) e escreve pacotes direto no anel; isso cai no sink. KickOff devolve `m_pRing` como no MGR.
- **Segmentos:** NewSegment, KickOffSegment e RingBufferDeviceAllocate têm a mesma função e a mesma assinatura; os
  campos são os do N&B (14904–14940). O caminho "sem memória" (side buffer, `10941 |= 0x20`) é o mesmo que o
  `Sa2NewSegment_hook` desfaz.
- **Fences:** o mesmo protocolo (`10908` tag atual, `[10896]` palavra concluída, teste `tag − fence < tag − concluída`,
  +2 por fence; KickOff marca `10941 |= 2`). O BlockOnFence do BL não tem o argumento NoKick.
- **Callbacks:** o D3DDevice_InsertCallback está embutido no jogo (`820EA188`). O escritor interno `820AE868` põe o
  callback e o contexto no pacote, e a interrupção (`0x820AE9C0`) chama-os a partir do bloco `[10900]`. O hook do Renut
  precisa de um invólucro novo (ver `HOOKMAP_BL.md`, linha 16).
- **Command buffers:** offsets coerentes com o N&B (`13516`, `+152`, `+168`). Se o jogo grava command buffers
  (BeginCommandBuffer) não foi verificado.

### 2.5 O que o MGR não tem e o BL usa

- **Tiling predicado:** `820BA5A8` = D3DDevice_BeginTiling (`stw r5,12748` TileCount, superfícies em `12728`–`12744`,
  `13188`, flags `10940`/`10943`), chamado pelo jogo em `820F1014` e `820F10D0`; `820BAA48` = EndTiling (lê `12748`, repõe
  a predicação `12708`–`12724`, `10932`/`10936`), chamado em `820F9018`. O `draw.cpp` do Renut tem os hooks do N&B
  (`D3DDevice_BeginTiling_hook`, `D3DDevice_EndTiling_hook`) mas não estão ligados no MGR, e misturam `off::` deslocados
  para o SA2 (+376) com literais do N&B (`0x31B0`, `0x31B4`, `0x3384`, `0x3390`, `0x33A0`). Com o layout do N&B no BL,
  esses literais ficam certos e os `off::` têm de voltar ao valor entre parênteses. Os draws dentro de BeginTiling/EndTiling
  levam `Flags & 1` e a `Predication`.
- **Front buffer automático:** o BL cria-o (`820B6E38` chama `820A1848` quando `DisableAutoFrontBuffer` é 0). No caminho
  do SA2/MGR é a função do jogo que o cria, por isso não há problema (o erro "automatic front buffer is not supported" só
  existe no caminho nativo do N&B).

## 3. Shaders

**Onde estão** (provado com os bytes):

1. **`/XBOX360.NSH` dentro de `LONG2DATA/LONG2DATA_US.CPK`**: 41 216 bytes comprimidos, 335 360 descomprimidos,
   cabeçalho `NSXR`, com **142 containers de vertex shader (`0x102A1100`) e 142 de pixel shader (`0x102A1101`)**. É a
   biblioteca de shaders do jogo.
2. **Na XEX:** 14 containers (7 VS + 7 PS) em `0x82720280`–`0x82722200`, quase certamente os shaders internos do D3D
   (clear, resolve, present, gama). Os draws internos do D3D (`820BDCD0`, `820BE7B0`) escrevem DRAW_INDX em linha no anel
   e não passam pelos draws com hook, logo o Renut não os desenha. À partida não fazem falta; incluir na cache não custa.
3. **Nos outros ficheiros do CPK não há shaders:** procurei o magic em todos os ficheiros não comprimidos (só 2 falsos
   positivos em áudio `.CSB`) e numa amostra dos comprimidos (`AMB`, `NUT`, `NUX`, `AMA`, `NFX`, `BIN`: 0 containers). Os
   `.NFX` maiores e os `.NUX` de personagens não foram todos lidos.

**Como o jogo os regista:** `8211FF58` chama CreateVertexShader `820A28B0`, CreatePixelShader `820A24E8` e
CreateVertexDeclaration `820A2C78`. O carregador de materiais `820F9CF8` copia o container para cada objeto, regista 3
cópias de cada VS com `XGRegisterVertexShader` e regista o PS em linha. Em todos os casos o **container chega intacto**
ao objeto (memcpy), e o Renut usa o hash do container como chave: a cache feita a partir do NSH bate certo.

**Formato do CPK** (provado, e diferente do MGR):

- os offsets dos ficheiros contam a partir de `ContentOffset` (0x800), **não** de `TocOffset`. O
  `tools/installer_probe/cpk_toc.py` do mgr-nx-deko soma `TocOffset` e dá offsets fora do ficheiro: o primeiro ficheiro
  aparece em 585 652 224, e o CPK tem 585 853 064 bytes;
- a compressão é CRILAYLA **com os 8 bytes do magic a zero**: em 0x800 está `00…00`, depois o tamanho descomprimido
  `0x0003D6A0` (= 251 808, o ExtractSize do primeiro ficheiro) e `0x0001A198`. O `cpk_shader_scan.cpp` procura
  `CRILAYLA` e salta estes ficheiros;
- um descompressor CRILAYLA de ~35 linhas em Python (o meu teste ficou no scratchpad, fora do repositório) descomprime
  `XBOX360.NSH` corretamente.

**Cache do XenosRecomp para o BL** (no PC; o resultado é dado do jogo e não vai para o repositório nem para a
distribuição):

1. Ler o TOC do `LONG2DATA_US.CPK` com base em `ContentOffset`, extrair `/XBOX360.NSH` e descomprimir com CRILAYLA
   aceitando magic a zero. Basta este ficheiro, não é preciso varrer os 3 359.
2. Partir pelo magic `0x102A1100`/`0x102A1101`. O tamanho de cada container vem do cabeçalho (`virtualSize` +
   `physicalSize`, como no `shader.h` do XenosRecomp), ou então da tabela do `NSXR` (142 entradas). Escrever
   `vs_/ps_<sha>.bin`, como o `extract_mgr_cpk_shaders.py`. Juntar os 14 da XEX (opcional).
3. `XenosRecomp <dir> shader_cache.cpp shader_common.h` no host, com o mesmo `banjo_renut_shader_cache_override()`
   que o ProjectSwordMan já usa no Android.
4. Ligar `renut_shader_capture_dir` no primeiro arranque, para apanhar variantes que falhem.

Risco por verificar: microcode de um XDK de 2008 no XenosRecomp, que até agora só viu 7645, 21076 e 21173. São 284
shaders, uma corrida curta no PC chega para saber.

## 4. Estimativa (o port do Renut para Android já existe no ProjectSwordMan: M1/M2 do MGR feitos a 23/10)

| Marco | Trabalho no Burst Limit | Dias realistas |
|---|---|---|
| **M1**: compila no Dragon-Burst, Renut escolhido por cvar, hooks e guardas do BL | (a) layout por jogo no Renut: voltar aos valores do N&B (todos provados acima) + tabela da cauda do BL, ~60 constantes em `d3d.h`, `d3d_native.h`, `device.cpp`, `draw.cpp`, `sa2_renut_pacing.cpp`: **1,5–2 d**; (b) 19 hooks com assinatura igual a apontar para o BL + criação nova em `820B7230` + InsertCallback (`820AE868`) + PS (`820A24E8`, `820F9CF8`) + `820AEAF0` sem efeito + guardas: **1,5–2 d**; (c) ligar BeginTiling/EndTiling do N&B (`820BA5A8`/`820BAA48`) e acertar os offsets: **1 d**; (d) extração do NSH + XenosRecomp: **0,5–1 d**; (e) trazer o CMake/glue do ProjectSwordMan para o Dragon-Burst (o build tem patches próprios): **1–1,5 d** | **5–8 dias** |
| **M2**: arranca até ao título no RP5 | primeiros arranques: callbacks e Swap (thread `820B8808` que também chama Swap), tiling com MSAA (EDRAM/resolve por tile), front buffer automático, `VK_ERROR_DEVICE_LOST` como no MGR | **1–2 semanas** |
| **M3**: gameplay | cobertura de shaders, efeitos (`NFX`), pós-processamento por resolve, desempenho, Mali (BCn/GS) como no MGR | **3–6 semanas** |

## 5. O que bloqueia o M1

1. **Layout do device:** o Renut de hoje tem os offsets privados do SA2/MGR em constantes espalhadas, alguns literais
   do N&B no meio (tiling) e endereços absolutos do N&B. Para o BL é preciso parametrizar por jogo: valores do N&B até
   ~0x4158 (provados), uma tabela própria para a cauda (21540…22300, 0x5780). Ficam por provar `0x541C` (o teste do
   `SetFence`), os tipos de recurso 1/2/3/16–20 e o Bind dos vertex shaders.
2. **LTCG:** CreateDevice (`820F92F8`), InsertCallback (`820EA188`), XGRegisterPixelShader (`820A24E8` + `820F9CF8`),
   IsFencePending e D3DDevice_Release (`820EA5B0`) não são funções. A criação do Renut (`Sa2CreateDevice`, que chama o
   CreateDevice original com tipo 2) não se transporta: é preciso um hook novo em `820B7230` sobre um device que o jogo
   já alocou. O hook de callbacks troca de assinatura. Pode haver outro D3D embutido no jogo que só aparece em execução,
   por exemplo pacotes escritos em linha que dependam de fences reais.
3. **Shaders e tiling:** a cache depende de corrigir o leitor do CPK (base em `ContentOffset`, CRILAYLA sem magic) e de o
   XenosRecomp aceitar microcode 6683, o que ainda não foi testado. O tiling predicado é um caminho do Renut que o MGR
   nunca exercitou no Android.

Não bloqueia: os 19 hooks de assinatura igual, as fences, o anel, os draws, o clear, o resolve e o Swap. A lógica é a
mesma do N&B, para o qual o Renut foi escrito.
