// Dragon Burst - estado do jogo para os controles por toque e para o menu do port.
//
// Le a memoria do jogo pelo Runtime (Memory::TranslateVirtual), com os enderecos
// que o Burst Limit Recompiled ja conhece (build US):
//
//   0x841B5130   ponteiro para o objeto da batalha (0 fora da luta;
//                burstlimit_camera.cpp / burstlimit_discord.cpp)
//   0x84239C10   jogadores (ponteiros); lutador = *(jogador + 0x2C), posicao x/y/z
//                (floats) em lutador + 0x90 (burstlimit_netinput.cpp)
//   0x841B2B10   ponteiro para o gestor da camara; a camara ativa em +0x150 tem o
//                olho em +0x00 e o alvo em +0x10 (x, y, z, 1) (burstlimit_camera.cpp)
//
// Nao escreve nada na memoria do jogo.

#include <jni.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>

#include <rex/audio/audio_system.h>
#include <rex/cvar.h>
#include <rex/filesystem.h>
#include <rex/graphics/graphics_system.h>
#include <rex/logging.h>
#include <rex/memory.h>
#include <rex/memory/utils.h>
#include <rex/perf/frame_rate.h>
#include <rex/ui/ui_watchdog.h>
#include <rex/runtime.h>
#include <rex/ui/overlay/quick_menu.h>

// android_hooks.cpp: pausa do jogo no inicio de cada tick.
extern std::atomic<bool> g_dragonburst_pause;
extern std::mutex g_dragonburst_pause_mu;
extern std::condition_variable g_dragonburst_pause_cv;
extern std::atomic<bool> g_dragonburst_parked;
extern std::atomic<bool> g_dragonburst_background;
// renut/renut_glue.cpp (so com o Renut compilado, DB_RENUT).
extern "C" __attribute__((weak)) void dragonburst_renut_background(bool background);
extern std::atomic<uint64_t> g_dragonburst_ticks;

namespace {

constexpr uint32_t kBattlePointer = 0x841B5130;
constexpr uint32_t kPlayerTable = 0x84239C10;
constexpr uint32_t kPlayerFighter = 0x2C;
constexpr uint32_t kFighterPosition = 0x90;
constexpr uint32_t kCameraManagerPointer = 0x841B2B10;
constexpr uint32_t kActiveCamera = 0x150;

rex::memory::Memory* GuestMemory() {
  auto* runtime = rex::Runtime::instance();
  return runtime ? runtime->memory() : nullptr;
}

uint32_t LoadU32(rex::memory::Memory* memory, uint32_t address) {
  return rex::memory::load_and_swap<uint32_t>(memory->TranslateVirtual<uint8_t*>(address));
}

float LoadF32(rex::memory::Memory* memory, uint32_t address) {
  const uint32_t bits = LoadU32(memory, address);
  float f;
  std::memcpy(&f, &bits, sizeof(f));
  return f;
}

// Endereco de guest plausivel (memoria do jogo mapeada, fora dos 0xF... dos
// registos da GPU).
bool GuestPointer(uint32_t address) { return address >= 0x40000000u && address < 0xF0000000u; }

uint32_t BattleAddress(rex::memory::Memory* memory) {
  const uint32_t battle = LoadU32(memory, kBattlePointer);
  return battle < 0xF0000000u ? battle : 0;
}

std::mutex g_pause_mu;
bool g_paused = false;      // pausa aplicada: logica parada no tick e audio parado
bool g_menu_pause = false;  // pedida pelos menus (HelperEngine, nativeSyncPause)
bool g_background = false;  // jogo fora da tela (GameActivity: onPause ou superficie destruida)

}  // namespace

