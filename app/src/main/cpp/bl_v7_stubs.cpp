// Dragon Burst - o que o Burst Limit Recompiled v7.3 chama de ficheiros que ficam fora
// do build Android (app/src/main/cpp/CMakeLists.txt, BL_SOURCES).
//
// burstlimit_achievements.cpp: as conquistas do PC tocam os sons de troféu pelo Media
// Foundation do Windows e abrem janelas ImGui por F1/F7, que o Android nao tem (o menu do
// jogo no Android e o AndroidMenu.kt). Sem conquistas por agora: o popup e a janela nao
// existem e a opcao de teste one_hit_kill (que vive la) fica desligada.

#include <functional>
#include <memory>

#include <rex/ui/imgui_dialog.h>
#include <rex/ui/overlay/achievement_notification.h>

namespace rex::ui {
class ImGuiDrawer;
class ImmediateDrawer;
}  // namespace rex::ui

std::unique_ptr<rex::ui::AchievementNotificationDialog> BurstLimitCreateAchievementToast(
    rex::ui::ImGuiDrawer*, rex::ui::ImmediateDrawer*) {
  return nullptr;
}

std::unique_ptr<rex::ui::ImGuiDialog> BurstLimitCreateAchievementsWindow(
    rex::ui::ImGuiDrawer*, rex::ui::ImmediateDrawer*,
    std::function<void(std::function<void()>)>) {
  return nullptr;
}

void BurstLimitToggleAchievementsWindow() {}

// Chamada pelo BurstLimitOnlineRulesBattleSetup (burstlimit_online_rules.cpp).
void BurstLimitOneHitKillBattleSetup() {}
