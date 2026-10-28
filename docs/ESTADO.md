# Estado do Dragon Burst

Port Android do [DBZ Burst Limit Recompiled](https://github.com/iExplosiveRage/DBZ-Burst-Limit-Recompiled)
(iExplosiveRage), feito sobre a infraestrutura do Dragon Fury (DBZ3-Android). Aparelhos de teste:
**Retroid Pocket 5** (Snapdragon 865 / Adreno 650) e **Anbernic RG Cube** (Unisoc T770 / Mali-G57).

O que cada recurso do PC vira no Android: [COMPATIBILIDADE.md](COMPATIBILIDADE.md).

**Release atual: 0.1.2** (17/10/2026; a 0.1.0 foi a primeira pública, patches 0000–0098 + jogo 0001–0004). A 0.2.0
(Renut, código do jogo cifrado) está preparada no branch `renut/burst-limit` (28/10), por publicar. Notas para
os jogadores: [CHANGELOG.md](../CHANGELOG.md); texto da release no GitHub: [release/v0.1.0.md](release/v0.1.0.md);
como publicar: [Publicar uma release](#publicar-uma-release). O changelog de desenvolvimento que estava no
CHANGELOG até a 0.1.0 está no [fim deste arquivo](#changelog-de-desenvolvimento-até-a-010).

## POCO X3 Pro nao arranca: kernel 4.14 ignora MAP_FIXED_NOREPLACE (0098, 17/10)

**POCO X3 Pro** (M2102J20SG "vayu", SM8150 / Adreno 640, Android 13, 0.1.1, 720p): o jogo nao arranca.
Depois do VdSwap #5, `Unhandled guest access violation: write of guest 0x706E0000` em loop (213 mil
linhas) na thread 0xF8000028; o watchdog diz que o loop do jogo nunca arrancou. Os outros aparelhos
(RP5, S20 FE, RG Cube, M54, Odin 3) arrancam com o mesmo build.

- **O que e 0x706E0000.** Heap v40000000 (paginas de 64 KB), na faixa das pilhas das threads
  (0x70000000-0x7F000000, `XThread::AllocateStack`). Endereco alinhado a 64 KB = uma pagina de guarda.
  Os registos batem com uma chamada a `ExCreateThread` da thread principal: r3=0x7018F670 (ponteiro do
  handle, na pilha), r4=0 (tamanho de pilha por omissao), r5=0x7018F6D4 (ponteiro do id), r9=0 (flags);
  lr=0x822A4FD8 e o retorno dessa chamada no jogo (sem o codigo gerado aqui nao ha nome para a funcao).
  A falta e no `memory()->Fill(..., 0xBE)` do host que enche a pilha nova: a pilha foi posta onde antes
  havia a pilha de uma thread que ja saiu, e as paginas de guarda dessa (PROT_NONE) ficaram sem acesso.
- **Porque so neste aparelho.** O SDM855/860 usa kernel **4.14**; o MAP_FIXED_NOREPLACE so existe a partir
  do 4.17 (SM8250 = 4.19, M54 = 5.15, etc.). O 4.14 ignora a flag e trata o endereco como sugestao: com o
  endereco ocupado (a arena do guest esta toda mapeada) o mmap devolve OUTRO endereco em vez de EEXIST.
  O `AllocFixed` devolvia esse bloco perdido como sucesso e nunca chegava ao mprotect do caminho EEXIST,
  por isso nenhum commit mudava a protecao do host. Como a arena e mapeada RW, quase tudo funcionava; so
  falha memoria que alguem pos PROT_NONE e que volta a ser alocada (guardas de pilhas libertadas; as
  janelas MMIO de GPU/XMA tambem nunca ficavam PROT_NONE neste kernel). Nao e RAM, page size, MIUI nem
  colisao de enderecos: a arena mapeou no 0x100000000 como nos outros.
- **Porque entrava em loop.** O callback devolvia false para faltas fora das heaps fisicas e o
  `ExceptionHandlerCallback` POSIX, sem ninguem tratar, simplesmente retornava: a instrucao corria outra
  vez e voltava a falhar, para sempre.
- **0098** (`0098-fixed-noreplace-fallback`): o `AllocFixed` deteta o endereco diferente, desfaz o mapa
  perdido e segue o caminho EEXIST (mprotect), com um aviso unico "the kernel ignored
  MAP_FIXED_NOREPLACE"; falhas de mprotect no commit passam a ir para o log com errno e a linha do
  `/proc/self/maps`. Falta de memoria do guest sem dono: uma linha `FATAL guest memory fault` (regiao do
  guest, estado/protecao, linha do maps, se o kernel respeita a flag), caixa de mensagem do SDL (PT/EN) e
  abort; os sinais que ninguem trata voltam ao handler original (tombstone) em vez de repetir. No arranque,
  depois de "Guest memory arena mapped": `Host memory: RAM ... MB (available, free, swap), kernel ...,
  page ..., guest arena ..., MAP_FIXED_NOREPLACE honoured: ...`.
- **O proximo log do POCO tem de mostrar:** `Host memory: ... kernel 4.14.x ... MAP_FIXED_NOREPLACE
  honoured: false`, um `AllocFixed: the kernel ignored MAP_FIXED_NOREPLACE` e passar do VdSwap #5 (VdSwap
  #60, `XamNotifyCreateListener`). Se aparecer `FATAL guest memory fault`, a linha diz o estado da pagina.
  Como as janelas MMIO passam a ficar protegidas neste kernel, pode aparecer outro problema logo a seguir.

## Otimizacoes para Mali ligadas fora das Adreno (17/10 19:50)

Autoteste de 17/10 14:35 no **Galaxy M54** (Mali-G68, 540p, 0.1.1): as tres chaves novas sairam "igual"
(`com copia por desenho no atalho resolve -> textura` 0,36%, `com resolves servidos pela textura do passo 4`
0,38%, `com escritas sem leitor neutras` 0,41%; ruido 0,38%). GPU por estado (linha perf dentro de cada
estado, ha deriva termica): ~36-40 ms nos estados vizinhos, 32,6 / 29,8 / 29,4 ms com cada uma ligada
(resolve 12,6 / 10,5 / 11,5 ms). O launcher passa a escrever `dbz3_direct_resolve_draw`,
`dbz3_rit_serve_resolves` e `dbz3_resolve_dedupe_unread_neutral` = true fora das Adreno (Adreno continua
false ate ser testado). No mesmo autoteste a composicao do brilho tambem deu igual no Mali.

## M54 a 540p: passo 4 banido, resolves caros no Mali (0095–0097, 17/10)

Log de 17/10 13:35–13:40 num **Galaxy M54** (Exynos 1380 / Mali-G68 MP5, driver 0x9801000, 75%,
frame_rate 30, build com 0093+0094, efeitos do jogo todos desligados): autoteste todo "igual"; luta a
24–27 FPS, GPU 33–40 ms (cp_wait 8–9 ms, limitado pela GPU): desenho 17–18 ms/14 passes, resolve
11–12 ms, transfer 0,9, outros 2–3; `resolves=19.0/f (atalho=14-16, sem_memoria=14-16) 960x544:12
480x272:6 768x768:1`, `cargas=0`. Passo 4: `5 assinaturas (2 prontas, 3 banidas)`, so uma linha de
ban no log (75696CA3CA281BEC, "resolvido outra vez"). Passo 3: `0 sem leitor saltados`.

- **Porque o passo 4 bane 3 de 5 (nao e por ser abaixo de 1x).** As contas de tamanho do 0091
  (960x544 do resolve contra 960x540 da textura, "o resolve tem de cobrir a textura") passam: nenhum
  `area`/`texturas` no "inicio falhou". O S20 FE a 720p (1x) baniu 9 de 12. A causa sao os **efeitos
  desligados**: o draw override salta o quad do efeito, mas o resolve dele continua e copia o que o
  render target tinha (a imagem do passe anterior). Se esse passe anterior foi desenhado direto na
  textura (passo 4), o render target nao a tem e a assinatura e banida. Com a captura de 16/10 07:07
  (RG Cube, efeitos desligados) e os shaders do override, os 5 passes do M54 sao: f592 (composicao,
  -> 0E1F1000, pronto), cac2 (-> 0DE59000; o motion blur 1D25 e saltado e o resolve dele copia o
  render target para 0E1F1000 -> **banido**, "resolvido outra vez"), ec9b a 640 (-> 0FF19000; os dois
  desfoques do DOF 0A9E saltados, os resolves para 10009000 e 0FF19000 -> **banido**), aa37 (->
  0DE59000; o filtro suave 04FC saltado e o quad seguinte, com mistura, carrega o render target ->
  **banido**, "passe que o carrega") e a copia final (-> 0C000000, pronto). No RP5 a 1x com DOF e
  brilho ligados esses quads sao desenhados e nada disto acontece. Num cenario com o passe a55b
  (-> 0E589000) havia ainda um ban falso: o resolve da profundidade linear (32_FLOAT na mesma base,
  quad saltado, feito pelo passo 2) contava como "resolvido outra vez".
- **Porque "sem leitor" e 0 (nao e a chave guest/host).** O passo 3 marca as entradas pelo inicio do
  destino (= base, igual a 1x e abaixo) e as leituras pela base da textura: nao ha tamanho na chave.
  Os destinos que morriam (10009000 no Cube a 75% sem os passos, 0E921000 no RP5) agora: 10009000 e
  0FF19000 sao escritos pela reducao 640 por compute (os 3 destinos depois dos desfoques saltados),
  que serve esses resolves antes do passo 3 (so 100F9000 e lido; ~0,3 ms de escritas inuteis, fica);
  e a profundidade 0E921000, nos cenarios em que o jogo a resolve duas vezes por frame, tem a
  primeira escrita sobrescrita sem leitor nenhum, e isso recomecava a contagem dos 120 a cada frame.
- **O que custam os 11–12 ms de resolves.** No Mali as texturas 8888 nao tem STORAGE (vista SNORM;
  e o STORAGE tiraria a AFBC), por isso o atalho faz compute (render target -> buffer) +
  vkCmdCopyBufferToImage (`atalho resolve->textura (buffer) ativo`): ~8 MB de trafego por resolve de
  960x540 e duas barreiras, ~0,7 ms cada.

Patches (os caminhos novos ficam **desligados por padrao** ate o aparelho confirmar; o autoteste liga
cada um, "com ..."):

- `0095-draw-copy-to-texture` (`dbz3_direct_resolve_draw`, false): o atalho escreve as texturas
  sem STORAGE com um desenho - render pass proprio com a vista da textura como alvo de cor (0074),
  triangulo de ecra inteiro, `texelFetch(render target, gl_FragCoord)`; troca R/B pela vista
  B8G8R8A8 como no passo 4. ~4 MB por resolve, sem buffer. Exato (UNORM -> float -> UNORM). Se nao
  der (textura nao renderizavel, vista), volta ao buffer: `copia por desenho para a textura nao
  feita (N; ...)`. Tambem: a linha do arranque diz "exatos" a 75% (o 0094 usava o passo 1 como
  sinal; agora `Dbz3PostSizesExactAtPercent`).
- `0096-rit-ban-reasons-and-serve`: **cada ban tem uma linha** (uma por assinatura, com o motivo, o
  destino, os acertos e, em "resolvido outra vez", o resolve que o causou); o resumo do passo 4 ganha
  `banidas por: ...` e os resolves servidos. O resolve da profundidade linear deixa de banir.
  `dbz3_rit_serve_resolves` (false): um resolve do render target que ficou sem a imagem, sem nada
  desenhado desde o passe (nenhum passe, transferencia, compute que substitua quad, resolve saltado
  ou escrita no destino), com o mesmo retangulo/formato/endian/troca, recebe a imagem da textura do
  passe por copia por desenho (o destino fica com os mesmos bytes; so com o resolve sem memoria); para
  o proprio destino nao faz nada. Ao ligar ao vivo, as banidas por "resolvido outra vez" voltam a
  aprender. Esperado no M54: 4 prontas (cac2 e ec9b passam), 1 banida (aa37, "passe que o carrega":
  precisaria de repor o render target, custo igual ao resolve).
- `0097-dedupe-unread-writes`: linha de diagnostico do passo 3 a cada 3000 frames,
  `resolves (lidas/sem leitor/so saltados): <destino> L/U/S seq N leitor <PS>` (o ultimo leitor: hash
  do PS, 1 = apresentacao, 2 = compute). `dbz3_resolve_dedupe_unread_neutral` (false): uma escrita
  sem leitor nenhum nao recomeca a contagem (a profundidade resolvida duas vezes deixa de o ser).
- Autoteste: tres linhas novas, `com copia por desenho no atalho resolve -> textura`, `com resolves
  servidos pela textura do passo 4`, `com escritas sem leitor neutras (passo 3)`.
- Validado fora do aparelho: verificacao de sintaxe; patches aplicados sobre o 0094. O desenho da
  copia e a mesma conta texel a texel do compute (mesma origem, mesmo (0,0), mesma troca).
- **Ganho esperado no M54 (GPU 33–40 ms):** copia por desenho ~8–10 resolves de cor por frame,
  ~0,7 -> ~0,35 ms cada: **-3 ms**; servir pela textura: tira os resolves de 0DE59000 e 0FF19000
  (2x) e troca dois atalhos por copias: **-1 a -1,5 ms**; escritas neutras: a profundidade (~0,7 ms)
  so nos cenarios com a dupla escrita. Total ~-4 a -5 ms -> **~29–35 ms**: 30 FPS na maior parte da
  luta, nao garantido. O mesmo vale para o RG Cube.
- A ver no M54/Cube (540p): no arranque `... (tamanhos de 1280x720 e 640x360 exatos a esta
  percentagem)`; sem nada ligado, uma linha `dbz3: passo 4: assinatura ... banida (<motivo>): ...` por
  banida e `passo 4: banidas por: resolvido outra vez 2, passe que o carrega 1; ...`; no autoteste,
  as tres linhas novas **igual** e o GPU ms de cada. Com as tres em `true` no burstlimit.toml:
  `copia por desenho para a textura ativa (...)` e `copia por desenho para a textura: 1 (960x540,
  troca R/B 1)`, nenhum `copia por desenho ... nao feita`; `resolve do render target servido pela
  textura do passe (0DE59000 -> 0E1F1000, 960x540, ...)` e `resolve para o proprio destino 0FF19000
  sem copia`; `passo 4: 5 assinaturas (4 prontas, 1 banidas)`, `banidas por: passe que o carrega 1;
  resolves servidos pela textura N`; perf com `resolve=` ~6–8 ms (as contagens `960x544:12` ficam: contam todos os resolves do jogo, tambem os saltados); a linha
  `resolves (lidas/sem leitor/so saltados)` com 0E921000 (diz quem a le, se nao morrer).

## Abaixo de 1x sem a imagem final direta (0094, 17/10 17:20)

Autoteste de 17/10 13:06 num **Samsung Galaxy M54** (SM-M546B, Exynos 1380 / Mali-G68, driver 0x9801000,
75%, build com 0091–0092 sem o 0093): a imagem de referencia (00/01) saiu espremida numa faixa fina no topo
da tela; os outros estados sairam com a imagem certa, por isso tudo deu "DIFERENTE" (78%). Com o 0091 todos
os passos ligaram a 75%. O **0094** tira o passo 1 (imagem final direta) e a composicao do brilho da lista dos
que correm abaixo de 1x (`Dbz3NativeStepsAtPercent`); continuam a profundidade linear, o contorno, a reducao
e o desfoque 640, o passo 4 e o atalho resolve -> textura. Esse zip nao tinha linhas perf
(`dbz3_perf_logging = false`). Falta: autoteste a 75% no RG Cube e no M54 com o 0094.

## Composicao do brilho desligada de novo (0093, 17/10 17:00)

Autoteste de 17/10 12:51 num **Samsung Galaxy S20 FE** (SM-G780G, SM8250 / Adreno 650, driver Qualcomm
0x801F6000, build com 0090–0092, 720p): a imagem de referencia saiu azul/laranja (gradiente por cima da
cena). Ruido 1,0%. Com a composicao do brilho por compute desligada, ou sem o passo 4, ou sem RT diretos, ou
"TUDO desligado", a imagem fica certa (66% dos pixels diferentes da referencia errada). Nesse aparelho a
reducao 640 nunca aprendeu (`reducao 640 ainda nao (2)`, 15 vezes) e o desfoque por compute nunca ligou; a
composicao ligou (`composicao do brilho por compute ativo (0E1F1000 <- 0DE59000 + 100F9000 ...)`, 0 falhas).
FPS 58–60 na luta. Ate perceber a causa (aparelho/driver ou regressao do 0091 a 1x, que ainda nao foi
testado no RP5), o **0093** volta o padrao de `dbz3_bl_native_glow_composite` para false e o launcher
escreve false. Falta: autoteste no RP5 com 0091–0093 e captura no S20 FE.

## Base do port (patches 0000–0082)

Feito nesta base:

- Pipeline reprodutível: `tools/versions.env` fixa o commit do BL (v5.2, `bb60bc1`) e do SDK dele
  (`465f9ef`, branch `burstlimit` do fork do iExplosiveRage, base ReXGlue v0.10).
- **Patches do SDK** (`patches/`, aplicados em `external/burstlimit/thirdparty/rexglue-sdk`):
  - `0000`–`0048`: os 49 patches do Dragon Fury refeitos sobre o SDK do BL (o BL mexe em muitos
    arquivos do SDK: as zonas que conflitavam foram resolvidas à mão; o `0000` traz a telemetria base
    `dbz3:` que o DBZ3 tinha no overlay do jogo).
  - `0049-vulkan-texture-replacement`: o envio das texturas de substituição no Vulkan (no PC só o D3D12
    tinha). Faz os mipmaps quando faltam, copia por um buffer de staging e solta-o no próximo submit.
  - `0050-vulkan-draw-overrides`: os `draw_overrides` do BL no Vulkan (ligar/desligar profundidade de
    campo, glow, motion blur, filtro suave; esconder o HUD da câmera livre).
  - `0051-posix-online-sockets`: o XNet (sockets do jogo) compila e funciona em POSIX.
  - `0052-android-overlay-fonts`: fontes do sistema Android para o menu do jogo / overlay.
  - `0053-cvar-gpu-debug-log-draws`: a cvar `gpu_debug_log_draws` só era definida no D3D12, mas o cache de
    texturas comum usa-a (link falhava fora do Windows).
  - `0054-pulse-event-registered-waits`: o `PulseEvent` do runtime POSIX perdia o aviso quando a
    thread estava entre soltar o mutex e esperar (`SignalAndWait`); o Burst Limit usa mutex + evento
    como variavel de condicao e encravava no arranque (o PC usa o Windows, que faz os dois passos de
    uma vez). Agora a espera regista-se antes de soltar o mutex e o pulso acorda quem esta registado.
    Teste de stress do padrao: o codigo antigo encrava em <3000 rondas, o novo passa 1 milhao.
  - `0055`/`0056`: diagnostico (`dbz3: gpu-diag` a cada 2 s, `dbz3: VdSwap #n`, e aos 10/20 s as
    threads do guest presas numa espera, com o objeto e quantos sinais recebeu).
  - `0057-auto-reset-event-handoff`: a causa real do ecra preto. O Burst Limit usa UM evento
    automatico para as duas direcoes do aperto de mao thread principal <-> worker (mutex + estado +
    `SetEvent`). No NT, `SetEvent` com alguem a espera entrega o sinal a essa thread; no runtime
    POSIX o sinal ficava "no ar" e a thread principal, que volta logo a esperar no mesmo evento,
    roubava-o. Agora a espera entra numa fila e o sinal vai para o primeiro da fila (Set e Pulse).
    Teste do padrao exato: o codigo anterior encrava 3/3 vezes logo na 1.a ronda; o novo passa
    200 mil rondas (Set e Pulse), mais testes de regressao dos eventos.
  - `0058-capture-resolve-readback`: a captura `.dfc` grava a imagem de cada resolve (lida de
    volta da GPU no 1.o frame capturado), para ver em que passe a imagem se perde.
  - `0059-bl-clear-to-1x`: o atalho "Clear direto em 1x" do Dragon Fury reconhece os shaders do
    Clear() do XDK do Burst Limit (menos 4 transferencias 4x → 1x por frame).
  - `0060-swap-diagnostics`: o fetch constant do frontbuffer de cada swap no log e na captura.
  - `0061-capture-texture-events`: cargas e invalidacoes de texturas na captura.
  - `0062-capture-draw-outcome`: onde cada draw da captura terminou em `IssueDraw`.
  - `0063-ignore-fetch-lod-bias-diagnostic`: cvar de diagnostico que ignora o LOD bias.
  - `0064-spirv-texture-exp-adjust-word3`: o `exp_adjust` das texturas lido da word 3 (era da word 4,
    o LOD bias): o preto da luta.
  - `0065-bl-native-present`: render nativo, passo 1 — a imagem final da luta apresentada direto.
  - `0066-bl-native-linear-depth`: render nativo, passo 2 — profundidade linear por compute.
  - `0067-bl-native-linear-depth-fix`: k_32_FLOAT no `Dbz3FindResolveTexture` (sem isto o passo 2
    nunca liga) e log da razao. Regra: patch ja publicado nunca se altera — o `apply-patches.sh`
    marca-o como aplicado e a versao nova nao entra (foi o que aconteceu a 1.a correcao do 0066).
  - `0068-bl-native-post-lite`: render nativo, passo 3a (draws nulos, resolves repetidos e sem leitor,
    preparacao do motion blur saltada com ele desligado).
  - `0069-resolve-dedupe-skipped-readers`: "sem leitor" passa a ser "so lido por draws saltados"
    (no RP5 o 100F9000 do brilho ficou 2 s sem leitor entre rounds e voltou a ser lido).
  - `0070-discard-full-overwrite-load`: passe que comeca com um quad opaco a cobrir o scissor
    nao carrega a cor (DONT_CARE, area de render = scissor): todos os passes do pos-processamento.
  - `0071-render-into-texture`: passo 4 - passe que acaba num resolve de cor 8888 para uma textura
    desenha direto nela (aprendido por assinatura do quad que abre o passe, 3 frames iguais); desiste
    e bane a assinatura se outro resolve, outro alvo, a textura lida no passe ou o render target
    voltar a ser preciso. So com dynamic rendering (o driver do RP5 e Vulkan 1.1).
- Autoteste (16/10 13:40, RP5 e RG Cube, efeitos ligados): nenhuma otimizacao muda a imagem.
  RG Cube: referencia 43,6 ms (22,4 FPS); passo 1 vale 1,4 ms, passo 2 4,5 ms, passo 3 ~1,5 ms;
  sem RT direto 79 ms, sem resolve sem memoria 69 ms. Desenho 28 ms em 16 passes, resolves 13,7 ms
  em 17,5/frame - o passo 4 ataca os resolves.
- Autoteste 16/10 14:06 (RG Cube): com dbz3_rt_direct LIGADO o Goku (P1, no ar) nao aparece e o
  fundo fica com outro desfoque - no RP5 nao. O passo 4 nao chegou a ligar (sem linha no log).
  `0072`: as partes do RT direto (restauros, restauro da profundidade, Clear 1x, uploads, transicao
  do resolve) passam a mudar ao vivo e entram no autoteste; o passo 4 compara tambem a base original
  do resolve e o log diz por que nao liga.
- Autoteste 16/10 20:04 (RG Cube, build 0072, restauros ignorados desligados no Mali): o Goku ja
  aparece, mas com dbz3_rt_direct LIGADO o Krillin (P2) fica meio transparente com o cenario por
  cima e o fundo mais desfocado (14,7%); nenhuma das partes separadas muda isso (Clear 1x: 1,75%).
  GPU 64 ms (15 FPS, efeitos todos ligados); sem RT direto 84-105 ms. Passo 4: `aprender falhou:
  resolve diferente 6055` - a captura mostrou que TODOS os resolves 8888 do pos tem
  `copy_dest_swap = 1` (troca R/B) e o passo 4 exigia 0.
  `0073-rit-bgra-rt-direct-switches`: o passo 4 aprende a troca e desenha numa vista B8G8R8A8 da
  textura (MUTABLE_FORMAT + lista de formatos; pipeline com a cor 0 em B8G8R8A8, bit
  `dbz3_color_0_bgra` na chave do passe; se o pipeline ainda compila, o passe vai pelo caminho
  normal sem banir). As duas partes do RT direto sem chave ganham-na:
  `dbz3_rt_direct_drop_transfers` e `dbz3_rt_direct_clear_siblings`; cada par de transferencia
  saltada aparece uma vez no log. O autoteste passa a usar a config atual como referencia
  (`sem X` / `com X`).
- Autoteste 16/10 20:30 (RG Cube) / 20:32 (RP5), build 0073: nada muda a imagem nos dois (o Krillin
  transparente nao voltou nesta cena; transferencias saltadas desligadas: 0,6% no Mali, igual).
  Mali ~21 FPS na referencia, sem RT direto 17 FPS, tudo desligado 13 FPS. Passo 4 no Mali:
  `18 assinaturas (18 prontas)` mas `nao renderizavel 15402` - as texturas do pos nao tem STORAGE
  (8888 tem a vista SNORM), so essas recebiam COLOR_ATTACHMENT. RP5: passo 4 nao corre (sem
  dynamic rendering), como esperado.
  `0074-rit-non-storage-textures`: qualquer 8888 2D sem mips pode ser alvo de cor (lista de formatos
  {UNORM, SNORM, B8G8R8A8}, se o SNORM tambem for alvo de cor); vistas proprias de alvo de cor; o
  log separa "nao renderizavel" de "vista".
- Autoteste 16/10 21:08 (RP5) / 21:12 (RG Cube), build 0074: **passo 4 funciona no Mali** - 18
  assinaturas, 14 prontas, 13551 passes desenhados direto na textura, imagem igual. Mali ~24 FPS /
  40 ms de GPU com tudo (o passo 4 vale ~4-5 ms). No RP5 (cena com o fundo longe) fazer todas as
  transferencias muda 22% da imagem (fundo nitido em vez de desfocado) - o mesmo que desligar o modo
  direto: alguma transferencia saltada faz falta para a profundidade/desfoque. No Mali fazer todas
  custa ~20 ms. Pares saltados: D832p13->D720p16, C0p13->D720p16, D0p16->C0p13/p16, C0p16->D0p16,
  C0p16<->C14 (profundidade linear), C0p16<->C0p8, C0p13<->C0p16.
  `0075-rt-direct-keep-kinds`: `dbz3_rt_keep_dd/cd/dc/cc` mantem so um tipo de transferencia; o
  autoteste testa cada um ("com transferencias X->Y").
- Autoteste 16/10 22:17 (RP5, Qualcomm) / 22:19 (RG Cube) / 22:22 (RP5, Turnip v26.3.0-R6),
  build 0075: nos dois primeiros so `com transferencias profundidade->cor` corrige a imagem
  (24% / 11,5%, o mesmo que sem modo direto). Custo: RP5 ~3 ms (22 -> 25 ms), Mali ~8 ms
  (48 -> 56 ms). Pares D->C saltados: D0@0p16 -> C0@0p13/p16, D0@720p16 -> C0@0p13.
  `0076-keep-depth-to-color-per-driver-cache`: `dbz3_rt_keep_dc` ligado por omissao (e no
  launcher); a cache de pipelines do driver passa a um ficheiro por driver
  (`vulkan_driver_pipelines_<driverID>_<versao>.bin`) - o Turnip estava a ler a da Qualcomm.
- **Turnip no RP5 (22:22):** imagem toda errada em todos os estados, ate "TUDO desligado" (fundo cor
  de azeitona, lutadores com cores de normais, riscas nas texturas; sem resolve sem memoria fica
  pior). Geometria certa: o problema e das texturas do jogo. E o mesmo que o Dragon Fury viu no
  RP5 com Turnip a 13/10 (ESTADO dele: "texturas e cores erradas, riscas"). So no Turnip: memoria
  do guest em 4 storage buffers (128 MB) e texturas BC nativas. App: com driver alternativo,
  memoria num so buffer de 512 MB sempre e, nos Adreno 6xx, BC descomprimido (como no driver da
  Qualcomm e no Mali) - bits 1 e 8 das "Correcoes do Turnip", agora automaticos.
- Autoteste 16/10 22:45 (RP5 Turnip, flags 9) / 22:46 (RP5 Qualcomm) / 22:52 (RG Cube): Qualcomm e
  Mali certos (so "sem transferencias profundidade->cor" muda: 19,6% / 15,3% - confirmado). Turnip:
  com TUDO desligado a cena aparece quase certa mas com R e B trocados; com tudo ligado, fundo cor de
  azeitona. Causa provavel: as 8888 com MUTABLE_FORMAT + lista {R8G8B8A8, B8G8R8A8} do passo 4
  (0073/0074), presentes em todos os estados (o Turnip so foi testado com elas).
  `0077-no-rit-textures-on-turnip`: no Turnip (driverID 18) as texturas sao criadas como no Xenia
  (`dbz3_rit_textures_turnip = false`); o passo 4 fica desligado nesse driver.
- Pesquisa do Turnip (OptXbox docs/en/TURNIP.md): suporte por geracao, builds, TU_DEBUG, limites (128
  MB de storage buffer em todas as geracoes, sem fragment shader interlock), emuladores. App:
  `drivers/TurnipSupport.java` (tabela SOC_MODEL -> Adreno -> estavel/experimental/sem suporte,
  mostrada na aba Drivers); TU_DEBUG automatico com Turnip (sysmem no A830 e A710/720/722;
  FD_DEV_FEATURES no Samsung A740); buffer unico e BC descomprimido voltam a ser so manuais; protecao
  contra driver que fecha o jogo no arranque (o seguinte usa o do sistema e a aba Drivers avisa);
  linha "GPU: ..." no logcat.
- Autoteste 16/10 23:38 (RP5 Qualcomm) / 23:44 (RP5 Turnip R6, com 0077) / 23:49 (RG Cube): Qualcomm e
  Mali certos. Turnip continua errado: referencia com o fundo cor de azeitona e lutadores com cores
  de normais; sem "resolve sem memoria" as texturas saem em riscas (tiling errado). O 0077 nao era a
  causa. Igual ao Dragon Fury no RP5 com Turnip (R5, R6, PurpleVK T30): o caminho do Xenia neste SDK
  com o Turnip do Adreno 650, independente das otimizacoes, e nao e GMEM/LRZ/UBWC/barreiras (o DF
  testou). O Purple (vulkan.purple.so) fechou o jogo no arranque e a protecao voltou ao driver do
  sistema (funcionou). Proximo: builds estaveis antigos recomendados para o 865 (K11MCH1 v24.3.0 R9,
  MrPurple T19) e as opcoes do compilador ir3 (IR3_SHADER_DEBUG nopreamble/nouboopt/nofp16, bits
  128/256/512 das Correcoes do Turnip) - se for um shader mal compilado, uma delas muda a imagem.
- 17/10 00:06 (RP5 Qualcomm) / 00:12 (RP5 Turnip v25.3.0 R11, Mesa 25.3): Turnip 25.3 com o mesmo
  defeito (fundo azeitona, lutadores com cores de normais; sem "resolve sem memoria" 90% diferente)
  - nao e da versao 26.3.
  **Bisect do Turnip** (aba Drivers): cada arranque do jogo com driver alternativo aplica um passo de
  `TurnipSupport.BISECT` (0 base, 1 sem dynamic rendering/stencil export, 2-4 ir3 nopreamble/nouboopt/
  nofp16, 5 sysmem, 6 noubwc+nolrz, 7 BC descomprimido, 8 buffer unico 512 MB, 9 sem robustBufferAccess/
  robustness2, 10 flushall+syncdraw, 11 depth float32), escrito em logs/turnip_bisect.txt e no topo do
  autoteste.txt.
  `0078-rit-render-pass-dc-pairs-mask-robust`: passo 4 tambem sem dynamic rendering (render pass +
  framebuffer proprios para a vista da textura, so passes de cor 0; framebuffers destruidos quando a
  submissao termina) - para o RP5 com o driver da Qualcomm; os pares profundidade->cor com chave
  propria (`dbz3_rt_dc_base0_to_pitch13/16`, `dbz3_rt_dc_other`, no autoteste); `vulkan_mask_features`
  aceita `robustBufferAccess`.
- Autoteste 17/10 00:32 (RP5 Turnip, sem o bisect ligado: nenhum turnip_bisect.txt) / 00:34 (RG Cube) /
  00:42 (RP5 Qualcomm), build 0078: **passo 4 funciona no RP5 com o driver da Qualcomm** (106572 passes
  direto na textura, imagem igual) e no Mali (27825). RP5 com efeitos: ~53 FPS / 18 ms de GPU (antes
  22-25 ms). Dos pares profundidade->cor so **D@0 -> C pitch 16** muda a imagem (23,7%); os outros dois
  nao. `0079-only-needed-depth-to-color-pair`: `dbz3_rt_dc_base0_to_pitch13` e `dbz3_rt_dc_other`
  desligados por omissao. Turnip: o congelar da luta nao segurou a imagem (o Goku mexeu-se entre fotos)
  e a imagem continua a do buffer de normais.
- 17/10 (offline, sem build): **passo 3b, primeiro efeito** - `0080-bl-native-contour`. Mapa do custo
  do pos no RP5 (Qualcomm, frame 1 das capturas de 16/10 07:32/07:48/08:00, as duas cadeias do frame):
  contorno ABBF 0,89-0,93 ms de draw + 1,16-1,19 de resolve; contorno por ID na cena 404B49 0,75;
  desfoques 97906B 0,84 + 0,60; composicao F59256 0,46-0,48 + 1,26-1,97; reducao B0133E 0,33 + 0,30.
  Escolhido o **ABBF (mascara do contorno dos lutadores -> 0F0B9000)**: o draw mais caro do pos e com
  saida discreta. O PS (desmontado com o `translator_disasm` do SDK) le o ID (floor(canal*255/64)) e a
  profundidade linear dos 4 vizinhos; se os IDs nao sao todos iguais escreve a cor (c0/c1/c2) do ID
  do vizinho mais perto da camara, senao 0 (profundidade fora da imagem = 0, borda preta).
  Referencia em numpy (`tools/ref_contour.py`) contra os resolves das capturas: **17 draws em 11
  capturas (RP5 Qualcomm, RP5 Turnip, RG Cube), 0 pixeis diferentes**. No SDK: com
  `dbz3_bl_native_contour` (ligada, HotReload) o quad e saltado e o resolve seguinte e um compute
  (le a profundidade so nas arestas, buffer + copia para a textura, porque as 8888 do pos nao tem
  STORAGE); reconhecimento exato (alvo, mistura, constantes do VS e do PS, os 4 vertices, fetch
  constants), aprende o destino no 1.o frame (antes do passo 4, que ficaria com esse resolve), corre
  fora de passes (serve o render pass do RP5). Precisa do passo 2 (a 0F451000 tem de estar em dia).
  No autoteste: "contorno por compute (passo 3b)"; no launcher vai com o pos-processamento enxuto.
  A ver no aparelho: `contorno por compute ativo`, o contador (~2 passes/frame, 0 falhas), imagem
  igual ligado/desligado (contorno e o desfoque dele, especiais com `c1` colorido) e o ganho em ms.
  - jogo `0004-android-app-menu`: sem o menu do PC no Android (o do app substitui-o).
- **Patches do jogo** (`patches/game/`, aplicados em `external/burstlimit`):
  - `0001-android-portability`: caminhos (pasta da app = "pasta do exe"), HTTP por JNI
    (`android_http.cpp` → `NativeHttp.kt`), sons de menu pelo SDL3, saves, instalador/tela inicial e
    Discord desligados, itens só de PC escondidos do menu do jogo.
  - `0002-android-pause-point`: ponto de pausa usado pelo launcher (menus de toque com o jogo parado).
  - `0003-android-libcxx-fixes`: diferenças do libc++ do NDK (ex.: `file_time_type` em `__int128`).
- Verificado aqui (Linux x86-64): os 55 patches aplicam limpos num clone novo e o resultado é idêntico
  ao branch de trabalho; o SDK com os patches compila no host (os únicos erros são do `std::expected`
  do libstdc++ do host nas ferramentas `rexglue`/codegen, que no PC com clang 20 + libc++ não aparecem);
  `vulkan/texture_cache.cpp`, `vulkan/command_processor.cpp`, `xsocket.cpp`, `xam_net.cpp` e
  `overlay_text.cpp` (com `REX_PLATFORM_ANDROID`) compilam. Os fontes do jogo passaram por
  verificação de sintaxe com as flags do Android e com os headers do libc++ 19 (o do NDK r28):
  jogo, SDK e JNI sem erros além dos que só existem fora do Android (APIs do bionic).
- No PC do dfdx047 (CachyOS, clang 23): `codegen.sh` com o `.iso` Redump funcionou (90 arquivos
  gerados); o primeiro build NDK parou num `to_string` ambíguo, corrigido no `game/0003`.
- **Não verificado aqui** (sem NDK/Android SDK neste ambiente): o build Gradle/NDK completo e o
  Kotlin. A revisão estática do Kotlin não achou erros pendentes, mas o primeiro build no PC é que
  confirma.
- **Launcher** adaptado ao BL: importação por `.iso` (XDVDFS) ou pasta, verificação do `default.xex`
  USA (SHA-1), todas as opções do BL que funcionam no Vulkan, mods no formato do BL, packs de texturas
  (vários, um ativo), extração de texturas, saves (Download ⇄ `saves/`), online em rede local,
  controles de toque com os comandos do BL (Rush X, Smash Y, Ki blast B, Guarda A, Arremesso LT,
  Aura Spark LB, Perseguição RT, Transformar RB, Ki L3) e layout próprio da câmera livre.

## Rodada 1 (2026-10-14, depois do 0057)

O jogo arranca, passa os menus e entra na luta nos dois aparelhos. Problema: **sem texturas em
jogo (tudo preto)**; no Mali o ecra pisca (menus e luta, como no Dragon Fury).

| zip | aparelho | otimizacoes da GPU | fps luta | GPU ms luta | fins de passe/f | fps menus |
|---|---|---|---:|---:|---:|---:|
| 19:46 | RP5 | desligadas | 25 | 39,0 | 44 | 54 |
| 19:53 | RP5 | ligadas | 48 | 18,5 | 31 | 58 |
| 20:07 | RG Cube | ligadas | 14 | 70,0 | 24 | 44 |

- As otimizacoes do Dragon Fury ja valem no Burst Limit (RP5 25 → 48 fps em luta).
- Suspeita principal para o preto: atalhos validados so no Budokai 3 que reconhecem shaders do
  D3D do XDK (restauro 6E57…, profundidade via cor ADCA…) e eliminam resolves/restauros que no
  DBZ3 ninguem mais lia (`dbz3_rt_elide_restores`, `dbz3_skip_depth_restore`; 4,9 resolves
  eliminados/f no RP5). Falta confirmar com A/B (otimizacoes desligadas) e captura `.dfc`.
- ~300-800 avisos "Recovered stale physical page protection" por sessao (caminho de recuperacao do
  runtime original, paginas sem observador): verificar nas capturas se tocam texturas.

## Rodada 2 (2026-10-14, capturas `.dfc` da luta: RP5 20:32, RG Cube 20:28)

O que ficou provado:

- **As otimizacoes do Dragon Fury nao sao a causa do preto.** No zip 19:46 o log confirma tudo
  desligado ("sinais de textura como spec constants: false", sem `rt_direct`, sem atalhos de
  resolve, 19,7 resolves/f para a memoria) e a luta ja estava preta.
- `clear_memory_page_state = true` nao muda nada (tirar o `cvars.txt`).
- Os menus (2D) estao certos; o preto e so na luta. As vezes aparece um frame do jogo com as
  cores trocadas.
- **A captura nao ve o que a GPU desenha.** No Vulkan os resolves ficam na memoria da GPU e nunca
  voltam a memoria do guest: as texturas 1280x720 que a captura grava saem a zero mesmo sem os
  atalhos. Por isso entra o **0058**: durante a captura (1.o frame), cada resolve e lido de volta
  da GPU (espera a GPU, so nesse frame) e gravado no `.dfc` (registo 8). Os atalhos de resolve
  (eliminacao e "modo rapido") ficam desligados nesse frame para tudo passar pela memoria.
  `python3 tools/dfc_textures.py captura.dfc --out pasta --resolves` tira um PNG por resolve
  (cor, profundidade e 32_FLOAT em cinzento) com a % de pixeis nao pretos: ve-se em que passe a
  imagem se perde.

Mapa de um frame de luta (RP5, 332 draws, iguais no Mali):

| draws | o que e | alvo |
|---|---|---|
| 0–23 | 24 pontos sem cor (mascara 0, scissor 16x16) | p1280 1x |
| 24–53 | sombra / silhueta (Clear 1x + Clear 4x p520) → resolve 0ECB9000 1024x1024 | p1040 1x |
| 54 | Clear do XDK (VS 0A6D1DD7…, PS 2E372EA2…, 4x p640) | 4x → 1x |
| 55–128 | 1.a passagem da cena (lutadores com 2.o stream skinned em 0x0CB9…–0x0CC1…) | p1280 1x D@720 |
| 129–136 | resolves cor 0E589000 + profundidade 0E921000; profundidade 24_8 → linear 32_FLOAT (0F451000) | |
| 137–211 | Clear + 2.a passagem da cena (mesmos draws) | p1280 1x |
| 212–218 | distorcao + aura (sprites 4A56… com a profundidade linear) → 0DE59000 | |
| 220–245 | pos-processamento: brilho (640x360), motion blur, DOF, filtro suave (saltados por omissao) | |
| 246–304 | HUD (profundidade sempre a passar) → 0E1F1000 | |
| 306–307 | Clear 4x (cor@0 + profundidade@0) e copia de 0E1F1000 para o alvo | |
| 308–331 | mais 24 pontos sem cor, resolve para o frontbuffer 0C000000 | |

- O Clear do XDK do Burst Limit usa shaders diferentes dos do Budokai 3, por isso o atalho
  "Clear direto em 1x" (`dbz3_clear_to_1x`, 0042) nunca disparava: 4 transferencias 4x → 1x por
  frame (Mali: transfer ~12 ms/frame). **0059** junta os shaders do BL ao atalho.
- Os avisos "Recovered stale physical page protection" sao das 3 threads de skinning a escrever
  os vertices dos lutadores (anel triplo de 8 MB: 0x0C4…, 0x0CC…, 0x0D4…); 300–2000 por sessao.
  Por ver se deixam a GPU com vertices velhos (nao explica o ecra todo preto: o cenario e estatico).

## Rodada 3 (2026-10-14 22:08, RP5, captura com leitura de volta dos resolves)

Otimizacoes desligadas, os 4 efeitos ligados. **A GPU desenha a luta**: o resolve para o
frontbuffer (0C000000) tem o cenario, os lutadores e o pos-processamento. Defeitos vistos na
imagem: ceu preto e uma esfera branca enorme por cima do cenario (a ver depois).

Como o frame com leitura de volta sai certo e o jogo normal mostra preto, a suspeita passa a
ser uma de duas:
1. sincronizacao: a leitura de volta fecha e espera cada submissao depois de cada resolve;
2. memoria do guest: a leitura de volta copia a imagem para a memoria do CPU; se algo volta a
   carregar o frontbuffer (ou outra copia) a partir da memoria do CPU, sem a leitura de volta
   carrega zeros (preto).
Teste A/B sem recompilar, no `cvars.txt`: `readback_resolve = "full"` (sincrona) contra
`readback_resolve = "fast"` (copia atrasada, sem esperar a GPU).

## Rodada 4 (2026-10-14 22:47 e 22:54, RP5)

- Captura com as otimizacoes do Dragon Fury **ligadas** (efeitos ligados): a cena sai certa ate
  0DE59000, mas a composicao do brilho (F59256…, le 0DE59000 + o brilho 640x360) sai preta e tudo
  dai para a frente tambem, ate ao frontbuffer. Com elas **desligadas** (captura das 22:08) a
  mesma composicao sai certa e o frontbuffer tem a luta.
- Conclusao: as otimizacoes do Budokai 3 apagam o pos-processamento do Burst Limit. Passam a vir
  desligadas por omissao no launcher (o atalho certo para o BL fica para o render nativo).
- Por confirmar no aparelho: otimizacoes desligadas com os efeitos desligados (a predefinicao).

## Rodada 5 (2026-10-14 23:44, RP5) e patch 0060

- Otimizacoes desligadas, efeitos ligados, sem leitura de volta: luta a 13–16 fps, ecra preto.
  Mas a captura das 22:08 (mesma configuracao) tinha a luta inteira no frontbuffer: a imagem
  existe na GPU e nao chega ao ecra. Suspeita: a apresentacao (o presenter le o frontbuffer pelo
  fetch constant que o VdSwap escreve; se na luta ele descrever outro formato/tiling/endian, a
  imagem lida sai preta ou com cores trocadas).
- O Mali tambem fica preto: nao e um problema do driver do Adreno 650.
- O upstream do BL nao tem commits depois do v5.2 (`bb60bc1`) e o fork do SDK tambem nao. A
  "versao Linux" do BL e o build de Windows (D3D12) com vkd3d-proton/Wine: **o backend Vulkan do
  SDK do BL nunca foi usado pelo autor**; tudo o que o fork mudou em codigo partilhado so foi
  validado com D3D12.
- **0060**: regista no log cada variante do fetch constant do swap (`dbz3: swap fb=...`) e grava
  os registos do swap na captura. Comparar menus com a luta.

## Rodada 6 (2026-10-14 23:55, RP5, otimizacoes desligadas, efeitos ligados)

- O swap usa sempre o mesmo fetch constant (0C000000 8_8_8_8 1280x720 tiled, endian 0), nos
  menus e na luta: a apresentacao nao e o problema.
- O utilizador ve um vislumbre da imagem so na cutscene do inicio da luta. A captura das 22:08
  era dessa cutscene; a das 23:55 e da luta a valer.
- Na luta: a cena (0DE59000, depois das auras) sai **certa**; o passe seguinte, a composicao do
  brilho (PS F59256…: `oC0 = tex0 * c0.w + tex1 * c0.x`, c0 = (8,0,0,1), com tex0 = 0DE59000),
  sai **preto**, e tudo dai ate ao frontbuffer tambem. O draw, as constantes e os fetch constants
  sao iguais aos da cutscene (onde sai certo), exceto o LOD bias do tex0 (-1 na luta, 0 na cutscene).
- Suspeita: a textura de 0DE59000 nao e recarregada depois do resolve (fica com o conteudo velho,
  que no fim do frame anterior ja era preto: o preto alimenta-se a si proprio). **0061** grava na
  captura cada carga e cada invalidacao de textura (registo 9) e `dfc_textures.py --eventos
  --addr 0DE59000` mostra a linha do tempo.
- Desassemblador de shaders Xenos no PC (shader.cpp + translator_disasm.cpp do SDK + stubs): usar
  o ucode da captura com os dwords trocados (big-endian).

## Rodada 7 (2026-10-15 00:14, RP5, com 0061)

- A textura 0DE59000 **e** invalidada pelo resolve e recarregada antes da composicao: nao e
  textura velha. Mesmo assim a composicao sai preta, e o HUD inteiro tambem nao aparece no
  resolve depois do HUD (0% de pixeis nao pretos).
- O que falha na luta sao **todos os draws com teste de profundidade ligado e funcao "sempre"**
  (composicao, motion blur, HUD); os da cena (LEQUAL) e os sem profundidade funcionam. Na
  cutscene os mesmos draws funcionam. `free_camera` esta desligado (controles.log), por isso o
  "esconder HUD" do modo foto nao deveria estar ativo.
- **0062**: cada draw da captura grava a linha do `return` em `IssueDraw` (registo 10;
  `dfc_textures.py --eventos`). Linhas (no SDK com 0000–0062): 4373 esconder HUD, 4763 pipeline
  provisorio (compilacao assincrona), 5122 desenhado; 4346 override "skip"; 4613 sem vertices.
- Teste sem recompilar: `async_shader_compilation = false` no cvars.txt (se a composicao/HUD
  ficam com pipelines provisorios para sempre, isto resolve).

## Rodada 8 (2026-10-15 00:31, RP5, com 0062 e `async_shader_compilation = false`)

- Ainda preto. **Todos os 254 draws do frame chegam ao fim de `IssueDraw`** (linha 5122): nenhum
  e saltado (nem HUD escondido, nem pipeline provisorio). A GPU executa-os e mesmo assim a
  composicao e o HUD nao aparecem.
- Os registos de render (0x2000–0x2FFF) da composicao sao **identicos** entre a cutscene (onde
  funciona) e a luta. A unica diferenca: o LOD bias do tf0 (word 4 do fetch constant = 0x003E0000
  ou 0x003E0003 → −1.0) em **todos** os draws que falham (composicao, HUD 2E37…, D9BA…, EC9B…);
  na cutscene e 0.
- **0063**: `dbz3_ignore_fetch_lod_bias = true` (cvars.txt) traduz os shaders sem o LOD bias do
  fetch constant. Se a luta aparecer, o problema esta no caminho do LOD bias (gradientes × 2^bias).

## Rodada 9 (2026-10-15 00:39, RP5): primeira luta com imagem

- A luta apareceu (Goku vs Krillin, ceu), 11–13 fps no RP5 com os 4 efeitos ligados (GPU ~75 ms:
  desenho 47 ms/27 passes, resolve 13 ms, texturas 8,5 ms, 21 resolves/frame).
- O diagnostico do 0063 **nao estava ligado** nessa sessao (cvars.txt so com
  `async_shader_compilation = false`). A captura mostra porque deu imagem: nessa luta o jogo
  **nao** pos o LOD bias (word 4 = 0); nas sessoes pretas punha −1 / −1,16 (0x003E…, 0x003B…).
  Confirma a correlacao: com o bias do jogo os draws de ecra inteiro e o HUD saem pretos.
- O launcher passa a escrever `dbz3_ignore_fetch_lod_bias = true` (contorno ate se perceber o
  erro no caminho do LOD bias do tradutor SPIR-V).

## Rodada 10 (2026-10-15 07:29–07:32, RP5, efeitos desligados, LOD bias ignorado)

- Luta com imagem certa (captura 07:31:58: HUD, cenario, auras). **23–27 fps** em luta (GPU
  ~37–42 ms: desenho 11–14 ms/20–24 passes, ~21 resolves/f, ~12 cargas/f) contra 12–15 fps com os
  efeitos ligados.
- Continua: o inicio do round 1 fica preto alguns segundos/minutos e depois aparece. Falta uma
  captura **durante o preto** com o contorno ligado. Pistas das capturas pretas anteriores: a
  1.a passagem da cena tem ~130 draws (64 nos frames bons) e a profundidade linear (0F451000)
  sai constante ~1,0 (nos frames bons vai de ~40 a ~6400).

## Rodada 11 (2026-10-15 07:55, RP5): a causa do preto

- Captura no preto do round 1 com o contorno do LOD bias ligado: continua preto. Diferenca de
  todas as constantes entre a composicao boa (cutscene) e a preta: so a word 4 do fetch 0
  (0x00000000 vs 0x003E0000).
- **Causa**: o tradutor SPIR-V aplicava o expoente do resultado (`exp_adjust`) lendo os bits
  13:18 da **word 4** do fetch constant; o `exp_adjust` esta nos bits 13:18 da **word 3** (o
  tradutor DXBC, usado pelo D3D12 do PC, le a word 3). Na word 4 esses bits sao parte do LOD
  bias (12:21): com o LOD bias −1 (0x3E0) davam −16, e cada amostra saia ×2^−16 → preto. O
  Budokai 3 nunca usa LOD bias, por isso nunca apareceu; o Burst Limit liga-o em parte da luta
  (round 1, intro), por isso "preto e depois aparece".
- **0064** corrige (word 3). O contorno `dbz3_ignore_fetch_lod_bias` volta a desligado.

## Rodada 12 (2026-10-15 08:04–08:10, RP5, com 0064): a luta funciona

| otimizacoes do Dragon Fury | efeitos | fps luta | GPU ms | desenho | transfer | resolve | texturas |
|---|---|---:|---:|---|---|---:|---:|
| desligadas | ligados | 12–16 | 60–80 | 32–49 ms/25–31p | 5–9 ms | 12–15 ms | 8–12 ms |
| **ligadas** | ligados | **41–57** | **17–23** | 7–11 ms/21–28p | 0 | 8,5–10,5 ms | 0,5 ms |

- Imagem certa nas duas (capturas 08:05:14 e 08:10:04: cenario de relva, HUD, auras). O preto
  da rodada 4 com as otimizacoes ligadas era o mesmo bug do exp_adjust, nao elas.
- As otimizacoes voltam a vir ligadas por omissao no launcher.
- Os efeitos do launcher funcionam (toml com os 4 a true e os draws de DOF/brilho/motion blur
  presentes na captura).
- Proximo: RG Cube (Mali) com tudo isto; depois o render nativo (o resolve, ~9–10 ms com ~20
  resolves/frame, e agora o maior custo; desenho ~8–11 ms em 21–28 passes).

## Rodada 13 (2026-10-15 13:27–13:38, RG Cube / Mali-G57, otimizacoes ligadas)

- Imagem certa na luta (captura 13:37:59). 13–17 fps com efeitos, 17–19 sem (GPU 52–74 ms:
  desenho 26–46 ms em 16–25 passes, resolve 15–20 ms). Desligar os efeitos quase nao muda: o
  jogo faz a cadeia de passes de ecra inteiro na mesma.
- Tempo por draw no Mali (captura com cada draw no seu passe, valores inflacionados mas
  comparaveis), frame da luta sem efeitos:

| fase | draws | % do tempo |
|---|---:|---:|
| pos-processamento (brilho/composicao, mesmo com os efeitos "desligados") | 25 | 31% |
| profundidade linear (copia da cena, A55B…, D987… 24_8 → 32_FLOAT) | 8 | 22% |
| final (Clear 4x + copia de 0E1F1000 + resolve para o frontbuffer) | 27 | 14% |
| sombra/silhueta (p1040) | 30 | 10% |
| cena 2.a passagem + auras | 87 | 8% |
| HUD | 65 | 7% |
| cena 1.a passagem | 75 | 6% |

- Prioridades do render nativo (Mali primeiro): 1) final: apresentar 0E1F1000 direto (sem a
  copia + resolve para 0C000000); 2) profundidade linear calculada direto do depth buffer num
  passe so; 3) cadeia de pos-processamento num so passe quando os efeitos estao desligados;
  4) juntar a 1.a passagem da cena so quando algum efeito a le.

