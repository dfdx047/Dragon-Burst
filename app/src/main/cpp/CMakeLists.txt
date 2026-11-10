# Dragon Burst - nucleo nativo
#
# Compila o SDK ReXGlue do Burst Limit Recompiled (external/burstlimit/thirdparty/
# rexglue-sdk, branch burstlimit, com os patches Android de patches/) e o jogo
# recompilado (external/burstlimit, com o generated/default do codegen e os patches
# de patches/game/) numa biblioteca libgame.so que o SDLActivity carrega (encriptada no APK; ver o fim deste
# ficheiro).
#
# Resultado (todas em arm64-v8a, empacotadas no APK):
#   libmain.so          carregador: le o default.xex do jogador (chave do libgame.so)
#   libgame.so          jogo recompilado + codigo do Burst Limit Recompiled + Renut + shaders traduzidos + glue
#                       Android; NAO vai no APK: so encriptado (assets/libgame.zdat, tools/pack-libgame.py)
#   librexruntime.so    runtime ReXGlue (kernel, memoria, audio, input, UI, online)
#   librexgpu-xenos.so  plugin da GPU Xenos (Vulkan)
#   libSDL3.so          SDL3 (janela, eventos, audio, comandos)

cmake_minimum_required(VERSION 3.25)
project(dragonburst_android LANGUAGES C CXX)

get_filename_component(DB_REPO_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../.." ABSOLUTE)
set(BL_ROOT     "${DB_REPO_ROOT}/external/burstlimit")
set(REXSDK_ROOT "${BL_ROOT}/thirdparty/rexglue-sdk")
set(BL_GENERATED "${BL_ROOT}/generated/default")

#-----------------------------------------------------------------------------
# Verificacoes antes de comecar (mensagens claras em vez de erros obscuros)
#-----------------------------------------------------------------------------
if(NOT EXISTS "${REXSDK_ROOT}/CMakeLists.txt")
    message(FATAL_ERROR
        "SDK ReXGlue nao encontrado em ${REXSDK_ROOT}.\n"
        "Corre na raiz do repositorio:  bash tools/setup-external.sh")
endif()
if(NOT EXISTS "${REXSDK_ROOT}/include/rex/main_android.h")
    message(FATAL_ERROR
        "Os patches Android ainda nao foram aplicados ao SDK.\n"
        "Corre na raiz do repositorio:  bash tools/apply-patches.sh")
endif()
# Todos os patches de patches/ tem de estar aplicados (o script regista-os em
# .dragonburst-patches). Evita compilar um SDK desatualizado sem dar por isso.
function(db_check_patches PATCH_GLOB TARGET_DIR)
    file(GLOB PATCH_FILES "${PATCH_GLOB}")
    set(STAMP "${TARGET_DIR}/.dragonburst-patches")
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${PATCH_FILES} "${STAMP}")
    set(APPLIED "")
    if(EXISTS "${STAMP}")
        file(STRINGS "${STAMP}" APPLIED)
    endif()
    foreach(PATCH ${PATCH_FILES})
        get_filename_component(PATCH_NAME "${PATCH}" NAME)
        if(NOT PATCH_NAME IN_LIST APPLIED)
            message(FATAL_ERROR
                "O patch ${PATCH_NAME} ainda nao foi aplicado em ${TARGET_DIR}.\n"
                "Corre na raiz do repositorio:  bash tools/apply-patches.sh")
        endif()
    endforeach()
endfunction()
db_check_patches("${DB_REPO_ROOT}/patches/[0-9][0-9][0-9][0-9]-*.patch" "${REXSDK_ROOT}")
db_check_patches("${DB_REPO_ROOT}/patches/game/[0-9][0-9][0-9][0-9]-*.patch" "${BL_ROOT}")
if(NOT EXISTS "${BL_GENERATED}/sources.cmake")
    message(FATAL_ERROR
        "Falta ${BL_GENERATED}/sources.cmake (codigo recompilado do default.xex).\n"
        "Corre na raiz do repositorio:  bash tools/codegen.sh /caminho/para/default.xex")
endif()