// Lado do P1 no ecra, para os botoes de ajuda com "para a frente" / "para tras"
// (especiais: frente/tras + ki blast, dash: frente + defesa). O Burst Limit e 3D: o
// lado sai da posicao dos dois lutadores projetada no eixo "direita" da camara ativa
// (direita = frente x cima, com a camara a olhar para -Z quando o yaw e 0, como no
// burstlimit_camera.cpp). 0 = desconhecido, 1 = P1 a esquerda, 2 = P1 a direita.
namespace {
int g_last_side = 0;
char g_side_debug[128] = "";
}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_dfdx047_dragonburst_game_NativeGameState_nativeP1Side(JNIEnv*, jclass) {
  rex::memory::Memory* memory = GuestMemory();
  if (!memory || !BattleAddress(memory)) {
    g_last_side = 0;
    return 0;
  }
  float pos[2][3];
  for (int i = 0; i < 2; ++i) {
    const uint32_t player = LoadU32(memory, kPlayerTable + 4 * i);
    if (!GuestPointer(player)) return g_last_side;
    const uint32_t fighter = LoadU32(memory, player + kPlayerFighter);
    if (!GuestPointer(fighter)) return g_last_side;
    for (int a = 0; a < 3; ++a) pos[i][a] = LoadF32(memory, fighter + kFighterPosition + 4 * a);
  }
  const uint32_t manager = LoadU32(memory, kCameraManagerPointer);
  if (!GuestPointer(manager)) return g_last_side;
  const uint32_t camera = manager + kActiveCamera;
  float eye[3], target[3];
  for (int a = 0; a < 3; ++a) {
    eye[a] = LoadF32(memory, camera + 4 * a);
    target[a] = LoadF32(memory, camera + 0x10 + 4 * a);
  }
  const float fx = target[0] - eye[0];
  const float fz = target[2] - eye[2];
  // direita = frente x (0, 1, 0) = (-fz, 0, fx)
  const float rx = -fz;
  const float rz = fx;
  const float d = (pos[1][0] - pos[0][0]) * rx + (pos[1][2] - pos[0][2]) * rz;
  const float len = std::sqrt(rx * rx + rz * rz);
  if (!std::isfinite(d) || !(len > 1e-4f)) return g_last_side;
  snprintf(g_side_debug, sizeof(g_side_debug), "p1=(%.1f,%.1f,%.1f) p2=(%.1f,%.1f,%.1f) d=%.2f",
           pos[0][0], pos[0][1], pos[0][2], pos[1][0], pos[1][1], pos[1][2], d / len);
  // Com os dois quase alinhados com a camara fica o lado anterior (histerese).
  if (std::fabs(d / len) < 0.5f) return g_last_side;
  g_last_side = d > 0.0f ? 1 : 2;
  return g_last_side;
}

// ---------------------------------------------------------------------------
// Pausa do jogo enquanto um menu esta aberto (menus do launcher por cima do jogo
// ou o menu do port, Back+Start). A logica para no ponto de pausa do tick
// (android_hooks.cpp, chamado pelo BurstLimitCameraFrame) e o audio com
// AudioSystem::Pause(). O Kotlin chama isto a cada tick do HelperEngine (60 Hz).
// ---------------------------------------------------------------------------
namespace {
// Aplica g_menu_pause || g_background (com g_pause_mu): a logica para no ponto de pausa do tick e o audio com
// AudioSystem::Pause(). Sem runtime ainda (arranque) so a logica: o audio nao existe.
void ApplyPauseLocked() {
  const bool want = g_menu_pause || g_background;
  if (want == g_paused) return;
  auto* rt = rex::Runtime::instance();
  auto* audio = rt ? static_cast<rex::audio::AudioSystem*>(rt->audio_system()) : nullptr;
  if (want) {
    g_dragonburst_pause.store(true, std::memory_order_release);
    if (audio) audio->Pause();
  } else {
    if (audio) audio->Resume();
    {
      std::lock_guard<std::mutex> l(g_dragonburst_pause_mu);
      g_dragonburst_pause.store(false, std::memory_order_release);
    }
    g_dragonburst_pause_cv.notify_all();
  }
  g_paused = want;
}
}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dfdx047_dragonburst_game_NativeGameState_nativeSyncPause(JNIEnv*, jclass, jboolean overlay_open,
                                                                  jboolean pause_quick_menu) {
  std::lock_guard<std::mutex> lock(g_pause_mu);
  auto* rt = rex::Runtime::instance();
  if (!rt || !rt->memory()) return JNI_FALSE;
  g_menu_pause = overlay_open || (pause_quick_menu && rex::ui::QuickMenuDialog::IsOpen());
  ApplyPauseLocked();
  return g_paused ? JNI_TRUE : JNI_FALSE;
}

