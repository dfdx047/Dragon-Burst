// Renut (renderizador nativo de Direct3D do mgr-nx-deko) no Dragon Burst, a partir da cola do Zandatsu
// (src/renut_glue.cpp de la): a escolha no arranque e os hooks que o Renut partilha com a cola (Swap e os dois draws),
// nos enderecos do Burst Limit (docs/renut/HOOKMAP_BL.md). Compilado so com DB_RENUT (cmake/renut.cmake).
// O nome das cvars e o do Zandatsu (swordman_*), o mesmo Renut.
//
// swordman_renderer = xenos (padrao): o Xenia emulado de sempre; todos os hooks do Renut chamam a funcao original.
// swordman_renderer = renut: o Direct3D do jogo corre no Renut (plume/Vulkan), sem anel PM4 nem EDRAM.

#include <atomic>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <string>

#include <dlfcn.h>
#include <sched.h>

#include <adrenotools/driver.h>

#include <rex/cvar.h>
#include <rex/hook.h>
#include <rex/ppc.h>

#include <rex/logging.h>

#include "renut_engine/gpu/sa2_renut_yield.h"

REXCVAR_DECLARE(std::string, present_effect);  // SDK (ui/presenter.cpp)
REXCVAR_DECLARE(std::string, swordman_renut_bc_decode);  // Renut (renut/0020): texturas BC sem suporte do driver

REXCVAR_DEFINE_STRING(swordman_renderer, "xenos", "SwordMan",
                      "Renderizador, escolhido no arranque. xenos = GPU do Xbox emulada (Xenia, plugin xenos). "
                      "renut = Direct3D do jogo nativo no Renut do mgr-nx-deko (plume, Vulkan)")
    .allowed({"xenos", "renut"})
    .lifecycle(rex::cvar::Lifecycle::kInitOnly);

REXCVAR_DEFINE_BOOL(mgr_renut_diag_original_draw, false, "MGR/Diagnostics",
                    "Renut: corre tambem o Draw original do jogo antes do do Renut (diagnostico)");

// Frames apresentados pelo jogo no modo Renut (o VdSwap do SDK nao e chamado: o Renut substitui o D3DDevice_Swap).
std::atomic<uint64_t> g_sa2_game_frames{0};

// SDK (patch 0100): pausa dos menus do app e contador do vigia (android_hooks.cpp). No modo Xenia o VdSwap chama-o;
// no Renut o VdSwap nunca corre, por isso e chamado aqui, no inicio do Swap, na mesma thread do jogo.
extern "C" void (*rex_vdswap_hook)(void);
void SwordManRenutShotOnSwap();          // abaixo
void SwordManRenutOverlayStatsOnSwap();  // abaixo
void SwordManRenutPlaceMainThreadOnSwap();  // abaixo
namespace {
void StartVBlank();  // abaixo
}  // namespace

REX_EXTERN(__imp__sub_820B64C0);
REX_HOOK_RAW(sub_820B64C0) {  // D3DDevice_Swap (BL; MGR sub_826B3510)
  if (banjo::renut_gpu::Active()) {
    StartVBlank();
    if (rex_vdswap_hook) rex_vdswap_hook();
    SwordManRenutShotOnSwap();
    SwordManRenutOverlayStatsOnSwap();
    SwordManRenutPlaceMainThreadOnSwap();
    renut_native_sub_820B64C0(ctx, base);
    g_sa2_game_frames.fetch_add(1, std::memory_order_relaxed);
    return;
  }
  __imp__sub_820B64C0(ctx, base);
}

REX_EXTERN(__imp__sub_820A8E10);
REX_HOOK_RAW(sub_820A8E10) {  // D3DDevice_DrawIndexedVertices (BL; MGR sub_826BCD40)
  if (banjo::renut_gpu::Active() && REXCVAR_GET(mgr_renut_diag_original_draw)) {
    const PPCContext arguments = ctx;
    __imp__sub_820A8E10(ctx, base);
    ctx = arguments;
  }
  SA2_RENUT_YIELD_SHARED(sub_820A8E10);
  __imp__sub_820A8E10(ctx, base);
}

REX_EXTERN(__imp__sub_820A8A20);
REX_HOOK_RAW(sub_820A8A20) {  // D3DDevice_DrawVertices (BL; MGR sub_826BC928)
  if (banjo::renut_gpu::Active() && REXCVAR_GET(mgr_renut_diag_original_draw)) {
    const PPCContext arguments = ctx;
    __imp__sub_820A8A20(ctx, base);
    ctx = arguments;
  }
  SA2_RENUT_YIELD_SHARED(sub_820A8A20);
  __imp__sub_820A8A20(ctx, base);
}