#-----------------------------------------------------------------------------
# SDK ReXGlue
#-----------------------------------------------------------------------------
# O SDK fixa a pasta de saida em out/<plataforma>; aqui apontamo-la para a
# pasta que o Gradle espera, para as .so entrarem no APK.
set(REXGLUE_OUTPUT_DIR "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}" CACHE STRING "" FORCE)
set(REXGLUE_USE_VULKAN ON CACHE BOOL "" FORCE)
set(REXGLUE_ENABLE_TRACY OFF CACHE BOOL "" FORCE)
set(REXGLUE_BUILD_TESTS OFF CACHE BOOL "" FORCE)
# DLSS / FSR 4 / FidelityFX 2-3 sao so D3D12 (Windows).
set(REXGLUE_ENABLE_DLSS OFF CACHE BOOL "" FORCE)
set(REXGLUE_ENABLE_FSR_SDK OFF CACHE BOOL "" FORCE)
set(REXGLUE_ENABLE_FIDELITYFX OFF CACHE BOOL "" FORCE)
# ARMv8.2-A tambem no SDK (runtime, plugin da GPU, terceiros), nao so no jogo: os
# atomicos LSE em linha substituem as chamadas __aarch64_cas*/ldadd*, que no port do
# Raging Blast 2 (RB2-Android) custavam varios % das threads do command processor e
# da UI. Os dois aparelhos de teste (Snapdragon 865 e Unisoc T770) sao ARMv8.2.
# DB_LEGACY_ARMV8: o conjunto ARMv8.0 para CPUs sem LSE (Snapdragon 660/665 / Kryo 260, Exynos 9611: so Cortex-A73/A53; o A76 do Snapdragon 675/730 ja e v8.2).
# Os atomicos passam a ser chamadas __aarch64_* (outline atomics), que usam LSE em runtime onde existir. O APK leva
# os dois conjuntos (APK universal): o Gradle corre este CMakeLists uma segunda vez com DB_LEGACY_ARMV8=ON e
# DB_LIB_SUFFIX=_v80 (tarefa legacyNative do app/build.gradle.kts), e o GameLib.kt escolhe pelo HWCAP do CPU.
option(DB_LEGACY_ARMV8 "Compila para ARMv8.0 (sem LSE/v8.2 em linha)" OFF)
set(DB_LIB_SUFFIX "" CACHE STRING "Sufixo das .so deste conjunto (\"_v80\" no ARMv8.0)")
set(DB_MARCH_V80 -march=armv8-a -moutline-atomics)
if(DB_LEGACY_ARMV8)
    set(DB_MARCH ${DB_MARCH_V80})
else()
    set(DB_MARCH -march=armv8.2-a)
endif()
list(JOIN DB_MARCH " " DB_MARCH_STR)
string(APPEND CMAKE_C_FLAGS " ${DB_MARCH_STR}")
string(APPEND CMAKE_CXX_FLAGS " ${DB_MARCH_STR}")
add_subdirectory("${REXSDK_ROOT}" rexglue-sdk)

# APK universal: o runtime e o plugin da GPU de cada conjunto tem nome proprio (librexruntime_v80.so,
# librexgpu-xenos_v80.so); o libgame.so de cada conjunto liga ao runtime do mesmo conjunto e o runtime carrega o
# plugin do mesmo conjunto (patch 0197 do SDK).
if(DB_LIB_SUFFIX)
    set_target_properties(rexruntime PROPERTIES OUTPUT_NAME "rexruntime${DB_LIB_SUFFIX}")
    set_target_properties(rexgpu-xenos PROPERTIES OUTPUT_NAME "rexgpu-xenos${DB_LIB_SUFFIX}")
endif()
target_compile_definitions(rexruntime PRIVATE "REX_GPU_PLUGIN_SUFFIX=\"${DB_LIB_SUFFIX}\"")
# So um libSDL3.so, um libmain.so e os hooks do adrenotools no APK, para os dois conjuntos: compilados em ARMv8.0
# (outline atomics, LSE em runtime onde existir). Nao sao caminho quente. Ficam depois do SDK para prevalecerem
# sobre o -march=armv8.2-a global (o ultimo -march ganha).
foreach(T SDL3-shared main_hook file_redirect_hook gsl_alloc_hook hook_impl)
    if(TARGET ${T})
        target_compile_options(${T} PRIVATE ${DB_MARCH_V80})
    endif()
endforeach()