// ---------------------------------------------------------------------------
// Jogo fora da tela (HOME, recentes, tela desligada): a pausa dos menus, ja (o HelperEngine para no onPause), e alem
// dela a thread de render do jogo para no Swap e o VBlank do Renut e o vsync do Xenia param (android_hooks.cpp): o
// render thread do Renut e o command processor do Xenia ficam sem trabalho, nada e apresentado. O SDL para o audio e
// a thread da UI (SDL_HINT_ANDROID_BLOCK_ON_PAUSE). Na volta o jogo continua do mesmo tick; o swapchain novo para a
// janela nova e do Renut (renut/0008) e do SDK.
// ---------------------------------------------------------------------------
namespace {
std::chrono::steady_clock::time_point g_background_since;
uint64_t g_background_ticks = 0;
uint64_t g_background_shown = 0;  // quadros que chegam a tela (Renut e Xenia, rex::perf::GetPresentStats().shown)
}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_dfdx047_dragonburst_game_NativeGameState_nativeSetBackground(JNIEnv*, jclass, jboolean background) {
  std::lock_guard<std::mutex> lock(g_pause_mu);
  if (bool(background) == g_background) return;
  g_background = background;
  ApplyPauseLocked();
  {
    std::lock_guard<std::mutex> l(g_dragonburst_pause_mu);
    g_dragonburst_background.store(g_background, std::memory_order_release);
  }
  g_dragonburst_pause_cv.notify_all();
  if (dragonburst_renut_background) dragonburst_renut_background(g_background);
  rex::ui::SetUIWatchdogBackground(g_background);  // o SDL segura a thread da UI na pausa: nao e um travamento
  const uint64_t ticks = g_dragonburst_ticks.load(std::memory_order_relaxed);
  if (g_background) {
    g_background_since = std::chrono::steady_clock::now();
    g_background_ticks = ticks;
    g_background_shown = rex::perf::GetPresentStats().shown.load(std::memory_order_relaxed);
    REXLOG_INFO("dbz3: segundo plano: jogo em pausa (tick {}, ja parado: {})", ticks,
                g_dragonburst_parked.load(std::memory_order_acquire));
  } else {
    const double s =
        std::chrono::duration<double>(std::chrono::steady_clock::now() - g_background_since).count();
    // 1 tick no maximo: o que estava a meio quando a pausa chegou; idem os quadros (o que ja ia na fila).
    const uint64_t shown = rex::perf::GetPresentStats().shown.load(std::memory_order_relaxed);
    REXLOG_INFO("dbz3: primeiro plano depois de {:.1f} s: {} ticks do jogo e {} quadros mostrados na pausa{}", s,
                ticks - g_background_ticks, shown - g_background_shown,
                g_paused ? " (continua em pausa pelo menu)" : "");
  }
}

/** Ao sair do jogo: nunca deixar a thread do jogo presa na pausa. */
// Perda da GPU (SDK 0188): o SDK deixa de submeter e marca aqui; o HelperEngine ve a marca
// e o GameOverlay volta ao launcher com o aviso (em vez de SIGABRT no jogo).
namespace {
std::atomic<bool> g_gpu_lost{false};

void OnHostGpuLost(const char*) {
  g_gpu_lost.store(true, std::memory_order_release);
}

const bool g_gpu_loss_handler_set = [] {
  rex::graphics::SetHostGpuLossHandler(OnHostGpuLost);
  return true;
}();
}  // namespace