## Render nativo — passo 1 (patch 0065, `dbz3_bl_native_present`)

- O fim de cada frame da luta: Clear 4x, quad de copia (VS 4666C5A9…, PS EC9B5278…: `tex * VS c5`)
  de 0E1F1000 para o render target, 24 pontos e resolve para o frontbuffer. Com `c5 = (1,1,1,1)` a
  copia e identidade: o quad e o resolve para o frontbuffer sao saltados e o presenter le direto
  a textura de 0E1F1000 (o fetch constant do quad entra no lugar do fetch 0 do swap). Com outra
  cor (fades entre rounds) fica o caminho normal. Interruptor no launcher (Desempenho).
- Resultado (logs de 15/10): Mali 18–20 FPS / GPU 50–54 ms com efeitos desligados; RP5 47–54 FPS
  com efeitos.

## Render nativo — passo 2 (patch 0066, `dbz3_bl_native_linear_depth`)

- Depois do resolve da profundidade (0E921000), o jogo desenha um quad de ecra inteiro (VS
  36FB87D3…, PS D9872B5A…: `c255.x / (c255.x - d * c255.y)`, A = 10000, B = 9999) num render
  target 32_FLOAT e resolve-o para a textura k_32_FLOAT 0F451000 (lida pelo bright pass, motion
  blur e DOF). Agora: no 1.o frame o resolve ensina o destino; dai em diante o quad e saltado e o
  resolve e feito por um compute (variante 6 do imageStore) que le a profundidade direto do render
  target (D@720, intacto entre o resolve e o quad) e escreve na textura. As texturas k_32_FLOAT
  ganharam usage STORAGE. Se algo nao bater (destino, depth noutro render target), volta ao caminho
  normal e reaprende. Interruptor no launcher (Desempenho).