#-----------------------------------------------------------------------------
# libadrenotools: permite carregar um driver Vulkan alternativo (Turnip/Mesa)
# em vez do driver Qualcomm do sistema. So e usado se a app definir
# REX_VULKAN_DRIVER_DIR/REX_VULKAN_DRIVER_NAME (ver GameActivity e o patch 0009).
#-----------------------------------------------------------------------------
include(FetchContent)
if(NOT CMAKE_ANDROID_ARCH_ABI)
    set(CMAKE_ANDROID_ARCH_ABI "${ANDROID_ABI}")
endif()
FetchContent_Declare(adrenotools
    GIT_REPOSITORY https://github.com/bylaws/libadrenotools.git
    GIT_TAG 8fae8ce254dfc1344527e05301e43f37dea2df80
    GIT_SUBMODULES_RECURSE TRUE
)
FetchContent_MakeAvailable(adrenotools)
# vulkan_instance.cpp (no rexui) usa a API; o rexruntime e quem liga a biblioteca.
target_link_libraries(rexui PRIVATE adrenotools)
target_compile_definitions(rexui PRIVATE REX_HAS_ADRENOTOOLS=1)
target_link_libraries(rexruntime PRIVATE adrenotools)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

#-----------------------------------------------------------------------------
# Jogo: a mesma lista de fontes do CMakeLists.txt do Burst Limit Recompiled.
#-----------------------------------------------------------------------------
set(BL_SOURCES
    ${BL_ROOT}/src/main.cpp
    ${BL_ROOT}/src/burstlimit_branding.cpp
    ${BL_ROOT}/src/burstlimit_buttons.cpp
    ${BL_ROOT}/src/burstlimit_camera.cpp
    ${BL_ROOT}/src/burstlimit_cpk.cpp
    ${BL_ROOT}/src/burstlimit_forms.cpp
    ${BL_ROOT}/src/burstlimit_install.cpp
    ${BL_ROOT}/src/burstlimit_kicharge.cpp
    ${BL_ROOT}/src/burstlimit_launcher.cpp
    ${BL_ROOT}/src/burstlimit_discord.cpp
    ${BL_ROOT}/src/burstlimit_menuart.cpp
    ${BL_ROOT}/src/burstlimit_menusound.cpp
    ${BL_ROOT}/src/burstlimit_mods.cpp
    ${BL_ROOT}/src/burstlimit_netinput.cpp
    ${BL_ROOT}/src/burstlimit_online.cpp
    ${BL_ROOT}/src/burstlimit_patches.cpp
    ${BL_ROOT}/src/burstlimit_ranked.cpp
    ${BL_ROOT}/src/burstlimit_saves.cpp
    ${BL_ROOT}/src/burstlimit_slots.cpp
    ${BL_ROOT}/src/burstlimit_stages.cpp
    # v7.3: Drama on/off, Exit Game, regras das salas online, entrar com qualquer
    # comando, quick fight (testes) e widescreen (projecao Hor+; SDK 0186).
    # Fora no Android: burstlimit_native*.cpp (renderizador nativo D3D12 do PC) e
    # burstlimit_achievements.cpp (Media Foundation / janelas do Windows).
    ${BL_ROOT}/src/burstlimit_drama.cpp
    ${BL_ROOT}/src/burstlimit_exit.cpp
    ${BL_ROOT}/src/burstlimit_online_rules.cpp
    ${BL_ROOT}/src/burstlimit_quickfight.cpp
    ${BL_ROOT}/src/burstlimit_signin.cpp
    ${BL_ROOT}/src/burstlimit_widescreen.cpp
)

# Codigo recompilado do default.xex (define GENERATED_SOURCES, caminhos absolutos).
include("${BL_GENERATED}/sources.cmake")

# Opcoes do codigo recompilado:
#  - so tabelas de linhas no debug info (os .o ficam pequenos e o link rapido,
#    mas os crashes no logcat continuam a mostrar ficheiro:linha);
#  - sem contracao de FMA: em ARM64 o clang funde a*b+c por omissao e isso
#    mudaria os resultados de virgula flutuante em relacao ao PowerPC (e o online
#    entre aparelhos/PC dessincronizava);
#  - o cabecalho pre-compilado que o codegen gera (burstlimit_pch.h).
set_source_files_properties(${GENERATED_SOURCES} PROPERTIES
    COMPILE_OPTIONS "-gline-tables-only;-ffp-contract=off;-fno-strict-aliasing")

