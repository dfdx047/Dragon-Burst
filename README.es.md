<p align="center"><img src="docs/banner.png" alt="Dragon Burst" width="820"></p>

# Dragon Burst

[Português](README.md) · [English](README.en.md) · **Español**

Port nativo para Android de **[DBZ Burst Limit Recompiled](https://github.com/iExplosiveRage/DBZ-Burst-Limit-Recompiled)**
(iExplosiveRage), la recompilación estática de Dragon Ball Z: Burst Limit de Xbox 360. El código del juego
se ejecuta de forma nativa en arm64 (no se emula), sobre el runtime del ReXGlue SDK en Vulkan y la base de
Dragon Fury (el port Android de DBZ Budokai 3 HD).

> **No incluye archivos del juego.** Necesitas tu propia copia legal, versión **USA (NTSC-U)**.
>
> **Versión 0.2.0.** Probada en la Retroid Pocket 5 y la Anbernic RG Cube. Lo que aún no se
> probó en un dispositivo está marcado como **experimental**.

## Qué incluye

**Launcher**
- Importar el juego directamente del `.iso` o de una carpeta extraída (`default.xex` + `LONG2DATA/`).
  Solo la versión USA: la app comprueba el ejecutable y avisa si el disco es de otro juego, de otra
  región, está modificado o cortado. **Verificar archivos** comprueba la instalación después.
- Pestañas: Inicio, Rendimiento, Controles, Juego, Mods, Texturas, Drivers, Depuración, Ajustes y Acerca
  de. Tema propio, portugués, inglés y español, acceso directo en la pantalla de inicio.
- **Actualización automática**: al abrirse, la app busca una versión nueva en las Releases de GitHub,
  muestra qué cambió (en el idioma de la app), descarga el APK y abre el instalador.

**Juego** (las opciones de la versión de PC que funcionan en Android)
- 30 FPS (original) o 60 FPS, además de los modos de 120 FPS (abajo).
- Trajes del modo historia, escenarios extra, empezar transformado (RB/LB), cargar ki (mantener L3),
  personajes extra de mods, iconos de botones Xbox/PlayStation/Switch, "Online" en lugar de "Xbox LIVE",
  sonidos de menú, campo de visión, cámara libre y congelar el juego (modo foto).
- Menú del juego propio de Android (Back + Start, o el botón de pausa): imagen, efectos, juego,
  controles, partida y sistema, con el juego en pausa.
- **Partidas**: exportar/importar desde el menú del juego; el mismo `.zip` que la versión de PC y Xenia.
  La pestaña Juego copia las partidas desde y hacia la carpeta Download.

**Mods y texturas**
- Mods en el formato de PC (`mods/`, `mod.toml`): instalar un `.zip`, activar/desactivar, tipo
  detectado, avisos de conflictos y de carpeta de más.
- **Packs de texturas HD** (el reemplazo de texturas portado a Vulkan): varios packs, uno activo,
  comprobación de nombres y formatos, precarga opcional y extracción de las texturas originales para
  crear packs.

**Gráficos**
- **5 preajustes** (Mínimo, Ligero, Equilibrado, Alto, Ultra), cada uno con los procesadores para los que
  está pensado; la app sugiere el de tu dispositivo.
- **Renderizador**: Auto (por defecto), Renut o Xenia (ver Rendimiento).
- Resolución interna **Pantalla, 480p, 540p, 720p, 1080p (solo con el Renut), 2K (1440p) o 4K (2160p)**.
  "Pantalla" dibuja a la altura que ocupa la imagen en la pantalla; en pantallas pequeñas la app la
  sugiere en el primer arranque.
- Efectos del juego activables (profundidad de campo, brillo, desenfoque de movimiento, filtro suave),
  AMD FSR 1 + CAS, FXAA, filtro anisotrópico, mantener la proporción o estirar.
- **Drivers Vulkan**: el del sistema o Turnip (Mesa) mediante libadrenotools, descargados de los
  repositorios de la comunidad o importados. La pestaña Drivers muestra el nivel de soporte de Turnip
  para tu Snapdragon. **Turnip es experimental en este port** (ver los dispositivos probados).

**120 FPS** (solo en pantallas de 120 Hz; en las demás las opciones no aparecen)
- **120 (interpolado)**: el juego corre a 60 y la GPU genera un fotograma entre cada dos reales. Un
  fotograma más de latencia (unos 8 ms); el HUD se mantiene nítido. **Experimental.**
- **120 (nativo)**: el propio juego corre a 120, como en PC. La GPU tiene que dibujar cada fotograma en
  menos de 8,3 ms. **Experimental**: las cinemáticas y el online no se comprobaron a 120.
- Ninguno de los dos se probó todavía en un dispositivo de 120 Hz.

**Controles**
- Controles táctiles pensados para no tapar el juego: cruceta de 8 direcciones (o stick analógico),
  botones con el estilo del juego, transparentes, que aparecen al tocar la pantalla y se ocultan al usar
  un mando.
- Diseños separados para combates, menús y cámara libre (cambio automático), editor de diseño y macros
  (combinación o secuencia), botones del port (Ki, Aura Spark, Transformar, Lanzamiento, Persecución,
  menú).
- Mandos físicos con zona muerta y vibración.

**Online**
- **Red local (Wi-Fi)**, el método LAN de la versión de PC: un dispositivo es el anfitrión y el otro
  escribe su IP. **Experimental**: aún no probado entre dos dispositivos. Los dos necesitan el mismo APK.
- El lobby por internet (Ranked, clasificaciones) aún no está en Android, y no hay juego cruzado con PC.

**Diagnóstico**
- **Autotest del render** (menú del juego → Sistema, cerca de 1,5 min, en un combate offline): desactiva
  cada atajo del renderizador por turnos, compara las imágenes y dice cuál estropea la imagen y cuánto
  vale cada uno.
- **Exportar logs** (pestaña Acerca de, pestaña Depuración o menú del juego → Sistema): un `.zip` en
  Download con los logs, el resultado del autotest y la última captura de fotogramas.

Lista completa, función por función, frente a la versión de PC: [docs/COMPATIBILIDADE.md](docs/COMPATIBILIDADE.md)
(en portugués).

## Rendimiento

**Renut (0.2.0, por defecto).** El renderizador nativo de
[Zandatsu](https://github.com/dfdx047/Zandatsu): dibuja el Direct3D del juego directamente
en Vulkan, sin emular la GPU de Xbox 360. Auto lo usa donde el driver tiene lo que necesita (Adreno con el
driver del sistema o Turnip, Mali) y Xenia en los demás; la pestaña Drivers dice por qué, y si el Renut
cierra el juego al arrancar Auto pasa solo a Xenia. Quedan dos defectos: el pelo de algunos personajes
sale gris y, rara vez, un cuadrado de color parpadea en un golpe especial.

**Xenia.** Burst Limit Recompiled usa el renderizador de Xenia, que imita la GPU de Xbox 360 y es pesado para un
móvil. Dragon Burst sustituye las partes más caras por un **render nativo**, paso a paso, cada uno con su
interruptor y comprobado por el autotest:

1. la imagen final presentada directamente, sin la última copia a pantalla completa del juego;
2. la profundidad lineal calculada por compute, directamente del depth buffer;
3. un posprocesado ligero (draws vacíos y resolves repetidos o sin lector omitidos), con el contorno, la
   reducción, el desenfoque y la composición del brillo hechos por compute;
4. los pases del posprocesado dibujando directamente en sus texturas de destino.

A eso se suman las optimizaciones de GPU de Dragon Fury y los mismos pasos a 540p (parche 0091, aún sin
confirmar en un dispositivo). En
la Retroid Pocket 5, a 720p con todos los efectos, los combates pasaron de 12-16 FPS a hasta 60 FPS. El
método y las herramientas están en **[OptXbox](https://github.com/dfdx047/Optxbox)**; el historial con
los números de cada ronda, en [docs/ESTADO.md](docs/ESTADO.md) (en portugués).

## Dispositivos probados

| Dispositivo | Procesador / GPU | Driver | Resultado |
|---|---|---|---|
| Retroid Pocket 5 | Snapdragon 865 / Adreno 650 | del sistema (Qualcomm) | Renut a 720p: 60 FPS estables en combate y en la campaña (GPU 11 ms). Xenia a 720p con todos los efectos: hasta 60 FPS en combate, unos 47-50 FPS en las escenas pesadas. |
| Retroid Pocket 5, perfil 480+ | Snapdragon 865 / Adreno 650 (GPU limitada) | del sistema / Turnip | Renut: 50 FPS a 480p, 30 FPS estables a 540p; a 540p pidiendo 60, 39 en combate y 54 en la campaña. |
| Retroid Pocket 5 | Snapdragon 865 / Adreno 650 | Turnip (varias versiones, Mesa 25.3 y 26.3) | Imagen incorrecta (fondo color oliva, colores cambiados). Usa el driver del sistema. |
| Anbernic RG Cube | Unisoc T770 / Mali-G57 MC4 | del sistema | Renut con "Pantalla" (360p): 60 FPS en combate y en la campaña; 480p 59 FPS, 540p 50-52 FPS. Xenia a 720p con efectos: 22-24 FPS. |
| AYN Odin 3 | — | — | Aún sin probar (será el dispositivo de prueba de los 120 FPS). |

## Hardware

- **Mínimo para 60 FPS:** Snapdragon 865 (Adreno 650) a 720p, con bajadas en las escenas pesadas.
- **Gama de entrada:** clase Unisoc T770 / Mali-G57 MC4 con el Renut: 60 FPS con la resolución
  "Pantalla" en una pantalla pequeña (RG Cube, 360p) o a 480p.
- **Recomendado:** Snapdragon 8 Gen 2 o más nuevo (p. ej. AYN Odin 3), que es también lo que necesita el
  modo de 120 FPS interpolado (además de una pantalla de 120 Hz).
- **Sin probar:** cualquier cosa por debajo de una Mali-G57 MC4 o de una Adreno 6xx de gama media. El
  preajuste Mínimo (480p) existe para esos dispositivos, pero no se ha medido.
- Siempre: Android 11 o más nuevo, arm64, Vulkan y unos 6 GB libres para los datos del juego.

## Instalar

1. Descarga el APK en [Releases](https://github.com/dfdx047/Dragon-Burst/releases) e instálalo (Android
   pide permitir la instalación de apps desconocidas).
2. Abre Dragon Burst y toca **Importar .iso** (el `.iso` de tu disco) o **Importar carpeta**. Solo
   funciona la versión USA (NTSC-U).
3. Elige un preajuste en la pestaña Rendimiento (la app sugiere el de tu dispositivo) y toca **JUGAR**.

Las próximas versiones llegan desde la propia app.

## Aviso legal

No incluye archivos del juego y no se descarga nada: la app solo usa la copia que importas. Haz el dump
de tu propio disco; no pidas ni compartas ISOs. Proyecto de fans, no oficial y sin fines comerciales.
Dragon Ball pertenece a Bird Studio/Shueisha, Toei Animation y Bandai Namco.

## Compilar

El código del juego se genera a partir de tu propio `default.xex` USA, así que la compilación necesita tu
copia. Necesitas el Android NDK 28.2.13676358, JDK 17, clang 20+, cmake y ninja. Paso a paso en
[docs/ESTADO.md](docs/ESTADO.md#como-compilar-pc-linux) (en portugués). Resumen:

```bash
bash tools/setup-external.sh
bash tools/apply-patches.sh
bash tools/codegen.sh /path/to/BurstLimit.iso   # or default.xex
./gradlew assembleRelease
```

`bash tools/install.sh` instala en los dispositivos conectados por adb. Los parches del SDK y del juego
están en `patches/`.

## Reportar problemas

1. En un combate offline, abre el menú del juego → Sistema → **Autotest del render** y espera a que
   termine.
2. Toca **Exportar logs** (en el mismo menú o en la pestaña Acerca de): `DragonBurst-logs-*.zip` va a la
   carpeta Download.
3. Abre una issue en GitHub con el dispositivo, el preajuste/opciones usados, qué pasó y el `.zip`.

## Créditos

- iExplosiveRage: [DBZ Burst Limit Recompiled](https://github.com/iExplosiveRage/DBZ-Burst-Limit-Recompiled).
- El equipo de ReXGlue SDK y el proyecto [Xenia](https://github.com/xenia-project/xenia), de donde viene
  el renderizador.
- NovaPowers (DBZ Budokai 3 HD), de cuyo port Android, Dragon Fury, parte este.
- SDL, libadrenotools (bylaws) y quienes compilan los drivers Turnip/Mesa (Mr. Purple, StevenMXZ, K11MCH1).

Hecho por dfdx047 · [Discord](https://discord.gg/nXSANX9V6k) · [YouTube](https://www.youtube.com/@DFDX47) ·
[Ko-fi](https://ko-fi.com/dfdx047)