- Logs de 16/10 (RG Cube e RP5): o passo nunca ficava pronto — o destino era "aprendido" a cada
  frame (16 mil linhas) porque `Dbz3FindResolveTexture` so aceitava 8888/24_8. Corrigido no 0067
  (k_32_FLOAT = R32_SFLOAT) e o log diz agora a razao quando nao da (`ainda nao (N)`).

## Render nativo — passo 3a (patch 0068)

- Logs de 16/10 07:08/07:11: o passo 2 ligou nos dois (RG Cube 19,6 -> 21,7 FPS, GPU 50 -> 45 ms;
  RP5 ~50 FPS, GPU 16-19 ms com efeitos).
- Mapa do pos com efeitos desligados (captura 07:07, RG Cube): os draw overrides saltam os desfoques,
  mas os resolves deles continuam - 12 resolves no pos. Agora:
  - `dbz3_resolve_dedupe`: resolve sem draws desde o anterior e com os mesmos parametros, para um
    destino que ja tem essa imagem, e saltado (0FF19000 depois do DOF desligado); destino que
    ninguem le antes de ser reescrito durante 120 escritas deixa de ser resolvido (10009000 x2,
    0F0B9000, 0DE59000 x2, e a profundidade 0E921000 que so o quad do passo 2 lia). Um draw que o
    volte a ler reativa-o.
  - `dbz3_skip_null_draws`: os 24 pontos por frame (PS 2E37..., mascara 0, sem profundidade) fora
    de consultas de oclusao.
  - `dbz3_bl_native_post_lite`: com o motion blur desligado, a preparacao dele (29D1...) e o resolve.
  Um interruptor no launcher (Depuracao) liga os tres.
