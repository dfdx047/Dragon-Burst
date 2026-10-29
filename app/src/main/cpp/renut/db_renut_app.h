// Renut no BurstlimitApp (patches/game/0006): o que o MgrApp do Zandatsu (src/mgr_app.h de la) faz nas fases da app,
// em funcoes que o burstlimit_app.h chama. So com DB_RENUT (cmake/renut.cmake); swordman_renderer = renut escolhe-o.
#pragma once

#if defined(DB_RENUT)
#include <cstdio>
#include <cstdlib>
#include <functional>
#include <stdexcept>
#include <string>

#include <rex/logging.h>
#include <rex/rex_app.h>
#if defined(__ANDROID__)
#include <rex/main_android.h>
#endif

#include "renut_engine/gpu/banjo_renut_select.h"
#include "renut_engine/gpu/overlay/app_integration.h"

// renut_glue.cpp: as funcoes Vulkan que o Renut assume (false = fica o Xenia).
bool SwordManRenutDeviceSupported(std::string* why = nullptr);

// OnPreSetup: true com o Renut (sem GPU emulada nem plugin; o Renut desenha na janela com o seu dispositivo Vulkan).
// Se o driver nao tiver o que o Renut precisa (29/10, decisao do dono: nunca o Xenia sozinho), o jogo fecha com o
// motivo no log e em renut_support.txt: o launcher encontra o renut_run.pending, mostra o aviso com o motivo e, do 2.o
// crash seguido em diante, pergunta se o jogador quer o Xenia.
inline bool DbRenutPreSetup(rex::RuntimeConfig& config) {
  if (!banjo::renut_gpu::Active()) return false;
  std::string why;
  const bool supported = SwordManRenutDeviceSupported(&why);
  if (!supported) banjo::renut_gpu::detail::g_active = -1;
#if defined(__ANDROID__)
  if (FILE* f = std::fopen((rex::GetAndroidAppDirectory() / "renut_support.txt").c_str(), "w")) {
    std::fputs(supported ? "ok\n" : ("no\n" + why + "\n").c_str(), f);
    std::fclose(f);
  }
#endif
  if (!supported) {
    REXLOG_ERROR("Renut: o driver nao tem o que o Renut precisa ({}); o jogo fecha (o launcher diz porque)", why);
    rex::FlushLogging();
    std::_Exit(3);
  }
  renut::gpu::ConfigureRuntime(config);
  REXLOG_INFO("graphics: Renut (Direct3D nativo do mgr-nx-deko, plume/Vulkan), sem PM4 nem EDRAM");
  return true;
}

// OnFinalizePaths: a janela ja existe; o dispositivo Vulkan do Renut antes do runtime. Aqui ja nao ha volta ao Xenia.
template <typename Drawer>
inline void DbRenutStart(rex::ui::WindowedAppContext& context, Drawer&& imgui_drawer) {
  if (banjo::renut_gpu::Active() && !renut::gpu::StartRenderer(context, std::forward<Drawer>(imgui_drawer))) {
    REXLOG_ERROR("Renut: o renderizador nao arrancou (swordman_renderer = xenos para usar o Xenia)");
    throw std::runtime_error("Renut: o renderizador nao arrancou");
  }
}
#endif