// ---------------------------------------------------------------------------------------------------------------
// O handshake da frame do BL (como o renderizador nativo do PC, burstlimit_native.cpp): a thread do jogo (820E7920) e
// a de render (820EA188) passam as frames por um mutex/evento auto-reset do objeto 0x8438AC00 (+160 "frame na fila"),
// as duas pelo MESMO evento com condicoes opostas, em 822A2E50 -> NtSignalAndWaitForSingleObjectEx(infinito). No host
// a libertacao e a espera nao sao atomicas e um wake-up perde-se: com o Renut as duas ficavam paradas logo no
// primeiro Swap (vigia: 820E7920 e 820EA188 em SignalAndWait). Com o Renut estas tres esperas tem timeout (os
// chamadores ja voltam a ver a condicao sob o mutex e tratam STATUS_TIMEOUT como acordar), e antes de cada uma correm
// os callbacks do InsertCallback que a thread de render do Renut ja alcancou (no Renut so correm na proxima chamada
// D3D de uma thread do jogo; aqui as duas estao a bloquear).
// ---------------------------------------------------------------------------------------------------------------
#include "renut_engine/gpu/guest/callbacks.h"

REXCVAR_DEFINE_INT32(swordman_renut_handshake_ms, 8, "SwordMan",
                     "Renut no Burst Limit: timeout das esperas do handshake da frame (0 = infinito, como o jogo)")
    .lifecycle(rex::cvar::Lifecycle::kHotReload);

// ---------------------------------------------------------------------------------------------------------------
// VBlank do D3D no Renut. O BL anda ao ritmo do vblank: o handler de VBlank do D3D (820B5CF8(device), chamado no
// console pelo interrupt da GPU) avanca o contador de vblanks do device (+16548), despacha os flips em fila e chama o
// callback do jogo (device+16544 = 820EAA20, posto em 820F9578), que sinaliza o evento do tique da frame: a thread
// 820E7870 acorda e avanca o contador pelo qual a thread do jogo espera depois do null flip (820E7AE4). Com o Renut
// nao ha GraphicsSystem (ConfigureRuntime: graphics = nullptr), logo nenhum interrupt: o jogo parava ai, no primeiro
// frame. Aqui uma XHostThread chama o handler a 60 Hz, ou ao ritmo do frame_rate (como o vsync do SDK chama o
// interrupt). A fila de flips fica vazia (o Renut tem o null flip 820B5F90 e o Swap em hook), por isso o handler nunca
// escreve no registo da GPU.
// ---------------------------------------------------------------------------------------------------------------
#include <mutex>

#include <rex/system/kernel_state.h>
#include <rex/system/xthread.h>

#include "renut_engine/gpu/d3d/d3d_native.h"
#include <rex/graphics/frame_pacing.h>

REXCVAR_DEFINE_BOOL(swordman_renut_vblank, true, "SwordMan",
                    "Renut no Burst Limit: o VBlank do D3D (820B5CF8) a 60 Hz, que o jogo espera a cada frame")
    .lifecycle(rex::cvar::Lifecycle::kInitOnly);

namespace {
std::atomic<uint64_t> g_handshake_waits{0}, g_handshake_timeouts{0};
std::atomic<uint64_t> g_vblanks{0};
rex::system::object_ref<rex::system::XHostThread> g_vblank_thread;

constexpr uint32_t kBlVBlankHandler = 0x820B5CF8;

// O ritmo do vblank que o jogo pediu (frame_rate do burstlimit_patches: 30 = vblank a 30 Hz, que segura o jogo a 30
// como no Xenia; 0 = os 60 Hz do modo de video). Ate 240 Hz; acima (unlocked) fica nos 60.
std::chrono::nanoseconds VBlankInterval() {
  const double hz = rex::graphics::GetGuestVblankRate();
  return std::chrono::nanoseconds(int64_t(1e9 / (hz >= 1.0 && hz <= 240.0 ? hz : 60.0)));
}

int VBlankThreadMain() {
  using clock = std::chrono::steady_clock;
  auto interval = VBlankInterval();
  auto next = clock::now() + interval;
  auto* kernel = rex::system::kernel_state();
  auto* dispatcher = kernel->function_dispatcher();
  auto* thread = rex::system::XThread::GetCurrentThread();
  for (;;) {
    std::this_thread::sleep_until(next);
    interval = VBlankInterval();
    next += interval;
    // Atrasado mais de 4 vblanks (thread suspensa): retoma a partir de agora, sem rajada.
    if (clock::now() - next > 4 * interval) next = clock::now() + interval;
    const uint32_t device = renut::gpu::native::GlobalDevice();
    if (!device) continue;
    uint64_t args[] = {device};
    dispatcher->ExecuteInterrupt(thread->thread_state(), kBlVBlankHandler, args, 1);
    const uint64_t n = g_vblanks.fetch_add(1, std::memory_order_relaxed) + 1;
    if (n == 1 || n == 600) REXLOG_INFO("Renut: VBlank do D3D a 60 Hz ({} vblanks, device {:08X})", n, device);
  }
}

void StartVBlank() {
  static std::once_flag once;
  std::call_once(once, [] {
    if (!REXCVAR_GET(swordman_renut_vblank)) return;
    g_vblank_thread = rex::system::object_ref<rex::system::XHostThread>(
        new rex::system::XHostThread(rex::system::kernel_state(), 128 * 1024, 0, [] { return VBlankThreadMain(); }));
    g_vblank_thread->set_name("Renut VBlank");
    g_vblank_thread->Create();
  });
}
}  // namespace