- Proximo (passo 3b): a cadeia toda num compute (contorno ABBF, brilho B0133E/F59256, mascara CAC24,
  DOF/cor AA377), validado fora do aparelho com as imagens dos resolves das capturas.

- 17/10 03:09 (RP5 Qualcomm) / 03:14 (RP5 Turnip), build 0080: o contorno por compute nunca ligou -
  "contorno ainda nao (1)" (destino nao aprendido) nos dois; imagem igual. `0081-contour-learn-original-base`:
  o resolve do contorno tambem e aceite pela base original no EDRAM (como o passo 4 no 0072) e, se nao
  bater, uma linha "contorno, resolve nao bate: ..." diz qual campo falha.
  03:24 (RP5 Qualcomm) / 03:34 (RP5 Turnip Rutabaga v3): a linha diz "quer 0x0" - os parametros do
  quad so eram guardados quando o draw ja podia ser saltado, por isso a aprendizagem comparava com
  largura 0. `0082-contour-learn-params`: guardados sempre que o quad e reconhecido.
  **03:50 (RP5 Qualcomm, 0082): o contorno por compute funciona** - "contorno -> 0F0B9000", "contorno por
  compute ativo", 9741 passes em 12000 frames, 0 falhas; autoteste "igual". Primeiro efeito do passo 3b
  confirmado no aparelho. Cena pesada (~22-23 ms com efeitos): o ganho fica abaixo do ruido da medicao.
