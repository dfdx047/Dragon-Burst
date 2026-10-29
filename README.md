<p align="center"><img src="docs/banner.png" alt="Dragon Burst" width="820"></p>

# Dragon Burst

**Português** · [English](README.en.md) · [Español](README.es.md)

Port nativo para Android do **[DBZ Burst Limit Recompiled](https://github.com/iExplosiveRage/DBZ-Burst-Limit-Recompiled)**
(iExplosiveRage), a recompilação estática do Dragon Ball Z: Burst Limit de Xbox 360. O código do jogo roda
nativo em arm64 (não é emulado), sobre o runtime do ReXGlue SDK em Vulkan e a base do Dragon Fury (port
Android do DBZ Budokai 3 HD).

> **Nenhum arquivo do jogo vem incluído.** Você precisa da sua própria cópia legal, versão **USA (NTSC-U)**.
>
> **Versão 0.2.0.** Testada no Retroid Pocket 5 e no Anbernic RG Cube. O que ainda não foi
> testado num aparelho está marcado como **experimental**.

## O que tem

**Launcher**
- Importar o jogo direto do `.iso` ou de uma pasta extraída (`default.xex` + `LONG2DATA/`). Só a versão
  USA: o app confere o executável e avisa se o disco é de outro jogo, de outra região, alterado ou cortado.
  **Verificar arquivos** confere a instalação depois.
- Abas: Início, Desempenho, Controles, Jogo, Mods, Texturas, Drivers, Depuração, Configurações e Sobre.
  Tema próprio, idiomas português, inglês e espanhol, atalho na tela inicial.
- **Atualização automática**: ao abrir, o app procura uma versão nova nas Releases do GitHub, mostra o
  que mudou (no idioma do app), baixa o APK e abre o instalador.

**Jogo** (as opções da versão de PC que funcionam no Android)
- 30 FPS (original) ou 60 FPS, e os modos de 120 FPS (abaixo).
- Roupas do modo história, cenários extras, começar transformado (RB/LB), carregar ki (segurar L3),
  personagens extras de mods, ícones de botão Xbox/PlayStation/Switch, "Online" em vez de "Xbox LIVE",
  sons de menu, campo de visão, câmera livre e congelar o jogo (modo foto).
- Menu do jogo próprio do Android (Back + Start, ou o botão de pausa): imagem, efeitos, jogo, controles,
  save e sistema, com o jogo pausado.
- **Saves**: exportar/importar pelo menu do jogo; o mesmo `.zip` da versão de PC e do Xenia. A aba Jogo
  copia os saves de e para a pasta Download.

**Mods e texturas**
- Mods no formato da versão de PC (`mods/`, `mod.toml`): instalar `.zip`, ligar/desligar, tipo detectado,
  aviso de conflitos e de pasta a mais.
- **Packs de texturas HD** (a substituição de texturas foi portada para o Vulkan): vários packs, um ativo,
  conferência dos nomes e formatos, pré-carregamento opcional e extração das texturas originais para
  criar packs.

**Gráficos**
- **5 predefinições** (Mínimo, Leve, Equilibrado, Alto, Ultra) com os processadores recomendados para
  cada uma; o app sugere a do seu aparelho.
- **Renderizador**: Auto (padrão), Renut ou Xenia (veja Desempenho).
- Resolução interna **Tela, 480p, 540p, 720p, 1080p (só no Renut), 2K (1440p) ou 4K (2160p)**. "Tela"
  desenha na altura que a imagem ocupa na tela; nas telas pequenas o app sugere-a no primeiro arranque.
- Efeitos do jogo ligáveis (profundidade de campo, brilho, desfoque de movimento, filtro suave), AMD
  FSR 1 + CAS, FXAA, filtro anisotrópico, manter a proporção ou esticar.
- **Drivers Vulkan**: o do sistema ou Turnip (Mesa) via libadrenotools, baixados dos repositórios da
  comunidade ou importados. A aba Drivers mostra o nível de suporte do Turnip para o seu Snapdragon.
  **Turnip é experimental neste port** (veja os aparelhos testados).

**120 FPS** (só em telas de 120 Hz; nas outras as opções não aparecem)
- **120 (interpolado)**: o jogo roda a 60 e a GPU gera um quadro entre cada dois reais. Mais um quadro
  de latência (cerca de 8 ms); o HUD continua nítido. **Experimental.**
- **120 (nativo)**: o próprio jogo roda a 120, como na versão de PC. A GPU precisa de cada quadro em
  menos de 8,3 ms. **Experimental**: cenas e online não foram verificados a 120.
- Nenhum dos dois foi testado ainda num aparelho de 120 Hz.

**Controles**
- Controles na tela feitos para não cobrir o jogo: D-pad de 8 direções (ou analógico), botões com o
  visual do jogo, transparentes, que aparecem quando você toca e somem quando usa um controle.
- Layouts separados para luta, menus e câmera livre (trocado sozinho), editor de layout e macros
  (combinação ou sequência), botões do port (Ki, Aura Spark, Transformar, Arremesso, Perseguição, menu).
- Controles físicos com zona morta e vibração.

**Online**
- **Rede local (Wi-Fi)**, o método LAN da versão de PC: um aparelho é o anfitrião, o outro digita o IP.
  **Experimental**: ainda não testado entre dois aparelhos. Os dois precisam do mesmo APK.
- O lobby pela internet (Ranked, placares) ainda não está no Android, e não há cross-play com o PC.

**Diagnóstico**
- **Autoteste do render** (menu do jogo → Sistema, cerca de 1,5 min, numa luta offline): desliga cada
  atalho do renderizador, compara as imagens e diz qual estraga a imagem e quanto cada um vale.
- **Exportar logs** (aba Sobre, aba Depuração ou menu do jogo → Sistema): um `.zip` em Download com os
  logs, o resultado do autoteste e a última captura de quadros.

Lista completa, recurso por recurso, contra a versão de PC: [docs/COMPATIBILIDADE.md](docs/COMPATIBILIDADE.md).

## Desempenho

**Renut (0.2.0, padrão).** O renderizador nativo do
[Zandatsu](https://github.com/dfdx047/Zandatsu): desenha o Direct3D do jogo direto em
Vulkan, sem imitar a GPU do Xbox 360. O Auto usa-o sempre, em todas as GPUs (Adreno com o driver do
sistema ou Turnip, Mali); o Xenia fica como opção na tela de Desempenho. Se o jogo fechar, o app avisa e
oferece Exportar logs; só a partir do 2.º fecho seguido pergunta se quer usar o Xenia por agora.

**Xenia.** O Burst Limit Recompiled usa o renderizador do Xenia, que imita a GPU do Xbox 360 e é pesado para um
celular. O Dragon Burst troca as partes mais caras por um **render nativo**, passo a passo, cada um com
interruptor e validado pelo autoteste:

1. a imagem final apresentada direto, sem a última cópia de tela inteira;
2. a profundidade linear calculada por compute, direto do depth buffer;
3. o pós-processamento enxuto (draws vazios e resolves repetidos ou sem leitor saltados) e o contorno, a
   redução, o desfoque e a composição do brilho feitos por compute;
4. os passes do pós-processamento desenhando direto na textura de destino.

Mais as otimizações de GPU do Dragon Fury e os mesmos passos a 540p (patch 0091, ainda não confirmado
no aparelho). No Retroid Pocket 5,
a 720p com todos os efeitos, a luta foi de 12-16 FPS para até 60 FPS. O método e as ferramentas estão no
**[OptXbox](https://github.com/dfdx047/Optxbox)**; o histórico com os números de cada rodada, em
[docs/ESTADO.md](docs/ESTADO.md).

## Aparelhos testados

| Aparelho | Processador / GPU | Driver | Resultado |
|---|---|---|---|
| Retroid Pocket 5 | Snapdragon 865 / Adreno 650 | do sistema (Qualcomm) | Renut a 720p: 60 FPS estáveis na luta e na campanha (GPU 11 ms). Xenia a 720p com todos os efeitos: até 60 FPS na luta, cerca de 47-50 FPS nas cenas pesadas. |
| Retroid Pocket 5, perfil 480+ | Snapdragon 865 / Adreno 650 (GPU limitada) | do sistema / Turnip | Renut com FSR: 60 FPS a 480p e a 540p na luta e na campanha (mínimo 59 a 540p). |
| Retroid Pocket 5 | Snapdragon 865 / Adreno 650 | Turnip (várias versões, Mesa 25.3 e 26.3) | Imagem errada (fundo cor de azeitona, cores trocadas). Use o driver do sistema. |
| Anbernic RG Cube | Unisoc T770 / Mali-G57 MC4 | do sistema | Renut com "Tela" (360p): 60 FPS na luta e na campanha; 480p 59 FPS, 540p 50-52 FPS. Xenia a 720p com efeitos: 22-24 FPS. |
| AYN Odin 3 | — | — | Ainda não testado (será o aparelho de teste dos 120 FPS). |

## Hardware

- **Mínimo para 60 FPS:** Snapdragon 865 (Adreno 650) a 720p, com quedas nas cenas pesadas.
- **Entrada:** classe Unisoc T770 / Mali-G57 MC4 com o Renut: 60 FPS com a resolução "Tela" numa tela
  pequena (RG Cube, 360p) ou a 480p.
- **Recomendado:** Snapdragon 8 Gen 2 ou mais novo (ex.: AYN Odin 3), que é também o que o modo 120 FPS
  interpolado precisa (além de uma tela de 120 Hz).
- **Não testado:** qualquer coisa abaixo de um Mali-G57 MC4 ou de um Adreno 6xx intermediário. A
  predefinição Mínimo (480p) existe para esses aparelhos, mas não foi medida.
- Sempre: Android 11 ou mais novo, arm64, Vulkan e cerca de 6 GB livres para os dados do jogo.

## Instalar

1. Baixe o APK em [Releases](https://github.com/dfdx047/Dragon-Burst/releases) e instale (o Android pede
   para permitir a instalação de apps desconhecidos).
2. Abra o Dragon Burst e toque em **Importar .iso** (o `.iso` do seu disco) ou **Importar pasta**. Só a
   versão USA (NTSC-U) funciona.
3. Escolha uma predefinição na aba Desempenho (o app sugere a do seu aparelho) e toque em **JOGAR**.

As próximas versões chegam pelo próprio app.

## Aviso legal

Nenhum arquivo do jogo vem incluído e nada é baixado: o app só usa a cópia que você importa. Faça o dump
do seu próprio disco; não peça nem compartilhe ISOs. Projeto de fã, não oficial e sem fins comerciais.
Dragon Ball pertence a Bird Studio/Shueisha, Toei Animation e Bandai Namco.

## Compilar

O código do jogo é gerado a partir do seu próprio `default.xex` USA, por isso o build precisa da sua
cópia. Precisa do NDK 28.2.13676358, JDK 17, clang 20+, cmake e ninja. Passo a passo em
[docs/ESTADO.md](docs/ESTADO.md#como-compilar-pc-linux). Resumo:

```bash
bash tools/setup-external.sh
bash tools/apply-patches.sh
bash tools/codegen.sh /path/to/BurstLimit.iso   # or default.xex
./gradlew assembleRelease
```

`bash tools/install.sh` instala nos aparelhos ligados por adb. Os patches do SDK e do jogo estão em
`patches/`.

## Reportar problemas

1. Numa luta offline, abra o menu do jogo → Sistema → **Autoteste do render** e espere terminar.
2. Toque em **Exportar logs** (no mesmo menu ou na aba Sobre): o arquivo `DragonBurst-logs-*.zip` vai
   para a pasta Download.
3. Abra uma issue no GitHub com o aparelho, a predefinição/opções usadas, o que aconteceu e o `.zip`.

## Créditos

- iExplosiveRage: [DBZ Burst Limit Recompiled](https://github.com/iExplosiveRage/DBZ-Burst-Limit-Recompiled).
- Equipe do ReXGlue SDK e o projeto [Xenia](https://github.com/xenia-project/xenia), de onde vem o
  renderizador.
- NovaPowers (DBZ Budokai 3 HD), de cujo port Android, o Dragon Fury, este parte.
- SDL, libadrenotools (bylaws) e quem compila os drivers Turnip/Mesa (Mr. Purple, StevenMXZ, K11MCH1).

Desenvolvido por dfdx047 · [Discord](https://discord.gg/nXSANX9V6k) · [YouTube](https://www.youtube.com/@DFDX47) ·
[Ko-fi](https://ko-fi.com/dfdx047)