REX_EXTERN(__imp__sub_822A2E50);
REX_HOOK_RAW(sub_822A2E50) {  // SignalObjectAndWait do jogo (r5 = timeout em ms, -1 = infinito)
  if (banjo::renut_gpu::Active()) StartVBlank();
  const uint32_t lr = uint32_t(ctx.lr);
  const int32_t timeout_ms = REXCVAR_GET(swordman_renut_handshake_ms);
  if (!banjo::renut_gpu::Active() || timeout_ms <= 0 || ctx.r5.u32 != 0xFFFFFFFFu ||
      (lr != 0x820E79D4u && lr != 0x820E7A40u && lr != 0x820EA200u)) {
    __imp__sub_822A2E50(ctx, base);
    return;
  }
  renut::gpu::RunReadyCallbacks();
  ctx.r5.u64 = uint32_t(timeout_ms);
  __imp__sub_822A2E50(ctx, base);
  const uint64_t waits = g_handshake_waits.fetch_add(1, std::memory_order_relaxed) + 1;
  if (ctx.r3.u32 == 0x102u /* STATUS_TIMEOUT */) {
    const uint64_t timeouts = g_handshake_timeouts.fetch_add(1, std::memory_order_relaxed) + 1;
    if ((timeouts & (timeouts - 1)) == 0)  // 1, 2, 4, 8...
      REXLOG_INFO("Renut: handshake da frame, {} timeouts em {} esperas", timeouts, waits);
  }
}

// ---------------------------------------------------------------------------------------------------------------
// O que o Renut le da cola do SA2 deles (app/src/cpu/sa2_enhancements.cpp, me/sa2_render_mode.cpp, sa2_capture.cpp).
// Esses ficheiros trazem a maquinaria do SA2 (autoverificacao do codegen, contadores do SDK deles, captura de video);
// aqui so o que o renderizador chama, com os padroes deles.
// ---------------------------------------------------------------------------------------------------------------

#include "cpu/sa2_enhancements.h"
#include "me/sa2_render_mode.h"
#include "sa2_capture.h"

REXCVAR_DEFINE_INT32(sa2_render_height_handheld, 720, "SwordMan",
                     "Renut: altura de render (720 = os 1280x720 do jogo). Multiplos de 180 de 360 a 2160; a largura "
                     "segue a 16:9")
    .range(180, 4320)
    .lifecycle(rex::cvar::Lifecycle::kHotReload);

namespace sa2::enh {
// sa2_cpu_enhancements / sa2_noncpu_enhancements, ligados por padrao no deles: cada melhoria do Renut segue a sua
// propria cvar.
bool CpuMaster() { return true; }
bool NonCpuMaster() { return true; }
}  // namespace sa2::enh

namespace sa2::render {
// Sem modo "docked" no Android: sempre a altura de sa2_render_height_handheld.
bool Docked() { return false; }
int32_t RenderHeightOf(bool) { return REXCVAR_GET(sa2_render_height_handheld); }
void OnPresented(uint64_t) {}
}  // namespace sa2::render

namespace sa2::capture {
// Sem a captura de video em lockstep do PC deles (a do SwordMan e a do AutoPilot).
bool RenutLockstepOn() { return false; }
void RenutLockstepFrame(uint64_t, const rex::ui::RawImage*) {}
}  // namespace sa2::capture

// ---------------------------------------------------------------------------------------------------------------
// Driver Vulkan do plume (patch renut/0006): o mesmo que o SDK carrega para o Xenia (vulkan_instance.cpp,
// Dbz3OpenCustomVulkanDriver): o GameActivity define REX_VULKAN_DRIVER_DIR/NAME (aba Drivers, Turnip) e
// REX_NATIVE_LIB_DIR; sem elas, ou se falhar, o plume usa o libvulkan do sistema.
// ---------------------------------------------------------------------------------------------------------------
static void* OpenVulkanDriver();

// Uma so abertura por processo (a verificacao de funcoes e o plume usam o mesmo driver).
extern "C" void* plume_android_vulkan_library() {
  static void* const handle = OpenVulkanDriver();
  return handle;
}