- 17/10 (offline, sem build): **passo 3b, a cadeia do brilho** - `0083-bl-native-downsample`. Mapa
  da cadeia (todas as capturas): 0F0B9000 (contorno) -> reducao B0133E -> 0FF19000 -> desfoque
  vertical 97906B (VS 66B048) -> 10009000 -> desfoque horizontal 97906B (VS 4212920) -> 100F9000 ->
  composicao F59256 (+ cena 0DE59000) -> 0E1F1000 (+ 0F7E9000 na 1.a cadeia). Cada intermedia tem um
  so leitor (o passe seguinte); nao se funde, para o caminho normal continuar certo se a cadeia parar
  a meio (custa uma copia de 0,9 MB por passe 640x360). Modulo comum `dbz3_native_post` (um GLSL, um
  pipeline por tipo, destinos aprendidos por instancia, ate 4 por passe, as licoes do 0081/0082).
  Reducao (`dbz3_bl_native_downsample`): UV a meio de 2x2 texeis (media exata), `caixa(0,0)*c0 +
  max(4 caixas)*c1`. Dos dados: swizzle BGRA do fetch e troca R/B do resolve anulam-se (as contas por
  canal nao trocam canais); o Adreno converte 8 bits com `t*(1/255)`, o Mali com `t/255`; o render
  target arredonda o .5 para cima. `tools/ref_glow.py`: **29 draws em 24 capturas (8 Mali, 21 Adreno),
  27 com 0 pixeis diferentes**, 2 (contorno colorido) com 52 e 336 pixeis a +-1, so em empates exatos
  .5 (precisao do filtro da TMU). No autoteste: "reducao 640 por compute (passo 3b)".
  A ver no aparelho: `reducao 640 -> 0FF19000`, `reducao 640 por compute ativo`, o contador
  (~2 passes/frame, 0 falhas), imagem igual.
- 17/10 (offline, sem build): `0084-bl-native-blur` - os dois desfoques 640 (PS 97906B, VS 66B048
  vertical 0FF19000 -> 10009000, VS 4212920 horizontal 10009000 -> 100F9000) por compute
  (`dbz3_bl_native_blur`): UV no centro dos texeis, desvios +-1..+-5 (c255/c254 do VS, -6 do PS),
  pesos 1/0,9/0,8/0,6/0,4/0,2 vezes c0*c255.z (1/6,8), mad a mad. `tools/ref_glow.py`: **44 draws em
  18 capturas, 36 com 0 pixeis diferentes**, 8 (driver da Qualcomm) com 1-3 pixeis a +-1 em valores a
  menos de 1e-5 de um empate .5 (o compilador da Qualcomm faz FMA do mul+add); 14 draws de 7 capturas
  excluidos (o desfoque nao correu nessa build: o resolve e o conteudo anterior). No autoteste:
  "desfoque 640 por compute (passo 3b)". A ver no aparelho: `desfoque 640 -> 10009000` e
  `-> 100F9000`, `desfoque 640 por compute ativo`, o contador (~4 passes/frame, 0 falhas), imagem igual.
