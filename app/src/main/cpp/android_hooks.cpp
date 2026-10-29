// Dragon Burst - ganchos especificos do port Android.
//
// Pausa: o Burst Limit chama BurstLimitCameraFrame uma vez por tick do jogo, no loop
// principal (sub_822197D0), antes de o agendador correr as tarefas de update e de
// desenho (burstlimit_manifest.toml). O patch patches/game/0002 chama
// DragonBurstPausePoint() no inicio desse gancho: com a pausa pedida (menus por cima
// do jogo, game_state_jni.cpp), a thread principal do jogo fica aqui parada ate a
// pausa acabar e a logica congela no tick atual. (O GraphicsSystem::Pause do SDK so
// suspende a thread da GPU, o que no Android nao parava o jogo - DBZ3-Android.)
//
// A fazer (docs/COMPATIBILIDADE.md): no DBZ3 a espera do D3D do Xbox 360 por espaco
// no ring buffer da GPU girava a 100% de um nucleo (66% da thread do jogo no 8 Elite).
// No Burst Limit a funcao equivalente tem outro endereco: encontra-la com o mapa D3D
// (optxbox d3dmap --generated) e devolver-lhe a pausa aqui, como no DBZ3.

#include <atomic>
#include <cstdint>
#include <condition_variable>
#include <mutex>

std::atomic<bool> g_dragonburst_pause{false};
std::mutex g_dragonburst_pause_mu;
std::condition_variable g_dragonburst_pause_cv;
// A thread do jogo esta parada no ponto de pausa (para o log da ida ao segundo plano).
std::atomic<bool> g_dragonburst_parked{false};
// Jogo fora da tela (game_state_jni.cpp, nativeSetBackground): alem do tick, param aqui a thread de render do jogo
// (no Swap: Renut e Xenia), o VBlank do Renut (renut_glue.cpp), o vsync do Xenia e as threads do jogo que dormem ou
// cedem a vez (SDK 0189). Mudado com
// g_dragonburst_pause_mu tomado; acorda pelo g_dragonburst_pause_cv.
std::atomic<bool> g_dragonburst_background{false};

// Ticks do loop do jogo: o vigia (watchdog.cpp) deteta travamentos por aqui.
extern std::atomic<uint64_t> g_dragonburst_ticks;

void DragonBurstPausePoint() {
  g_dragonburst_ticks.fetch_add(1, std::memory_order_relaxed);
  if (!g_dragonburst_pause.load(std::memory_order_acquire)) {
    return;
  }
  std::unique_lock<std::mutex> lock(g_dragonburst_pause_mu);
  g_dragonburst_parked.store(true, std::memory_order_release);
  g_dragonburst_pause_cv.wait(lock,
                              [] { return !g_dragonburst_pause.load(std::memory_order_acquire); });
  g_dragonburst_parked.store(false, std::memory_order_release);
}

// Espera enquanto o jogo esta fora da tela. Devolve se esperou (o vsync recomeca a contar dali, sem rajada).
bool DragonBurstWaitForeground() {
  if (!g_dragonburst_background.load(std::memory_order_acquire)) {
    return false;
  }
  std::unique_lock<std::mutex> lock(g_dragonburst_pause_mu);
  g_dragonburst_pause_cv.wait(lock,
                              [] { return !g_dragonburst_background.load(std::memory_order_acquire); });
  return true;
}

// SDK 0100: o VdSwap (Xenia) e o Swap do Renut (renut_glue.cpp) chamam-no no inicio de cada frame, na thread de render
// do jogo. Com os menus o jogo continua a desenhar a imagem parada; fora da tela para aqui.
extern "C" void (*rex_vdswap_hook)(void);
extern "C" bool (*rex_background_wait)(void);
namespace {
void DragonBurstSwapPoint() { DragonBurstWaitForeground(); }
const bool g_hooks_set = [] {
  rex_vdswap_hook = DragonBurstSwapPoint;
  rex_background_wait = DragonBurstWaitForeground;
  return true;
}();
}  // namespace