static void* OpenVulkanDriver() {
  const char* driver_dir = std::getenv("REX_VULKAN_DRIVER_DIR");
  const char* driver_name = std::getenv("REX_VULKAN_DRIVER_NAME");
  const char* hook_dir = std::getenv("REX_NATIVE_LIB_DIR");
  if (!driver_dir || !*driver_dir || !driver_name || !*driver_name || !hook_dir || !*hook_dir) {
    REXLOG_INFO("Renut: driver Vulkan do sistema");
    return nullptr;
  }
  std::string dir(driver_dir), hooks(hook_dir);
  if (dir.back() != '/') dir += '/';
  if (hooks.back() != '/') hooks += '/';
  void* handle = adrenotools_open_libvulkan(RTLD_NOW, ADRENOTOOLS_DRIVER_CUSTOM, nullptr, hooks.c_str(), dir.c_str(),
                                            driver_name, nullptr, nullptr);
  if (handle) {
    REXLOG_INFO("Renut: driver Vulkan alternativo {}{}", dir, driver_name);
  } else {
    const char* error = dlerror();
    REXLOG_ERROR("Renut: o driver alternativo {}{} falhou a carregar ({}); a usar o do sistema", dir, driver_name,
                 error ? error : "sem detalhe");
  }
  return handle;
}

// ---------------------------------------------------------------------------------------------------------------
// Diagnostico: swordman_renut_shot_s = N grava, N segundos apos o primeiro Swap, a imagem que o Renut apresenta
// (renut::gpu::CaptureFrame: a mesma passagem de scan-out, para um alvo fora da swapchain) em logs/renut_shot.ppm.
// ---------------------------------------------------------------------------------------------------------------
#include <chrono>
#include <fstream>
#include <thread>

#include <rex/main_android.h>

#include "renut_engine/gpu/banjo_renut_capture.h"

REXCVAR_DEFINE_INT32(swordman_renut_shot_s, 0, "SwordMan",
                     "Renut (diagnostico): segundos apos o primeiro frame para gravar logs/renut_shot.ppm; 0 = nunca");

void SwordManRenutShotOnSwap() {
  static std::atomic<bool> started{false};
  const int32_t seconds = REXCVAR_GET(swordman_renut_shot_s);
  if (seconds <= 0 || started.exchange(true)) return;
  std::thread([seconds] {
    std::this_thread::sleep_for(std::chrono::seconds(seconds));
    rex::ui::RawImage image;
    if (!renut::gpu::CaptureFrame(image, 2000)) {
      REXLOG_WARN("Renut: captura falhou (nenhum frame apresentado em 2 s)");
      return;
    }
    const auto path = rex::GetAndroidAppDirectory() / "logs" / "renut_shot.ppm";
    std::ofstream out(path, std::ios::binary);
    out << "P6\n" << image.width << " " << image.height << "\n255\n";
    uint64_t sum = 0;
    for (uint32_t y = 0; y < image.height; ++y) {
      const uint8_t* row = image.data.data() + y * image.stride;
      for (uint32_t x = 0; x < image.width; ++x) {
        out.write(reinterpret_cast<const char*>(row + x * 4), 3);
        sum += row[x * 4] + row[x * 4 + 1] + row[x * 4 + 2];
      }
    }
    REXLOG_INFO("Renut: captura {}x{} em {} (media {:.1f})", image.width, image.height, path.string(),
                double(sum) / (3.0 * image.width * image.height));
  }).detach();
}

// ---------------------------------------------------------------------------------------------------------------
// O que os shaders traduzidos e o plume assumem sem verificar (docs/renut-port-analysis.md 5.2). Corre no
// OnPreSetup, antes de qualquer superficie: sem estas funcoes o MgrApp fica no Xenia, com a lista do que falta no log.
// ---------------------------------------------------------------------------------------------------------------
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>

namespace plume {
void SetAndroidClearFold(bool enabled);  // renut/0106 (o 0027 do Zandatsu)
}
namespace renut::gpu {
void SetPointSpriteUbo(bool on);  // renut/0108
}

// O Renut sem shaderInt64 (o driver da Qualcomm no Adreno 650 nao o tem): nos shaders so o point_sprite_gs (endereco de
// 64 bits do SharedConstants) e o bc_decode_cs (so sem BC nativo, ou seja na Mali) o usam. Sem ele, os point sprites
// leem o SharedConstants do UBO do set 7 (renut/0108). Ligado, o Renut deixa de exigir shaderInt64 quando ha BC; por
// omissao continua a exigi-lo, porque o driver da Qualcomm ainda cai no layout de 8 sets (so liga 4) e o jogo deve ir
// para o Xenia.
REXCVAR_DEFINE_BOOL(swordman_renut_point_ubo, false, "SwordMan",
                    "Renut: point sprites a ler o SharedConstants do UBO e sem exigir shaderInt64 (teste do driver Qualcomm)")
    .lifecycle(rex::cvar::Lifecycle::kInitOnly);
REXCVAR_DEFINE_BOOL(swordman_renut_clear_fold, true, "SwordMan",
                    "Renut: um clear do alvo inteiro antes do primeiro draw do passe abre o passe com loadOp CLEAR (a GPU "
                    "por blocos nao le o alvo da memoria); false = clear dentro do passe, como antes")
    .lifecycle(rex::cvar::Lifecycle::kInitOnly);