- 17/10 (offline, sem build): `0085-bl-native-glow-composite` - a composicao do brilho (PS F59256,
  VS 36FB87) por compute (`dbz3_bl_native_glow_composite`): `brilho*8 + cena`, a cena no texel do
  pixel, o brilho 640x360 bilinear com pesos exatos 0,75/0,25; os dois resolves da 1.a cadeia
  (0E1F1000 e 0F7E9000) saem do mesmo compute (ate 4 destinos por passe). O render target nao e lido
  depois (vem o Clear 4x). `tools/ref_glow.py`: **24 draws em 19 capturas, erro maximo 1**, 0-4494
  pixeis a +-1 por draw (Adreno <= 0,09%, Mali <= 0,5%), **todos em empates exatos .5** e sempre a
  GPU a arredondar para baixo (o bilinear da TMU fica um nada abaixo da media exata; o x8 amplia).
  Invisivel (o autoteste conta diferencas > 24); se for preciso bit a bit: sampler de hardware com
  coordenadas nao normalizadas no compute. No autoteste: "composicao do brilho por compute (passo 3b)".
  A ver no aparelho: `composicao do brilho -> 0E1F1000` e `-> 0F7E9000`, `composicao do brilho por
  compute ativo`, o contador (~3 passes/frame, 0 falhas), imagem igual, GPU ms. A cadeia toda (0080 +
  0083-0085): ~2,5 ms de draws + 3,4-4,1 ms de resolves por frame no RP5 (menos o que o passo 4 ja
  tira), trocados por 9 computes e ~24 MB de copias.
  `0086-glow-chain-rit-stale`: ao saltar um quad o modulo apaga a marca do passo 4 "render target sem
  a imagem" desse alvo, como o inicio do passe (sobrescreve tudo) fazia no caminho normal; sem isto,
  com a composicao saltada, o HUD depois do Clear 4x banía um passe desenhado direto na textura.

- 17/10 04:58 (RP5 Qualcomm, 0083-0086): a reducao 640, o desfoque e a composicao por compute ligaram
  (0 falhas), mas a imagem final ficou so com o brilho desfocado (captura de ecra do utilizador; FPS 50).
  O autoteste: so desligar o passo 4 ou o "passe sem carregar a cor" (de que o passo 4 depende) corrige
  (88%). Causa: o 0086 apagava a marca do passo 4 "render target sem a imagem" quando um quad era
  saltado, e o passe que depois carregava esse alvo ja nao banía o passe desenhado na textura; alem
  disso a composicao saltada deixa o render target de 1280 sem a imagem que o HUD carrega.
  `0087-undo-stale-clear-composite-off`: o 0086 deixa de fazer efeito; a composicao por compute fica
  desligada por omissao (e no launcher). Reducao, desfoque e contorno continuam ligados.

- 17/10 05:09 (RP5 Qualcomm, 0087): imagem certa com tudo ligado (referencia boa); 3b ativo (contorno
  9173, reducao 9417, desfoque 18834 passes, 0 falhas). RP5 ~47-50 FPS / ~20 ms de GPU nesta cena; tudo
  desligado 28 FPS / 36 ms. Dois estados partem a imagem: "com composicao" (esperado, fica desligada) e
  "sem contorno por compute" (imagem amarela): com o contorno pelo caminho normal o passo 4 desenha-o
  direto na textura 0F0B9000 e a reducao por compute le-a logo a seguir - a ultima barreira so a deixava
  visivel aos shaders de graficos. `0088-compute-read-waits-for-writes`: antes de um compute do 3b ler
  uma textura, barreira que espera por escritas de alvo de cor, copias e computes anteriores.

- **17/10 05:21 (RP5 Qualcomm, 0088): tudo "igual"** no autoteste com a luta parada (ruido 0,000%):
  contorno, reducao, desfoque, passo 4 e ate "com composicao" saem iguais (a imagem amarela foi
  corrigida pelo 0088). **RP5 a 59,9 FPS, ~13,5-14,5 ms de GPU com os efeitos ligados** nesta cena;
  tudo desligado 32 FPS / 31 ms. A composicao por compute fica desligada ate um teste com ela ligada
  em jogo normal (no 0087 partia com o 0086 ativo).