// O mesmo para o Renut (renut/renut_glue.cpp: VK_ERROR_DEVICE_LOST no plume, renut/0026).
extern "C" void dragonburst_gpu_lost(const char* reason) {
  OnHostGpuLost(reason);
}
extern "C" bool dragonburst_gpu_is_lost() {
  return g_gpu_lost.load(std::memory_order_acquire);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dfdx047_dragonburst_game_NativeGameState_nativeGpuLost(JNIEnv*, jclass) {
  return g_gpu_lost.load(std::memory_order_acquire) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_dfdx047_dragonburst_game_NativeGameState_nativeForceResume(JNIEnv*, jclass) {
  {
    std::lock_guard<std::mutex> lock(g_pause_mu);
    g_menu_pause = g_background = g_paused = false;
  }
  {
    std::lock_guard<std::mutex> l(g_dragonburst_pause_mu);
    g_dragonburst_pause.store(false, std::memory_order_release);
    g_dragonburst_background.store(false, std::memory_order_release);
  }
  g_dragonburst_pause_cv.notify_all();
}

// ---------------------------------------------------------------------------
// Luta ou menus: os controles por toque trocam de layout. -1 = ainda nao ha jogo.
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jint JNICALL
Java_com_dfdx047_dragonburst_game_NativeGameState_nativeFightFlag(JNIEnv*, jclass) {
  rex::memory::Memory* memory = GuestMemory();
  if (!memory) return -1;
  return BattleAddress(memory) != 0 ? 1 : 0;
}

// Camara livre / modo foto do Burst Limit (free_camera): o layout "mapa" (comando
// completo com os dois analogicos). 1 = ligada, 0 = nao, -1 = sem jogo.
extern "C" JNIEXPORT jint JNICALL
Java_com_dfdx047_dragonburst_game_NativeGameState_nativeMapFlag(JNIEnv*, jclass) {
  if (!GuestMemory()) return -1;
  return rex::cvar::GetFlagByName("free_camera") == "true" ? 1 : 0;
}

// Diagnostico da troca luta/menus (logs/controles.log com "Diagnostico de desempenho").
extern "C" JNIEXPORT jstring JNICALL
Java_com_dfdx047_dragonburst_game_NativeGameState_nativeBattleDebug(JNIEnv* env, jclass) {
  rex::memory::Memory* memory = GuestMemory();
  if (!memory) return env->NewStringUTF("sem jogo");
  char text[256];
  snprintf(text, sizeof(text), "battle=%08X free_camera=%s lado=%d %s", LoadU32(memory, kBattlePointer),
           rex::cvar::GetFlagByName("free_camera").c_str(), g_last_side, g_side_debug);
  return env->NewStringUTF(text);
}

// Diagnostico do lado do P1 (DBZ3). Sem uso no Burst Limit por enquanto.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_dfdx047_dragonburst_game_NativeGameState_nativeDumpFight(JNIEnv*, jclass, jstring, jint,
                                                                  jlong) {
  return JNI_FALSE;
}

// ---------------------------------------------------------------------------
// Menu de opcoes do port por toque (InGameMenu.kt): muda cvars ao vivo, como o menu
// do Burst Limit (Back + Start), e grava o burstlimit.toml.
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jboolean JNICALL
Java_com_dfdx047_dragonburst_game_NativeGameState_nativeSetCvars(JNIEnv* env, jclass, jobjectArray names,
                                                                 jobjectArray values) {
  if (!rex::Runtime::instance()) return JNI_FALSE;
  const jsize n = env->GetArrayLength(names);
  bool ok = true;
  for (jsize i = 0; i < n; ++i) {
    auto jn = static_cast<jstring>(env->GetObjectArrayElement(names, i));
    auto jv = static_cast<jstring>(env->GetObjectArrayElement(values, i));
    const char* cn = env->GetStringUTFChars(jn, nullptr);
    const char* cv = env->GetStringUTFChars(jv, nullptr);
    ok = rex::cvar::SetFlagByName(cn, cv) && ok;
    env->ReleaseStringUTFChars(jn, cn);
    env->ReleaseStringUTFChars(jv, cv);
    env->DeleteLocalRef(jn);
    env->DeleteLocalRef(jv);
  }
  rex::cvar::SaveConfig(rex::filesystem::GetExecutableFolder() / "burstlimit.toml");
  return ok ? JNI_TRUE : JNI_FALSE;
}

// ---------------------------------------------------------------------------
// Menu do jogo do app (InGameMenu.kt): le cvars, chama comandos (save_export,
// save_import <zip>) e toca os sons de menu do proprio jogo.
// ---------------------------------------------------------------------------
void BurstLimitPlayMenuSound(int sound);  // burstlimit_menusound.cpp

extern "C" JNIEXPORT jstring JNICALL
Java_com_dfdx047_dragonburst_game_NativeGameState_nativeGetCvar(JNIEnv* env, jclass, jstring name) {
  if (!rex::Runtime::instance()) return env->NewStringUTF("");
  const char* cn = env->GetStringUTFChars(name, nullptr);
  const std::string value = rex::cvar::GetFlagByName(cn);
  env->ReleaseStringUTFChars(name, cn);
  return env->NewStringUTF(value.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dfdx047_dragonburst_game_NativeGameState_nativeInvokeCommand(JNIEnv* env, jclass, jstring name,
                                                                      jstring args) {
  if (!rex::Runtime::instance()) return JNI_FALSE;
  const char* cn = env->GetStringUTFChars(name, nullptr);
  const char* ca = env->GetStringUTFChars(args, nullptr);
  const bool ok = rex::cvar::InvokeCommand(cn, ca);
  env->ReleaseStringUTFChars(name, cn);
  env->ReleaseStringUTFChars(args, ca);
  return ok ? JNI_TRUE : JNI_FALSE;
}

// 0 mover, 1 confirmar, 2 voltar, 3 nao da, 4 comecar, 5 abrir menu, 6 fechar menu.
extern "C" JNIEXPORT void JNICALL
Java_com_dfdx047_dragonburst_game_NativeGameState_nativeMenuSound(JNIEnv*, jclass, jint sound) {
  if (!rex::Runtime::instance()) return;
  if (rex::cvar::GetFlagByName("menu_sounds") == "false") return;
  BurstLimitPlayMenuSound(sound);
}

// Linha no log do jogo (autoteste do render: marca cada estado para as linhas "perf").
extern "C" JNIEXPORT void JNICALL
Java_com_dfdx047_dragonburst_game_NativeGameState_nativeLogLine(JNIEnv* env, jclass, jstring line) {
  const char* c = env->GetStringUTFChars(line, nullptr);
  REXLOG_INFO("dbz3: {}", c);
  env->ReleaseStringUTFChars(line, c);
}

// ---------------------------------------------------------------------------
// Patch 0092 (120 FPS): taxa real da tela para o presenter (so gera quadros interpolados se a
// tela chegar a ~1,8x os FPS do jogo) e a linha "como os quadros chegam a tela" do autoteste.
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_dfdx047_dragonburst_game_NativeGameState_nativeSetDisplayHz(JNIEnv*, jclass, jfloat hz) {
  static std::atomic<int> logged_hz{-1};
  rex::perf::SetDisplayRefreshRate(hz);
  // Antes do runtime arrancar o log ainda nao existe (a 1.a chamada vem do onCreate): a linha sai
  // na primeira chamada com o runtime de pe (3 s depois da superficie) e a cada mudanca.
  const int rounded = int(hz + 0.5f);
  if (rex::Runtime::instance() && logged_hz.exchange(rounded) != rounded) {
    REXLOG_INFO("dbz3: tela a {:.2f} Hz", hz);
  }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_dfdx047_dragonburst_game_NativeGameState_nativePresentInfo(JNIEnv* env, jclass) {
  return env->NewStringUTF(rex::perf::GetPresentInfo().c_str());
}
