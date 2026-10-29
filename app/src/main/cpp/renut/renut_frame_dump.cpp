// Dump de um frame do Renut: ver renut_frame_dump.h.

#include "renut_frame_dump.h"

#include <cctype>
#include <cstdio>
#include <deque>
#include <filesystem>
#include <set>

#include <rex/cvar.h>
#include <rex/logging.h>
#include <rex/main_android.h>

REXCVAR_DEFINE_BOOL(swordman_renut_frame_dump, false, "SwordMan",
                    "Renut (diagnóstico): com files/frame_dump.req, escreve o frame seguinte em "
                    "logs/frame_dump_<N>.txt (cada draw, clear e resolve com shaders, alvos e texturas)")
    .lifecycle(rex::cvar::Lifecycle::kInitOnly);
REXCVAR_DEFINE_STRING(swordman_renut_frame_dump_ps, "", "SwordMan",
                      "Renut (diagnóstico, com swordman_renut_frame_dump): em vez do pedido por ficheiro, escreve os "
                      "frames em que este pixel shader (hash em hex, os primeiros 8 dígitos) desenha, um por variante "
                      "do que vem depois dele no frame, até 12 ficheiros")
    .lifecycle(rex::cvar::Lifecycle::kInitOnly);

REXCVAR_DEFINE_INT32(swordman_renut_frame_dump_ring, 0, "SwordMan",
                     "Renut (diagnóstico, com swordman_renut_frame_dump): os últimos N frames ficam em memória e o "
                     "pedido por ficheiro (files/frame_dump.req) escreve-os todos num só ficheiro, o pedido incluído "
                     "(apanha o que já passou quando se vê um defeito)")
    .lifecycle(rex::cvar::Lifecycle::kInitOnly);

namespace swordman::frame_dump {
namespace {

FILE* g_file = nullptr;
uint64_t g_frame = 0;      // o frame visto por último
uint64_t g_dumping = 0;    // o frame a ser escrito (0 = nenhum)
uint32_t g_dumps = 0;

// Anel (swordman_renut_frame_dump_ring): os frames anteriores, mais antigo primeiro.
std::deque<std::pair<uint64_t, std::string>> g_ring;

// Gatilho por PS (swordman_renut_frame_dump_ps): cada frame fica em memória e só vai para ficheiro se o PS desenhou
// nele (ou no anterior).
std::string g_trigger;
std::string g_buffer;
bool g_buffer_hit = false;

void WriteBuffer(uint64_t frame) {
  const auto path = rex::GetAndroidAppDirectory() / "logs" / ("frame_dump_" + std::to_string(++g_dumps) + ".txt");
  if (FILE* f = std::fopen(path.c_str(), "w")) {
    std::fprintf(f, "frame %llu\n", (unsigned long long)frame);
    std::fputs(g_buffer.c_str(), f);
    std::fclose(f);
    REXLOG_INFO("Renut: frame {} escrito (frame_dump_{}.txt, ps {})", frame, g_dumps, g_trigger);
  }
}

}  // namespace

bool On() {
  static const bool on = REXCVAR_GET(swordman_renut_frame_dump);
  return on;
}

bool Active(uint64_t frame) {
  if (!On()) return false;
  static const bool by_ps = [] {
    g_trigger = REXCVAR_GET(swordman_renut_frame_dump_ps);
    for (char& c : g_trigger) c = char(std::toupper(static_cast<unsigned char>(c)));
    return !g_trigger.empty();
  }();
  static const int ring = REXCVAR_GET(swordman_renut_frame_dump_ring);
  if (!by_ps && ring > 0) {
    if (frame == g_frame) return true;
    if (g_frame) {
      g_ring.emplace_back(g_frame, std::move(g_buffer));
      while (g_ring.size() > size_t(ring)) g_ring.pop_front();
    }
    g_frame = frame;
    g_buffer.clear();
    std::error_code ec;
    if (frame % 15 == 0 && std::filesystem::remove(rex::GetAndroidAppDirectory() / "frame_dump.req", ec)) {
      const auto path =
          rex::GetAndroidAppDirectory() / "logs" / ("frame_dump_" + std::to_string(++g_dumps) + ".txt");
      if (FILE* f = std::fopen(path.c_str(), "w")) {
        for (const auto& [n, text] : g_ring) {
          std::fprintf(f, "frame %llu\n", (unsigned long long)n);
          std::fputs(text.c_str(), f);
        }
        std::fclose(f);
        REXLOG_INFO("Renut: frames {}-{} escritos (frame_dump_{}.txt, anel)", g_ring.empty() ? 0 : g_ring.front().first,
                    g_ring.empty() ? 0 : g_ring.back().first, g_dumps);
      }
    }
    return true;
  }
  if (by_ps) {
    if (frame == g_frame) return g_dumps < 12;
    if (g_frame && g_buffer_hit && g_dumps < 12) {
      // A cauda do frame depois do PS (os PS dos draws, R = resolve, C = clear): cada variante uma vez.
      static std::set<std::string> seen;
      std::string tail;
      size_t at = g_buffer.find("ps " + g_trigger);
      at = g_buffer.rfind('\n', at);
      for (size_t p = at == std::string::npos ? 0 : at + 1; p < g_buffer.size();) {
        const size_t end = g_buffer.find('\n', p);
        const std::string line = g_buffer.substr(p, end - p);
        if (line.rfind("D ", 0) == 0) {
          const size_t ps = line.find(" ps ");
          tail += ps != std::string::npos ? line.substr(ps + 4, 8) + " " : "? ";
        } else if (!line.empty()) {
          tail += line.substr(0, 1) + " ";
        }
        if (end == std::string::npos) break;
        p = end + 1;
      }
      if (seen.insert(tail).second) {
        WriteBuffer(g_frame);
        REXLOG_INFO("Renut: frame_dump_{} cauda: {}", g_dumps, tail);
      }
    }
    g_frame = frame;
    g_buffer.clear();
    g_buffer_hit = false;
    return g_dumps < 12;
  }
  if (frame == g_frame) return g_dumping == frame;
  g_frame = frame;
  if (g_file) {
    std::fclose(g_file);
    g_file = nullptr;
    REXLOG_INFO("Renut: frame {} escrito (frame_dump_{}.txt)", g_dumping, g_dumps);
    g_dumping = 0;
  }
  // O pedido, uma vez a cada 30 frames.
  if (frame % 30) return false;
  const auto dir = rex::GetAndroidAppDirectory();
  std::error_code ec;
  if (!std::filesystem::remove(dir / "frame_dump.req", ec)) return false;
  ++g_dumps;
  const auto path = dir / "logs" / ("frame_dump_" + std::to_string(g_dumps) + ".txt");
  g_file = std::fopen(path.c_str(), "w");
  if (!g_file) return false;
  g_dumping = frame;
  std::fprintf(g_file, "frame %llu\n", (unsigned long long)frame);
  return true;
}

void Line(const std::string& line) {
  static const bool buffered = !g_trigger.empty() || REXCVAR_GET(swordman_renut_frame_dump_ring) > 0;
  if (buffered) {
    g_buffer += line;
    g_buffer += '\n';
    if (!g_trigger.empty() && !g_buffer_hit && line.find("ps " + g_trigger) != std::string::npos) g_buffer_hit = true;
    return;
  }
  if (!g_file) return;
  std::fputs(line.c_str(), g_file);
  std::fputc('\n', g_file);
}

}  // namespace swordman::frame_dump