bool SwordManRenutDeviceSupported(std::string* why) {
  plume::SetAndroidClearFold(REXCVAR_GET(swordman_renut_clear_fold));
  void* library = plume_android_vulkan_library();
  if (!library) library = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
  auto get_instance_proc = library ? reinterpret_cast<PFN_vkGetInstanceProcAddr>(
                                         dlsym(library, "vkGetInstanceProcAddr"))
                                   : nullptr;
  if (!get_instance_proc) {
    REXLOG_ERROR("Renut: sem driver Vulkan");
    if (why) *why = "vulkan";
    return false;
  }
  auto create_instance =
      reinterpret_cast<PFN_vkCreateInstance>(get_instance_proc(nullptr, "vkCreateInstance"));
  VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
  app.apiVersion = VK_API_VERSION_1_2;
  VkInstanceCreateInfo info{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
  info.pApplicationInfo = &app;
  VkInstance instance = VK_NULL_HANDLE;
  if (!create_instance || create_instance(&info, nullptr, &instance) != VK_SUCCESS) {
    REXLOG_ERROR("Renut: vkCreateInstance (Vulkan 1.2) falhou");
    if (why) *why = "vulkan-1.2";
    return false;
  }
#define SM_VK(name) auto name = reinterpret_cast<PFN_##name>(get_instance_proc(instance, #name))
  SM_VK(vkDestroyInstance);
  SM_VK(vkEnumeratePhysicalDevices);
  SM_VK(vkGetPhysicalDeviceFeatures2);
  SM_VK(vkGetPhysicalDeviceProperties2);
  SM_VK(vkGetPhysicalDeviceFormatProperties);
#undef SM_VK
  uint32_t count = 1;
  VkPhysicalDevice device = VK_NULL_HANDLE;
  vkEnumeratePhysicalDevices(instance, &count, &device);
  std::string missing;
  if (!device) {
    missing = " dispositivo";
  } else {
    VkPhysicalDeviceVulkan12Features f12{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES};
    VkPhysicalDeviceFeatures2 f{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2, &f12};
    vkGetPhysicalDeviceFeatures2(device, &f);
    VkPhysicalDeviceVulkan12Properties p12{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_PROPERTIES};
    VkPhysicalDeviceProperties2 p{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2, &p12};
    vkGetPhysicalDeviceProperties2(device, &p);
    VkFormatProperties d32s8{};
    vkGetPhysicalDeviceFormatProperties(device, VK_FORMAT_D32_SFLOAT_S8_UINT, &d32s8);
    const auto need = [&](bool ok, const char* what) {
      if (!ok) missing += std::string(" ") + what;
    };
    need(f12.bufferDeviceAddress, "bufferDeviceAddress");
    // shaderInt64: com swordman_renut_point_ubo so para a descompressao BC por compute (sem BC nativo).
    need(f.features.shaderInt64 || (REXCVAR_GET(swordman_renut_point_ubo) && f.features.textureCompressionBC),
         "shaderInt64");
    renut::gpu::SetPointSpriteUbo(REXCVAR_GET(swordman_renut_point_ubo) || !f.features.shaderInt64);
    need(f12.descriptorIndexing && f12.runtimeDescriptorArray && f12.descriptorBindingPartiallyBound &&
             f12.descriptorBindingVariableDescriptorCount && f12.descriptorBindingSampledImageUpdateAfterBind,
         "descriptorIndexing");
    need(p12.maxDescriptorSetUpdateAfterBindSampledImages >= 8192, "8192-texturas");
    // Sem texturas BC (Mali) o Renut descomprime-as no upload (renut/0020, swordman_renut_bc_decode): ja nao falta.
    // RADV nas Xclipse (Samsung/AMD): BC4/BC5/BC7 emulados pelo driver (convertidos para EAC/BC3, e uma copia de volta
    // devolve o convertido): auto -> descompressao por compute, exata.
    const bool radv_xclipse = p12.driverID == VK_DRIVER_ID_MESA_RADV &&
                              std::strstr(p.properties.deviceName, "Xclipse") != nullptr;
    if (radv_xclipse && REXCVAR_GET(swordman_renut_bc_decode) == "auto") {
      REXCVAR_SET(swordman_renut_bc_decode, std::string("compute"));
      REXLOG_INFO("Renut: RADV na Xclipse, texturas BC descomprimidas por compute (swordman_renut_bc_decode)");
    }
    if (!f.features.textureCompressionBC) {
      REXLOG_INFO("Renut: o driver nao tem texturas BC; descomprimidas no upload (swordman_renut_bc_decode = {})",
                  REXCVAR_GET(swordman_renut_bc_decode));
    }
    // Mali (IDVS): sem vertexPipelineStoresAndAtomics, porque o vertex shader pode correr mais de uma vez por vertice. O
    // memexport dos vertex shaders do jogo so faz escritas simples (o mesmo valor no mesmo sitio), sem atomicos: repetir
    // nao muda o resultado. Aviso em vez de recusa (o Renut fora do Adreno e experimental, escolha do jogador).
    if (!f.features.vertexPipelineStoresAndAtomics) {
      REXLOG_WARN("Renut: o driver nao tem vertexPipelineStoresAndAtomics (memexport nos vertex shaders sem garantia)");
    }
    need(d32s8.optimalTilingFeatures & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT, "D32S8");
    // Driver Qualcomm no Adreno 710/720 (Galaxy A36, driver 0800.58, issue #9): SIGBUS com a pilha corrompida
    // (pc = lr = 0x200000001) logo depois da swapchain, em todos os arranques do Renut; o Turnip (ARMSX2) roda. O 750
    // (issue #11) e o 830 (Odin 3) rodam com o driver Qualcomm. O launcher le o motivo e sugere o Turnip.
    if (p12.driverID == VK_DRIVER_ID_QUALCOMM_PROPRIETARY) {
      const char* adreno = std::strstr(p.properties.deviceName, "Adreno");
      const int model = adreno ? std::atoi(adreno + std::strcspn(adreno, "0123456789")) : 0;
      if (model >= 700 && model < 730) {
        REXLOG_ERROR("Renut: driver Qualcomm {} no {} fecha ao abrir o Renut (issue #9); a usar o Xenia",
                     p12.driverInfo, p.properties.deviceName);
        need(false, "crash-qualcomm-a7xx");
      }
    }
    // O layout dos draws do Renut usa 8 descriptor sets (texturas 2D/3D/cubo/array, samplers, 3 de constantes). A Mali-G57
    // (r40) so liga 7 e o driver corrompe a pilha em vez de falhar no vkCreatePipelineLayout: abaixo de 8 o Renut compacta
    // o layout em 5 (renut/0020, SetDrawSetsCompact). No Adreno nao: o Turnip diz 4 mas sempre ligou os 8.
    if (p.properties.vendorID != 0x5143) {
      need(p.properties.limits.maxBoundDescriptorSets >= 5, "5-descriptor-sets");
    }
    REXLOG_INFO("Renut: limites maxBoundDescriptorSets {} maxPerStageDescriptorUpdateAfterBindSampledImages {} "
                "maxDescriptorSetUpdateAfterBindSampledImages {} maxPerStageUpdateAfterBindResources {} "
                "maxDescriptorSetUpdateAfterBindUniformBuffersDynamic {} maxPushConstantsSize {}",
                p.properties.limits.maxBoundDescriptorSets, p12.maxPerStageDescriptorUpdateAfterBindSampledImages,
                p12.maxDescriptorSetUpdateAfterBindSampledImages, p12.maxPerStageUpdateAfterBindResources,
                p12.maxDescriptorSetUpdateAfterBindUniformBuffersDynamic, p.properties.limits.maxPushConstantsSize);
    REXLOG_INFO("Renut: {} (Vulkan {}.{}), geometryShader {}", p.properties.deviceName,
                VK_API_VERSION_MAJOR(p.properties.apiVersion), VK_API_VERSION_MINOR(p.properties.apiVersion),
                f.features.geometryShader ? "sim" : "nao (retangulos e point sprites ficam errados)");
  }
  vkDestroyInstance(instance, nullptr);
  if (!missing.empty()) {
    REXLOG_ERROR("Renut: o driver nao tem{}; a usar o Xenia", missing);
    if (why) *why = missing.substr(1);
    return false;
  }
  return true;
}

// ---------------------------------------------------------------------------------------------------------------
// Overlay de depuracao do SDK no modo Renut: no Xenia o command processor conta os swaps e diz a resolucao; aqui e o
// Swap do Renut. O "Upscaler" vem do SDK 0175: o present do Renut escala o frame para o ecra (bilinear), sem o FSR
// do presenter do Xenia.
// ---------------------------------------------------------------------------------------------------------------
#include <rex/perf/frame_rate.h>

void SwordManRenutOverlayStatsOnSwap() {
  static const bool once = [] {
    const int32_t height = REXCVAR_GET(sa2_render_height_handheld);
    rex::perf::RecordGuestFrontbuffer(1280, 720);
    rex::perf::SetDrawResolutionScale(1, 1, 1, 1);
    rex::perf::SetDrawResolutionPercent(uint32_t(height > 0 ? height * 100 / 720 : 100));
    return true;
  }();
  (void)once;
  // O filtro do passe final (renut/0019: FSR 1, CAS), ao vivo: o menu muda present_effect com o jogo a correr.
  static std::string shown;
  const std::string &effect = REXCVAR_GET(present_effect);
  if (effect != shown) {
    shown = effect;
    rex::perf::SetUpscalerOverride(effect == "fsr"   ? "Renut \xC2\xB7 AMD FSR 1"
                                   : effect == "cas" ? "Renut \xC2\xB7 AMD CAS"
                                                     : "Renut \xC2\xB7 bilinear");
  }
  rex::perf::RecordGuestSwap();
}

// ---------------------------------------------------------------------------------------------------------------
// Núcleos da thread principal do jogo (a do Swap) e da thread de render do Renut (patch renut/0011).
// swordman_renut_cores: 0 = o escalonador decide (como antes); 1 = as duas nos núcleos grandes, livres entre eles;
// 2 = a principal no núcleo mais rápido e a de render nos outros grandes (nunca no mesmo). Núcleos lidos do
// cpufreq: "grandes" = máximo acima do mais lento (no SD865: 4-6 A77 a 2,42 GHz e 7 A77 a 2,84 GHz).
// ---------------------------------------------------------------------------------------------------------------
#include <fstream>
#include <vector>

REXCVAR_DEFINE_INT32(swordman_renut_cores, 0, "SwordMan",
                     "Renut: núcleos da thread principal e da de render. 0 = livres; 1 = as duas nos núcleos grandes; "
                     "2 = principal no mais rápido, render nos outros grandes")
    .range(0, 2)
    .lifecycle(rex::cvar::Lifecycle::kInitOnly);

namespace {

struct CoreSets {
  cpu_set_t big{};      // todos os grandes
  cpu_set_t fastest{};  // o de frequência mais alta
  cpu_set_t others{};   // grandes sem o mais rápido
  bool ok = false;
};

// Desligada (perfis de desempenho, núcleos desligados pelo sistema): fica de fora.
bool CoreOnline(int cpu) {
  if (cpu == 0) return true;
  std::ifstream f("/sys/devices/system/cpu/cpu" + std::to_string(cpu) + "/online");
  int on = 1;
  return !(f >> on) || on != 0;
}

const CoreSets& Cores() {
  static const CoreSets sets = [] {
    CoreSets out;
    std::vector<long> freq;
    for (int cpu = 0; cpu < CPU_SETSIZE; ++cpu) {
      std::ifstream f("/sys/devices/system/cpu/cpu" + std::to_string(cpu) + "/cpufreq/cpuinfo_max_freq");
      long khz = 0;
      if (!(f >> khz)) break;
      freq.push_back(khz);
    }
    if (freq.size() < 2) return out;
    long lo = freq[0], hi = freq[0];
    int fastest = 0;
    for (size_t i = 0; i < freq.size(); ++i) {
      lo = std::min(lo, freq[i]);
      if (freq[i] > hi) { hi = freq[i]; fastest = int(i); }
    }
    if (hi == lo) return out;
    // Só os ligados: o mais rápido e os grandes entre eles (um desligado como "grande" deixava a thread solta).
    hi = 0;
    fastest = -1;
    for (size_t i = 0; i < freq.size(); ++i) {
      if (freq[i] > lo && CoreOnline(int(i)) && freq[i] > hi) { hi = freq[i]; fastest = int(i); }
    }
    if (fastest < 0) return out;
    CPU_ZERO(&out.big); CPU_ZERO(&out.fastest); CPU_ZERO(&out.others);
    CPU_SET(fastest, &out.fastest);
    for (size_t i = 0; i < freq.size(); ++i) {
      if (freq[i] > lo && CoreOnline(int(i))) {
        CPU_SET(int(i), &out.big);
        if (int(i) != fastest) CPU_SET(int(i), &out.others);
      }
    }
    // Um só núcleo grande: as duas partilham-no (melhor que um pequeno).
    if (CPU_COUNT(&out.others) == 0) out.others = out.fastest;
    out.ok = true;
    return out;
  }();
  return sets;
}

void PlaceCurrentThread(const char* what, bool main_thread) {
  const int32_t mode = REXCVAR_GET(swordman_renut_cores);
  const CoreSets& c = Cores();
  if (mode == 0 || !c.ok) return;
  const cpu_set_t& set = mode == 1 ? c.big : (main_thread ? c.fastest : c.others);
  const bool ok = sched_setaffinity(0, sizeof(set), &set) == 0;
  std::string list;
  for (int cpu = 0; cpu < CPU_SETSIZE; ++cpu)
    if (CPU_ISSET(cpu, &set)) list += (list.empty() ? "" : ",") + std::to_string(cpu);
  REXLOG_INFO("Renut: {} nos núcleos {}{}", what, list, ok ? "" : " (recusado)");
}

}  // namespace

extern "C" void swordman_renut_render_thread_start() { PlaceCurrentThread("thread de render", false); }

// ---------------------------------------------------------------------------------------------------------------
// Threads de áudio nos núcleos pequenos (swordman_audio_little_cores, Xenia e Renut). Com poucos núcleos grandes
// (perfil SD480+ no RP5: 2 A77 e 4 A55) o Wwise (AK::EventManager, ~50% de um núcleo), o XMA, o Audio Worker e a
// SDLThread disputavam os grandes com as threads do jogo: presos nos pequenos, a luta a 540p subiu de 32-35 para
// 36-41 fps, sem underruns. Uma thread que procura as threads pelo nome (/proc/self/task/<tid>/comm), a cada 2 s no
// primeiro minuto e depois a cada 10 s, porque nascem ao longo do arranque.
// ---------------------------------------------------------------------------------------------------------------
#include <algorithm>
#include <chrono>
#include <dirent.h>
#include <set>
#include <thread>

REXCVAR_DEFINE_BOOL(swordman_audio_little_cores, false, "SwordMan",
                    "Threads de áudio (Wwise, XMA, Audio Worker, SDLThread) só nos núcleos pequenos")
    .lifecycle(rex::cvar::Lifecycle::kInitOnly);

namespace {

void AudioPlacementLoop(cpu_set_t little, std::string list) {
  static const char* const kNames[] = {"AK::", "XMA Decoder", "Audio Worker", "SDLThread"};
  std::set<int> done;
  for (int round = 0;; ++round) {
    if (DIR* dir = opendir("/proc/self/task")) {
      while (dirent* e = readdir(dir)) {
        const int tid = std::atoi(e->d_name);
        if (tid <= 0 || done.count(tid)) continue;
        std::ifstream f(std::string("/proc/self/task/") + e->d_name + "/comm");
        std::string name;
        std::getline(f, name);
        for (const char* prefix : kNames) {
          if (name.rfind(prefix, 0) != 0) continue;
          done.insert(tid);
          const bool ok = sched_setaffinity(tid, sizeof(little), &little) == 0;
          REXLOG_INFO("swordman: {} ({}) nos núcleos {}{}", name, tid, list, ok ? "" : " (recusado)");
          break;
        }
      }
      closedir(dir);
    }
    std::this_thread::sleep_for(std::chrono::seconds(round < 30 ? 2 : 10));
  }
}

}  // namespace

void SwordManStartAudioPlacement() {
  if (!REXCVAR_GET(swordman_audio_little_cores)) return;
  // Os pequenos: os ligados com o menor máximo do cpufreq.
  std::vector<std::pair<int, long>> cpus;
  for (int cpu = 0; cpu < CPU_SETSIZE; ++cpu) {
    std::ifstream f("/sys/devices/system/cpu/cpu" + std::to_string(cpu) + "/cpufreq/cpuinfo_max_freq");
    long khz = 0;
    if (!(f >> khz)) break;
    if (CoreOnline(cpu)) cpus.emplace_back(cpu, khz);
  }
  long lo = 0, hi = 0;
  for (const auto& [cpu, khz] : cpus) {
    lo = lo ? std::min(lo, khz) : khz;
    hi = std::max(hi, khz);
  }
  if (cpus.empty() || lo == hi) {
    REXLOG_INFO("swordman: swordman_audio_little_cores sem efeito (núcleos todos iguais)");
    return;
  }
  cpu_set_t little;
  CPU_ZERO(&little);
  std::string list;
  for (const auto& [cpu, khz] : cpus) {
    if (khz != lo) continue;
    CPU_SET(cpu, &little);
    list += (list.empty() ? "" : ",") + std::to_string(cpu);
  }
  std::thread(AudioPlacementLoop, little, list).detach();
}

void SwordManRenutPlaceMainThreadOnSwap() {
  static const bool once = (PlaceCurrentThread("thread principal do jogo", true), true);
  (void)once;
}

// ---------------------------------------------------------------------------------------------------------------
// GPU perdida no Renut (renut/0026 no plume): o caminho do Dragon Burst (SDK 0188, game_state_jni.cpp) em vez do
// gpu_reset.cpp do Zandatsu. VK_ERROR_DEVICE_LOST marca a perda e o app volta ao launcher com o aviso; ate la, a
// thread que ia submeter fica parada (no kgsl, um submit depois de um hang fecha o jogo com SIGSEGV).
// ---------------------------------------------------------------------------------------------------------------
extern "C" void dragonburst_gpu_lost(const char* reason);  // game_state_jni.cpp
extern "C" bool dragonburst_gpu_is_lost();

namespace swordman::gpu_reset {
void Trigger(const char* reason) {
  REXLOG_ERROR("Renut: GPU perdida ({}): a voltar ao launcher", reason);
  rex::FlushLogging();
  dragonburst_gpu_lost(reason);
}
}  // namespace swordman::gpu_reset

extern "C" void zandatsu_gpu_gate() {
  while (dragonburst_gpu_is_lost()) std::this_thread::sleep_for(std::chrono::milliseconds(100));
}

extern "C" void zandatsu_gpu_lost(const char* call) {
  swordman::gpu_reset::Trigger((std::string("VK_ERROR_DEVICE_LOST em ") + call).c_str());
}