- 17/10 (offline, sem build): **a composicao do brilho volta a ligar** - `0090-glow-composite-on`.
  O que o 0087 dizia (o HUD carrega o render target de 1280 que o quad saltado escreveria) nao se
  confirma: `tools/rt_after_quad.py` em todas as capturas, 58 composicoes (24 capturas, 2 frames),
  **0 cargas** - depois do quad e dos resolves vem sempre o Clear 4x de ecra inteiro ou um quad opaco
  de ecra inteiro (29D1F5 / AA377C); o HUD le a composicao pelas texturas 0E1F1000/0F7E9000. A imagem
  partida do 0086 era a marca do passo 4 apagada; a do 0087 ("com composicao" 88,6%, igual a "sem
  contorno" 88,8%) era a barreira que faltava e o 0088 pos (autoteste 05:21: "igual", 0 banidos).
  `ref_glow.py` (2 capturas do RP5): erro max 1, 336-389 pixeis a +-1, so em empates .5. Padrao do SDK
  e launcher (`nativePostLite`) ligados. Ganho esperado no RP5 ~0,8-1,5 ms/frame. A ver no aparelho:
  `composicao do brilho -> 0E1F1000`, `composicao do brilho por compute ativo (...)`, contador com 0
  falhas; no autoteste a linha passa a "sem composicao do brilho por compute (passo 3b)" -> igual.
- 17/10 (offline): **passe do mapa de sombras medido, sem mudanca.** Dois clears complementares (1x
  pitch 1040 faixa x 960..1024 + 4x pitch 520 960x1024 = 1024x1024; nao redundantes), 27-29 draws dos
  lutadores (VS 75605682/3D5BF5 + PS CF0D1D, VS 818BD0 + PS 323B49) e o resolve para 0ECB9000
  1024x1024 (14 leitores do cenario por frame). No RP5 (5 capturas SM8250): draws **0,47-0,78 ms/frame,
  3-7% do desenho**, cada um perto do minimo por draw (limitado por vertices, nao por preenchimento);
  clears 0,1 ms; resolve <= ~0,5 ms. Sombra a 512x512 pouparia <= 0,4-0,5 ms com sombras mais moles; o
  passo 4 neste passe (sem resolve) pouparia <= 0,5 ms mas o passe abre com clears e o passo 4 nao o
  aprende. Nao vale: sem opcao "sombra" no launcher. O tempo das cenas pesadas (18-20 ms) esta noutro
  lado - falta uma captura de uma cena dessas. No Mali a captura da 15-16 ms aos draws da sombra (tempo
  por draw inflacionado no Mali); ver com A/B de perf no RG Cube.

## Resolucao abaixo de 720p e predefinicoes por SoC (17/10, patch 0089)

- `0089-vulkan-below-1x`: o `draw_resolution_percent` (render abaixo de 1x, que o fork so tinha no
  D3D12) passa para o Vulkan, com o mesmo desenho: os render targets ficam com o tamanho de 1x e o
  jogo e desenhado no canto superior esquerdo (pixel do host = pixel do EDRAM). Viewport e scissor
  escalados (o scissor para fora), `GetResolveInfo` com a percentagem (o retangulo do resolve e o
  destino no mesmo canto; limpezas e dumps saem do mesmo retangulo), texturas feitas de dados
  resolvidos (2D, 1 nivel, fora da cauda de mips, blocos 1x1) criadas e carregadas do canto ao
  tamanho menor com os shaders normais (`IsDownscaledResolveSupportedForFormat`), o frontbuffer no
  tamanho menor (o presenter estica-o, FSR 1 incluido), `ps_param_gen` do tradutor SPIR-V devolvido
  em pixeis do guest, amostras de oclusao normalizadas e a area do "passe sem carregar a cor" em
  pixeis do host. Fica fixo no arranque (o Vulkan nao recria os caches ao vivo). 480p = 67%
  (858x483), 540p = 75% (960x540). Log: `Rendering below 1x: 67% (858x483 of 1280x720)` e
  `dbz3: resolucao interna 858x483 (escala 1x1, 67%)`.
- Render nativo fora de 1x (escala 2x/3x **ou** percentagem < 100): `Dbz3NativeResolution()` desliga a
  imagem final direta, a profundidade linear (passo 2), contorno/reducao/desfoque por compute (3b), os
  passes direto na textura (4) e o atalho resolve -> textura (tambem o modo rapido sem memoria); o
  fallback e o caminho normal do Xenia. Continuam (nao dependem do tamanho): resolves repetidos/sem
  leitor (3), draws nulos, o salto da preparacao do motion blur, o "passe sem carregar a cor", os
  efeitos desligaveis, o restauro da profundidade saltado, o Clear() 4x direto em 1x e os render
  targets diretos. Uma linha no arranque: `dbz3: render nativo: fora de 1x ficam desligados ...`.
- 1080p: nao ha. Seria 1,5x; acima de 100% os render targets de 1x nao chegam (o EDRAM 1x transborda)
  e a escala do Xenia so e inteira. Juntar 2x com 75% (= 1920x1080) parece coerente com o mesmo
  mecanismo, mas mexe no buffer de resolves com escala e nas cargas com escala, nunca testados no
  Vulkan: fica para depois de um teste no aparelho. No launcher a escolha diz isso (num ecra 1080p,
  720p e ampliado e 2K reduzido).
- Launcher: a resolucao passa a 480p / 540p / 720p / 2K (1440p) / 4K (2160p) (`InternalResolution`,
  escreve `draw_resolution_scale_x/y` + `draw_resolution_percent`, tambem no menu do jogo). Avisos:
  abaixo de 720p menos nitidez por % dos pixeis; acima, o render nativo desliga e a GPU desenha 4x/9x.
- Predefinicoes (5, em cartoes com os processadores recomendados; "Personalizado" quando algo muda a
  mao; o render nativo fica ligado em todas):

| predefinicao | resolucao | efeitos do jogo | FPS | apresentacao | aniso | recomendada para |
|---|---|---|---|---|---|---|
| Minimo | 480p | nenhum | 30 | bilinear | jogo | Helio G99, Snapdragon 4xx/6xx (Adreno 610-616), Unisoc T6xx (Mali-G52) |
| Leve | 540p | nenhum | 30 | FSR 1 | jogo | Mali-G57 MC4 (Unisoc T770, RG Cube), SD 720G/750G/695/845, Dimensity 700-1080 |
| Equilibrado | 720p | profundidade de campo + brilho | 60 | bilinear | 4x | SD 855/865/870/888 (RP5), 778G, 7 Gen 1/7+ Gen 2, Dimensity 1100/1200, Tensor |
| Alto | 720p | todos | 60 | FSR 1 | 16x | SD 8 Gen 1/8+ Gen 1/8 Gen 2 (Odin 2/Mini/Portal), 8s Gen 3, 7+ Gen 3, Dimensity 8100-9400, Exynos 2200/2400 |
| Ultra | 1440p | profundidade de campo + motion blur | 60 | bilinear | 16x | SD 8 Gen 3/8 Elite (Odin 3) |

  A recomendacao sai do Adreno (tabela SOC_MODEL -> Adreno do `TurnipSupport`) ou do SOC_MODEL
  (MediaTek MTxxxx, Unisoc Txxx/UMS, Exynos S5E, Tensor). Base: RP5 ~60 FPS a 720p com tudo
  (13,5-20 ms de GPU conforme a cena) -> Equilibrado deixa margem; RG Cube 22-24 FPS a 720p com
  efeitos -> Leve (540p = 56% dos pixeis, sem efeitos, 30 FPS). Ultra nao foi medido: a 2x o render
  nativo desliga (no RP5 tudo desligado a 720p dava 32 FPS).
- A ver no aparelho: com 480p/540p, as linhas `Rendering below 1x` e `dbz3: resolucao interna`, a
  imagem inteira no ecra (nao num canto: se aparecer no canto, a textura do frontbuffer nao foi feita
  como resolvida - `Below 1x: resolved texture ... isn't made at the lower resolution` diz o formato),
  HUD e menus nitidos/alinhados, sombras e contornos sem deslocamento, o FPS/GPU ms em luta no RG Cube
  (objetivo: 30 FPS estaveis em Leve). Com 2K: a linha do render nativo desligado e a imagem certa.
- **17/10 07:12, RG Cube (0089, 540p = 75%, FSR): sem ganho.** 20-24 FPS, GPU ~42-50 ms: desenho
  17-23 ms, resolve 12-14 ms (18,5-20/f: 960x544:12-13, 480x272:6, 768x768:1), texturas 8-9 ms
  (11-13 cargas/f), transfer 1,2-1,7 ms. A 720p com o render nativo (16/10 23:49, mesmo aparelho):
  desenho 27-37, resolve 7-10, texturas ~0. Os pixeis poupados (-10 ms de desenho) foram comidos
  pelos passos desligados: as cargas (o atalho resolve -> textura desligado: cada textura resolvida
  e recarregada da memoria a cada frame) e os resolves (sem o passo 4). As cargas "1280x720f6:7"
  nao eram texturas de 720p: a linha "cargas" mostrava o tamanho do guest; a textura e de 960x540
  (`textura grande 1280x720 ... scaled=1`). Autoteste todo "igual", mas com os passos desligados.
- **0091-native-steps-below-1x: os passos do render nativo abaixo de 1x.** O que partia em cada um:
  - **Atalho resolve -> textura (e o modo sem memoria) e passo 4:** procuravam a textura com o
    tamanho do retangulo do resolve (960x544, do host) e comparavam o pitch do destino (1280, do
    guest) com ele; a textura tem a chave do guest (1280x720) e e `scaled_resolve` (feita do canto
    a 960x540). Agora `ResolveInfo` guarda tambem o retangulo do guest (`dbz3_guest_width/height`),
    as texturas procuram-se com ele (e `scaled_resolve` abaixo de 1x), os tamanhos escritos sao os
    da textura no host (`Dbz3TextureWidth/Height`) e o retangulo tem de a cobrir toda. Passo 4: o
    framebuffer e a area do passe sao do tamanho da textura no host (antes o do resolve, 544 linhas
    para uma imagem de 540), e estas texturas ja podem ser alvo de cor/STORAGE. **Exato a qualquer
    percentagem** (copia texel a texel do canto do render target, o que o resolve + carga fazem).
  - **Passo 1, passo 2, contorno, composicao:** a conta do jogo da o mesmo texel do host so se o
    tamanho do host for exato (75%: 960x540 / 480x270). A 67% (857,6 -> 858, 482,4 -> 483) a
    amostra do jogo escorrega: na vertical, a partir da linha 402, o quad le a linha seguinte (o
    proprio jogo a 67% salta uma linha por passe de ecra inteiro; nao se corrige com um viewport,
    os tamanhos 720 e 360 dao razoes diferentes). O contorno precisa ainda de 0,5 < f < 1,5 (o
    vizinho a 1 texel do guest e o texel ao lado no host).
  - **Reducao e desfoque:** os desvios do VS em texeis do guest caem a k * f texeis do host (a 75%:
    0,75 / 1,5 / 2,25 / 3 / 3,75): amostras bilineares com pesos 0,25/0,75/0,5, nao texeis inteiros.
    Variante `SCALED` dos dois kernels (bilinear a mao com pesos exatos, so com 16 * p multiplo de
    100); a 1x os kernels de sempre (pipelines separados).
  - Portao por passo: `Dbz3NativeStepsAtPercent(p)` - 75%: todos; 67%: so o atalho e o passo 4;
    (60%: todos menos reducao e desfoque; 50%: todos menos o contorno). Cada passo verifica tambem
    os tamanhos reais das texturas (`ainda nao (7)` no contorno/brilho, `(5)` na profundidade).
  - Linha "cargas" com o tamanho no host (`960x540f6`).
- Validado fora do aparelho, `tools/ref_below1x.py`: (1) posicoes das amostras do draw do jogo
  (UV dos vertices + mapeamento do 0089, `X = a(i + 0,5) + f k` com UV0 = a/2) contra o compute,
  todos os pixeis: 100% e 75% **0 diferentes** em todos os passos; 67% diferentes em todos (por
  isso desligados). (2) com as entradas de 5 capturas (Mali e Adreno, 27 draws de reducao,
  desfoque e composicao): a 100% o modelo do draw reproduz o jogo (0 pixeis; composicao so os
  empates .5 ja conhecidos, 215-4494); a 75% modelo do draw vs compute SCALED: **erro max 1, 1
  pixel em 27 draws** (um empate). Contorno, profundidade e imagem final: mesmas contas a 1x, so
  tamanhos.
- Ganho esperado no RG Cube a 540p: texturas 8-9 -> ~0,2 ms, resolves 12-14 -> ~4-6 ms (os de 1x
  eram 7-10 ms com 1,8x os pixeis), desenho 17-23 -> ~16-19 ms (sem os quads saltados): **GPU
  ~42-50 -> ~25-29 ms, 30 FPS estaveis em Leve** (35-40 sem limite). A 480p (67%) so o atalho e o
  passo 4: provavelmente pior que 540p (sem o passo 2, que valia 4,5 ms a 720p); a predefinicao
  Minimo pode passar a 540p ou a 60% (768x432) se o teste o confirmar.
- A ver no aparelho (540p): `dbz3: render nativo: a 75% ficam ligados imagem final direta (passo
  1), profundidade linear (passo 2), contorno, reducao 640, desfoque 640, composicao do brilho
  (passo 3b), passes direto na textura (passo 4), atalho resolve -> textura; desligados nenhum
  (tamanhos de 1280x720 e 640x360 exatos a esta percentagem)...`; `profundidade linear -> 0F451000`
  e `por compute`; `contorno -> 0F0B9000`, `contorno por compute ativo (...)`; `reducao 640 por
  compute ativo (0FF19000 <- 0F0B9000; 480x270, ...)`, `desfoque 640 por compute ativo (...;
  480x270, ...)`, `composicao do brilho por compute ativo (...; 960x540, ...)`; `passo 4: N
  assinaturas (R prontas ...)` com feitos > 0; na linha perf `atalho=` e `sem_memoria=` > 0 e
  `cargas` perto de 0; `imagem final direta (...)`. Autoteste a 540p (a referencia e a config):
  linha nova `Resolucao interna: draw_resolution_percent=75`; todas as linhas do render nativo
  ("sem imagem final direta (passo 1)", "sem profundidade linear por compute (passo 2)", "sem
  contorno/reducao 640/desfoque 640/composicao do brilho por compute (passo 3b)", "sem passes
  direto na textura (passo 4)", "sem resolve sem memoria (DF)") **igual**; e "TUDO desligado" mais
  lento. Imagem inteira no ecra, HUD e contornos no sitio. A 480p: a linha diz ligados so os
  passes direto na textura e o atalho.

## 120 FPS: nativo e interpolado (17/10, patch 0092)

Texto completo e como portar: [TAXA_DE_QUADROS.md do OptXbox](https://github.com/dfdx047/Optxbox/blob/main/docs/pt-BR/TAXA_DE_QUADROS.md).

- **Bug corrigido:** o `GameActivity.requestSixtyHz` forçava sempre o modo de 60 Hz e
  `Surface.setFrameRate(60, FIXED_SOURCE)`, então o 120 FPS nunca chegava à tela. Agora
  `requestDisplayRate(hz)` pede o modo da taxa de saída pelo id (mesma resolução; para 120 o mais alto
  entre 110 e 125 Hz) e marca a superfície com essa taxa. Sem modo de 120 Hz: log `Ecra: sem modo de
  120 Hz nesta tela (maximo 60 Hz): fica o de 60 Hz` e fica a 60. O menu do jogo muda o modo ao vivo.
  A taxa real da tela vai para o runtime (`NativeGameState.setDisplayHz`, no início, 3 s depois de
  cada mudança da superfície e a cada mudança do `DisplayManager`).
- **Launcher e menu do jogo:** taxa de quadros 30 / 60 / 120 (nativo) / 120 (interpolado); os dois
  120 só aparecem em telas de 110 Hz ou mais, com aviso. RP5 (AMOLED 1080p de 60 Hz) e RG Cube só
  mostram 30/60; o **Odin 3 (120 Hz) é o aparelho de teste**. `GameConfig` ganhou `frameInterpolation`
  e `FrameMode`: 120 interpolado grava `frame_rate = "60"`, `dbz3_frame_interpolation = true` e
  `dbz3_frame_interpolation_quality` (2 nas GPUs de Alto/Ultra, 1 nas outras). Predefinições sem
  mudança (mantêm um 120 escolhido).
- **120 nativo, veredito:** `frame_rate = "120"` põe o intervalo de quadros do jogo em 1 (hooks
  `BurstLimit60FpsForceCap`) e o vblank do guest a 120 Hz (`SetGuestVblankRate`, thread `GPU VSync`),
  então o jogo desenha 120 quadros diferentes. A simulação do BL segue o tempo real (o autor do
  Recompiled diz isso no código e o PC tem 120/144/sem limite, com as correções da trava da pausa /
  sair da luta / Reset Standing Position, do tempo de jogo e dos relógios das cenas), então a
  velocidade fica normal por construção. Fica **experimental**: não temos o executável para ler o
  código dos ticks, cenas e online não foram testados a 120, e a GPU tem de fazer cada quadro em 8,3 ms
  (o RP5 faz 13,5-20 ms). Teste no Odin 3: `game_fps` ~120 na linha perf e o relógio do round a descer
  1 segundo por segundo real (cronômetro).
- **120 interpolado (patch 0092, SDK):** no presenter Vulkan, para cada quadro novo do jogo apresenta
  primeiro um quadro gerado a meio caminho entre o anterior e o novo, depois o novo; a FIFO separa-os
  por um vblank (sem timer). Roda na saída do guest, antes do bilinear/CAS/FSR (genérico, na
  resolução do jogo, o quadro gerado passa pelos mesmos efeitos; custo: os efeitos rodam 2x por quadro
  do jogo). Quatro compute shaders (`src/ui/shaders/dbz3_frame_interp.comp`, SPIR-V pré-compilado):
  pirâmide Y/U/V a 1/4 e 1/16 em rgba8; busca simétrica grossa ±3 texels a 1/16 (±48 px) com meio
  texel de refinamento; refinamento por bloco de 8x8 (5 vetores + zero, ±1 e ±0,5 texel a 1/4);
  por pixel o melhor de {vetor interpolado, vetor do bloco, (4 vizinhos na qualidade 2), zero} pelo
  erro de cor mais uma penalização de coerência (o bloco onde cada amostra cai tem de se mexer com o
  vetor). Pixel igual nos dois quadros = o novo (HUD nítido); mais da metade dos blocos sem par = corte
  de cena = o novo; amostras que discordam = o lado coerente, ou mistura simples. +1 quadro da tela de
  latência (8,3 ms). Só com FIFO e tela ≥ 1,8x os FPS do jogo. Cvars ao vivo:
  `dbz3_frame_interpolation`, `dbz3_frame_interpolation_quality` (0 mistura, 1, 2),
  `dbz3_frame_interpolation_display_hz` (forçar a taxa; 0 = do Android).
- **Custo estimado** (contagem de operações, 720p): qualidade 1 ~25 M amostras, ~2-3 ms numa Adreno 650;
  qualidade 2 ~43 M, ~4 ms na A650 e ~1-1,5 ms na Adreno 830 do Odin 3. O valor real sai na linha perf
  (timestamps da GPU).
- **Validado fora do aparelho** com `tools/frame_interp_ref.py` (mesmas contas do shader). As
  capturas `.dfc` só têm os resolves do 1.º quadro, então os pares foram feitos de quadros reais
  (imagem final, imagem antes do HUD e profundidade linear de 3 capturas: fundo e personagens com
  movimentos diferentes, HUD parado, referência exata em t = 0,5). PSNR na zona que mexe, qualidade 2
  contra só mistura: céu com motion blur 34,1 / 28,3 dB (normal) e 28,2 / 23,8 (rápido); paisagem com
  textura 33,9 / 25,5 e 28,1 / 21,9; céu liso com personagens pequenos 39,3 / 34,1 e 28,6 / 29,3. Corte
  de cena: o quadro gerado é exatamente o novo. Pontos fracos: rastros semitransparentes de motion blur
  com movimento relativo grande rasgam nas bordas; acima de ~54 px por quadro cai na mistura. Prévia:
  `prev | gerado | atual` e `referência | só mistura | erro x4`.
- **Linha perf:** `game_fps=` e `presented_fps=` em todas, e com a interpolação
  `interp=gerados:N sem_gerar:N gpu:X.XXms recurso:X% cortes:N`. **Autoteste:** linha nova
  `Apresentacao: FIFO_RELAXED, tela 120 Hz, jogo 60 FPS, interpolacao de frames: ativa (...) |
  frame_rate=60 dbz3_frame_interpolation=true` (a interpolação não muda a imagem do jogo, então não é
  um interruptor).
- **A ver no Odin 3** (120 interpolado, `dbz3_perf_logging` ligado): `Ecra: atual ... pedido 120 Hz,
  modo pedido N (120 Hz)`, `Ecra durante o jogo: 120.00 Hz (pedido 120 Hz)`, `dbz3: tela a 120.00 Hz`,
  `VulkanPresenter: Created WxH swapchain ... presentation mode 2` (FIFO) ou `3` (FIFO_RELAXED),
  `dbz3: interpolacao de frames: imagens 1280x720 (k=1, luma 320x180 e 80x45, blocos 160x90)`,
  `dbz3: interpolacao de frames: ativa (tela 120 Hz, jogo 60 FPS -> 120, FIFO..., 1280x720, qualidade
  2)`; na perf `game_fps` ~60, `presented_fps` ~120, `gerados` ~300 por janela, `gpu` < 2 ms,
  `recurso` de poucos %, `cortes` só em trocas de câmera. Na tela: HUD parado e nítido, personagens
  sem fantasmas duplos. Num aparelho de 60 Hz: `a espera: tela de 60 Hz, precisa de 108 Hz para 60
  FPS` e `presented_fps` = `game_fps`.

## Launcher e menu do jogo (rodada de 16/10)

- Ícone novo (17/10): onda de choque com DB de metal ao centro. Anel de choque rasgado com aro
  lavanda, pedras a voar com rasto, brasas e fumo; o "DB" de metal dourado-laranja (bisel, faixa
  escura, contorno escuro com aro laranja, corte diagonal, brilho em estrela) fica à frente do
  núcleo, que serve de contraluz. Adaptativo: fundo vetorial escuro, frente PNG com alfa por
  densidade (`drawable-*dpi`), camada monocromática (silhueta do DB + anel) para ícones temáticos,
  mipmaps legados quadrado/redondo. Tudo gerado por `tools/icon_gen.py` (numpy + Pillow).
- Palco do Inicio novo (17/10): choque de raios visto ao longe, sem personagens nem figuras.
  Raio violeta da esquerda (passa abaixo do logotipo) e dourado da direita, com nucleo branco,
  brilho turbulento, espiral e riscos a correr para o centro; esfera branca que vai e vem (o
  empurra-empurra), arcos eletricos, faiscas e onda de choque a cada 3 s; chao rachado a
  brilhar, mesas no horizonte, pedras a levitar, poeira, brasas e linhas de velocidade fracas.
  So Canvas do Compose (aditivo, ciclo de 12 s, ~170 desenhos e ~45 paths por quadro, nada
  recompoe); rachas, mesas e pedras em cache por tamanho. Animacoes do sistema desligadas: quadro
  parado. Prototipo/previas em `tools/stage_proto.py` (numpy + Pillow, o mesmo algoritmo).
- Tema novo tirado dos menus do proprio Burst Limit (violeta, borda creme, dourado, lavanda),
  escuro por omissao. Inicio com o palco animado e os links: Discord, YouTube, GitHub, Ko-fi.
- Abas: Inicio, Desempenho (predefinicoes Leve/Equilibrado/Alto/Ultra pelo SoC + 30/60/120 FPS),
  Controles, Jogo (opcoes do BL, saves, online), Mods, Texturas, Drivers, Depuracao (logs,
  capturas, atalhos do renderizador), Configuracoes (o app), Sobre.
- Importacao da ISO: copia pelo kernel (`transferTo`), velocidade e tempo restante; verificacao
  do disco pelo cabecalho do XEX (title ID 424107DC, media ID USA 036A7E66): outro jogo, outra
  versao, executavel alterado, imagem cortada, CPK danificado. Lista da instalacao para o
  Verificar notar ficheiros cortados.
- Controles por toque: botoes de comando com direcao (especial 1/2, golpe final, dash, agarrar)
  que mandam a direcao e o botao juntos; lado do P1 pela camara + posicao dos lutadores (a
  confirmar nos logs, `controles.log` mostra `lado=`); o L3 (carregar ki) so aparece com a opcao
  ligada. Layouts antigos ganham os botoes novos (versao 2).
- Menu do jogo novo do Android (AndroidMenu.kt), por toque e por comando (Back + Start, pausa ou
  engrenagem): Pausa (camara livre, congelar, sair), Imagem, Efeitos (FOV), Jogo, Controles,
  Save (exportar/importar pelos comandos `save_export`/`save_import`), Sistema (captura). Sons
  do proprio jogo. O menu do PC deixou de existir no Android (patches/game/0004).

## Como compilar (PC Linux)

Precisa de: Android Studio ou SDK de linha de comando com o **NDK 28.2.13676358**, JDK 17,
clang 20+, cmake 3.25+, ninja, e os pacotes de desenvolvimento que o SDK pede no Linux para a
ferramenta de codegen (x11-xcb e wayland-client, por pkg-config). A cópia do jogo: o `default.xex`
da versão **USA** (SHA-1 `aec598f88cf51181fc377b148e0b1ad30db4485c`).

```bash
git clone https://github.com/dfdx047/Dragon-Burst.git && cd Dragon-Burst
bash tools/setup-external.sh                 # clona o BL na versão fixada + submódulos usados
bash tools/apply-patches.sh                  # patches do SDK e do jogo (pode repetir)
bash tools/codegen.sh ~/Jogos/BurstLimit.iso  # o .iso USA (ou o default.xex); gera generated/default
# CMake: usa o do sistema (PATH) automaticamente; ou cmake.dir=... no local.properties
./gradlew assembleRelease
```

O APK sai em `app/build/outputs/apk/release/`. Instalar nos aparelhos ligados por adb:
`bash tools/install.sh` (todos), `bash tools/install.sh rp5` ou `bash tools/install.sh cube`. `-Pdf.uiOnly=true` compila só o launcher (rápido,
sem o jogo).

No aparelho: abrir o Dragon Burst → **Importar .iso** (ou pasta com `default.xex` + `LONG2DATA/`) →
JOGAR.

## Próximos passos

1. **Rodada 1 do OptXbox nos dois aparelhos** ([guia do OptXbox](https://github.com/dfdx047/Optxbox/blob/main/docs/pt-BR/FERRAMENTA.md)): instalar, importar, jogar
   menus + uma luta de Versus com "Diagnóstico de desempenho" ligado, exportar os logs (aba Sobre) e
   analisar com `--prefix "dbz3: " --adb-pattern "DragonBurst-logs-*.zip"`. Objetivo: o jogo abre,
   chega aos menus e entra em luta; anotar FPS/GPU ms por aparelho.
2. Corrigir o que a rodada 1 mostrar (crashes de arranque, formatos de textura, áudio, entrada).
3. **Mali (RG Cube)**: medir a predefinicao Leve (540p, 30 FPS) e o Minimo (480p) com o 0089
   (`draw_resolution_percent` no Vulkan).
4. **Mapa D3D do Burst Limit** (`tools/d3d_map.py`, [guia de port](https://github.com/dfdx047/Optxbox/blob/main/docs/pt-BR/GUIA_DE_PORT.md)): achar o
   endereço da espera do ring buffer do D3D do BL para aplicar o atalho de espera do Dragon Fury.
5. **Online pela internet**: cliente WebSocket do lobby por JNI (o ICE/libjuice já compila);
   depois Ranked/placares. Testar o online em rede local entre os dois aparelhos antes.
6. Packs de texturas e mods reais nos aparelhos (RAM com packs grandes no RG Cube).

## Atualizar o Burst Limit Recompiled

1. Mudar `BL_COMMIT` (e `SDK_COMMIT`, se o submódulo mudou) em `tools/versions.env`.
2. Num clone limpo: `setup-external.sh` → `apply-patches.sh`. O que não aplicar: refazer o patch no
   repositório de trabalho do SDK (cherry-pick dos commits dos patches sobre o novo `burstlimit`) e
   exportar de novo (`git diff --full-index -M c~1 c` por commit, nome = assunto do commit).
3. `codegen.sh` de novo (o código gerado depende da versão do BL), build, rodada do OptXbox.

## Diferenças de nomes em relação ao Dragon Fury

- Prefixo de log `dbz3:` e cvars `dbz3_*` mantidos de propósito: são o contrato do OptXbox
  ([DIAGNOSTICO.md do OptXbox](https://github.com/dfdx047/Optxbox/blob/main/docs/pt-BR/DIAGNOSTICO.md))
  e dos patches 0000–0048.
- Config do runtime: `burstlimit.toml` (o mesmo nome do PC). Logs exportados:
  `DragonBurst-logs-<data>.zip` (com `aparelho.txt`, `cvars.txt`, `logcat.txt`, `logs/`,
  `burstlimit.toml`).
- String de versão online: `dragonburst-android` (salas só entre aparelhos com o mesmo APK).

## Publicar uma release

1. Subir `versionCode` (+1 a cada release) e `versionName` em `app/build.gradle.kts`; nova entrada no
   `CHANGELOG.md` (PT, EN, ES) e o texto da release em `docs/release/vX.Y.Z.md`.
2. `./gradlew assembleRelease` e testar o APK de `app/build/outputs/apk/release/`.
3. No GitHub: release com a tag `vX.Y.Z` (ou `X.Y.Z`: o app tira o `v`) e o mesmo número do
   `versionName`; corpo = o conteúdo de `docs/release/vX.Y.Z.md` (seções `## PT`, `## EN`, `## ES`, que o
   app separa por idioma; o pop-up mostra o texto sem formatação, por isso nada de `**` nem `###`).
   Anexar o APK como asset com nome terminado em `.apk` (ex.: `DragonBurst-X.Y.Z.apk`): o app baixa o
   primeiro `.apk` dos assets; sem ele só abre a página. Não marcar como pre-release nem rascunho (o app
   lê `releases/latest`, que ignora os dois).
4. **Assinatura:** o APK de uma atualização só instala por cima se for assinado com a mesma chave do
   instalado. Hoje o release é assinado com a chave de debug (`signingConfigs.getByName("debug")`, o
   `~/.android/debug.keystore` do PC que compila): guardar uma cópia desse arquivo e compilar todas as
   releases com ele. Trocar de chave depois obriga os jogadores a desinstalar (perdem o jogo importado e os
   saves que não exportaram).

## Ferramentas de desenvolvimento

- **OptXbox** (repositório à parte): guia de uso ([FERRAMENTA.md](https://github.com/dfdx047/Optxbox/blob/main/docs/pt-BR/FERRAMENTA.md) ·
  [TOOL.md](https://github.com/dfdx047/Optxbox/blob/main/docs/en/TOOL.md)), guia de port Xbox 360 → Android
  ([GUIA_DE_PORT.md](https://github.com/dfdx047/Optxbox/blob/main/docs/pt-BR/GUIA_DE_PORT.md) ·
  [PORTING_GUIDE.md](https://github.com/dfdx047/Optxbox/blob/main/docs/en/PORTING_GUIDE.md)), render nativo
  passo a passo ([RENDER_NATIVO.md](https://github.com/dfdx047/Optxbox/blob/main/docs/pt-BR/RENDER_NATIVO.md) ·
  [NATIVE_RENDERER.md](https://github.com/dfdx047/Optxbox/blob/main/docs/en/NATIVE_RENDERER.md)) e o formato
  dos logs e capturas ([DIAGNOSTICO.md](https://github.com/dfdx047/Optxbox/blob/main/docs/pt-BR/DIAGNOSTICO.md) ·
  [DIAGNOSTICS.md](https://github.com/dfdx047/Optxbox/blob/main/docs/en/DIAGNOSTICS.md)). Rodada de testes
  no PC: `cd ~/Optxbox && python3 -m optxbox report --adb --adb-pattern "DragonBurst-logs-*.zip"`.
  (Estes links ficavam em `docs/GUIA_OPTXBOX.md` e `docs/GUIA_PORT_XBOX360_ANDROID.md`, removidos na 0.1.0.)
- `tools/` deste repositório: `setup-external.sh`, `apply-patches.sh`, `codegen.sh` (+ `xiso_extract.py`,
  `versions.env`) e `install.sh` para o build; `gpu-ab.sh` (A/B de cvars com log e captura de tela),
  `profile.sh` e `profile-hitch.sh` (simpleperf), `extract_funcs.py` (funções do código gerado para as
  pilhas do vigia), `dfc_parse.py` e `dfc_textures.py` (capturas `.dfc`), `d3d_map.py` +
  `d3d_trace_gen.py` (rastreio D3D, `-Pdf.d3dTrace=true`), as referências numpy `ref_contour.py`,
  `ref_glow.py`, `ref_below1x.py`, `rt_after_quad.py`, `frame_interp_ref.py`, e `icon_gen.py` /
  `stage_proto.py` (ícone e palco do Início).

## Changelog de desenvolvimento (até a 0.1.0)

O que estava no `CHANGELOG.md` antes de ele virar as notas para os jogadores, sem mudanças:

### 0.1.0 (em preparação)

Primeira base do port, sobre o Burst Limit Recompiled v5.2 (`bb60bc1`).

- 49 patches do Dragon Fury refeitos sobre o SDK do Burst Limit (0000–0048).
- Substituição de texturas e `draw_overrides` no Vulkan (0049, 0050), sockets do online em POSIX (0051),
  fontes do Android no menu do jogo (0052).
- Jogo: caminhos Android, HTTP por JNI, sons de menu pelo SDL3, saves, itens só de PC escondidos do menu.
- Launcher: importar `.iso`/pasta (só USA), opções do BL, mods, packs e extração de texturas, saves,
  online em rede local, controles de toque com os comandos do BL e layout da câmera livre.
- 120 FPS (patch 0092): a tela segue a taxa escolhida (antes ficava sempre a 60 Hz); 120 (nativo,
  experimental) e 120 (interpolado: um quadro gerado entre cada dois no presenter Vulkan) em telas de
  120 Hz; linha perf com `game_fps` e `presented_fps`.