add_library(burstlimit_recomp OBJECT ${GENERATED_SOURCES})
target_include_directories(burstlimit_recomp PRIVATE "${BL_ROOT}" "${BL_ROOT}/src" "${BL_GENERATED}")
target_link_libraries(burstlimit_recomp PRIVATE rex::runtime)
target_precompile_headers(burstlimit_recomp PRIVATE "${BL_GENERATED}/burstlimit_pch.h")
target_compile_options(burstlimit_recomp PRIVATE ${DB_MARCH} -ffp-contract=off)

add_library(game SHARED ${BL_SOURCES} $<TARGET_OBJECTS:burstlimit_recomp>)

target_sources(game PRIVATE
    # Pausa do jogo por baixo dos menus do port (patches/game/0002).
    "${CMAKE_CURRENT_LIST_DIR}/android_hooks.cpp"
    # Estado do jogo para os controles por toque e o menu do port (JNI).
    "${CMAKE_CURRENT_LIST_DIR}/game_state_jni.cpp"
    # HTTP(S) do ecra inicial/online pela pilha do Android (JNI).
    "${CMAKE_CURRENT_LIST_DIR}/android_http.cpp"
    # Vigia: pilhas de todas as threads em logs/watchdog.txt se o jogo encravar.
    "${CMAKE_CURRENT_LIST_DIR}/watchdog.cpp"
    # O que o BL v7.3 chama de burstlimit_achievements.cpp (fora do build).
    "${CMAKE_CURRENT_LIST_DIR}/bl_v7_stubs.cpp"
)

# Rastreio das funcoes D3D candidatas (optxbox d3dmap + tools/d3d_trace_gen.py).
option(DB_D3D_TRACE "Hooks de rastreio da biblioteca D3D (logs/d3d_trace.log)" OFF)
if(DB_D3D_TRACE)
    if(EXISTS "${CMAKE_CURRENT_LIST_DIR}/d3d_trace_hooks.cpp")
        target_sources(game PRIVATE "${CMAKE_CURRENT_LIST_DIR}/d3d_trace_hooks.cpp")
    else()
        message(FATAL_ERROR "DB_D3D_TRACE ligado mas falta d3d_trace_hooks.cpp: corre "
            "python3 tools/d3d_map.py && python3 tools/d3d_trace_gen.py")
    endif()
endif()

target_include_directories(game PRIVATE
    "${BL_ROOT}"
    "${BL_ROOT}/src"
    "${BL_GENERATED}"
    "${REXSDK_ROOT}/thirdparty/imgui"
    "${REXSDK_ROOT}/thirdparty/xxHash"
    # stb_image (o import de saves le zips comprimidos) e nlohmann/json (lobby).
    "${REXSDK_ROOT}/thirdparty/stb"
    "${REXSDK_ROOT}/thirdparty/inja/third_party/include"
    "${CMAKE_BINARY_DIR}/rexglue-sdk/include"
)

target_compile_options(game PRIVATE
    -fno-strict-aliasing
    -ffp-contract=off
)

# Versao online: so ha salas entre builds iguais. Android joga com Android (o
# runtime Vulkan e o x86 do PC podem divergir); o nome deixa isso claro.
set(DB_ONLINE_VERSION "dragonburst-android" CACHE STRING "Versao online (salas so entre iguais)")
target_compile_definitions(game PRIVATE "BURSTLIMIT_VERSION=\"${DB_ONLINE_VERSION}\"")

target_link_libraries(game PRIVATE
    rex::runtime
    rex::gpu-xenos
    imgui::imgui
    android
    log
)
# O jogo toca os sons dos menus pelo SDL3 (patches/game/0001).
if(TARGET SDL3::SDL3-shared)
    target_link_libraries(game PRIVATE SDL3::SDL3-shared)
elseif(TARGET SDL3::SDL3)
    target_link_libraries(game PRIVATE SDL3::SDL3)
endif()

# Acrescenta windowed_app_main_sdl.cpp (com o SDL_main do Android) e
# rex_app.cpp, e as opcoes de plataforma do SDK.
rexglue_configure_target(game GPU_PLUGINS xenos)

# Renut: renderizador nativo de Direct3D do mgr-nx-deko (do Zandatsu), escolhido no arranque com
# swordman_renderer = renut (padrao xenos). Shaders: tools/renut-shaders.sh. Mapa de hooks: docs/renut/.
option(DB_RENUT "Compila o Renut (swordman_renderer = renut)" ON)
if(DB_RENUT)
    include("${DB_REPO_ROOT}/cmake/renut.cmake")
    db_renut_setup(game)
endif()

# Alvo minimo: ARMv8.2-A (Snapdragon 865 / Cortex-A77 e A55 para cima, o que
# inclui o Unisoc T770 do RG Cube). Fica depois do rexglue_configure_target, que
# poe -march=armv8-a, para prevalecer.
target_compile_options(game PRIVATE ${DB_MARCH})

# Chamadas entre funcoes do proprio libgame.so sem PLT (do Zandatsu, "Codegen 1"): as sub_* do codigo gerado tem
# visibilidade padrao e cada chamada entre elas passava por um stub do PLT e pelo GOT. Com -Bsymbolic-functions o
# linker liga-as diretamente; os hooks fortes continuam a ganhar as definicoes fracas no link.
option(DB_BSYMBOLIC "libgame.so com -Bsymbolic-functions" ON)
if(DB_BSYMBOLIC)
    target_link_options(game PRIVATE -Wl,-Bsymbolic-functions)
endif()

#-----------------------------------------------------------------------------
# libgame.so fora do APK (do Zandatsu). O codigo do jogo (gerado, hooks, Renut e os shaders traduzidos) so pode estar
# no aparelho de quem tem o disco: o APK leva o libgame.so encriptado (AES-256-GCM, chave = SHA-256 da imagem PE do
# default.xex) e o libmain.so, que no import le o default.xex do jogador (loader/xex_image.cpp) para o GameLib.kt
# derivar a chave, desencriptar para getFilesDir() e o GameActivity carregar de la. Os hooks ficam no libgame.so
# porque substituem as definicoes fracas sub_X no link (-Bsymbolic-functions); o runtime, o plugin da GPU e o SDL ja
# sao .so a parte.
#-----------------------------------------------------------------------------
set(DB_XEX "${BL_ROOT}/game_data_root/default.xex" CACHE FILEPATH "default.xex do codegen (chave do libgame.so)")
set(DB_PACK_DIR "${DB_REPO_ROOT}/app/build/dragonburst-assets")
set(DB_PACK_NAME "libgame${DB_LIB_SUFFIX}")
add_custom_command(
    OUTPUT "${DB_PACK_DIR}/${DB_PACK_NAME}.zdat" "${DB_PACK_DIR}/${DB_PACK_NAME}.json"
    COMMAND python3 "${DB_REPO_ROOT}/tools/pack-libgame.py" "$<TARGET_FILE:game>" "${CMAKE_STRIP}" "${DB_XEX}"
            "${DB_PACK_DIR}" "${DB_PACK_NAME}"
    DEPENDS game "${DB_REPO_ROOT}/tools/pack-libgame.py" "${CMAKE_CURRENT_LIST_DIR}/loader/xex_image.cpp" "${DB_XEX}"
    COMMENT "libgame.so -> assets/libgame.zdat (encriptado)"
    VERBATIM)
add_custom_target(game_pack DEPENDS "${DB_PACK_DIR}/${DB_PACK_NAME}.zdat" "${DB_PACK_DIR}/${DB_PACK_NAME}.json")

add_library(main SHARED "${CMAKE_CURRENT_LIST_DIR}/loader/game_lib_jni.cpp" "${CMAKE_CURRENT_LIST_DIR}/loader/xex_image.cpp")
target_include_directories(main PRIVATE "${CMAKE_CURRENT_LIST_DIR}/loader" "${REXSDK_ROOT}/thirdparty")
target_link_libraries(main PRIVATE mspack log)
target_compile_options(main PRIVATE ${DB_MARCH_V80})
# O Gradle so compila os alvos da lista (build.gradle.kts): o libgame.so e o pacote vem por aqui.
add_dependencies(main game_pack)
