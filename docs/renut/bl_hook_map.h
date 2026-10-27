// DBZ Burst Limit (default.xex USA, SHA-1 aec598f8..., XDK 2.0.6683): code guards for Renut's hooks.
// Same format as mgr-nx-deko's d3d/mgr_hook_map.h: 64 bytes of the original image at each address and their FNV-32
// (offset basis 2166136261, prime 16777619). Image from `optxbox xex image default.xex` (never commit it).
// Evidence for each address: docs/renut/HOOKMAP_BL.md. Only the "certo" ones are guarded here.
#pragma once
#include <cstdint>
namespace renut::gpu::bl {
struct GuestCodeGuard { uint32_t address, bytes, fnv32; const char* name; };
inline constexpr GuestCodeGuard kGuestCodeGuards[] = {
  // Device creation. Direct3D_CreateDevice is inlined (LTCG) into the game's 0x820F92F8; the hook goes on
  // 0x820B7230 (the hardware setup it calls), which calls the three below.
  {0x820F92F8u, 64u, 0x781147F5u, "Direct3D_CreateDevice_inline_game"},
  {0x820B7230u, 64u, 0xD7C92B65u, "HardwareDeviceSetup"},
  {0x820B6E38u, 64u, 0xA2AE0241u, "InitializePresentationParameters"},
  {0x820B6B58u, 64u, 0xAC346E21u, "InitializeApiState"},
  {0x820B7670u, 64u, 0xFC6359A0u, "XenonGpu_Initialize"},
  // Ring, segments and fences.
  {0x820AE690u, 64u, 0x1BB85863u, "KickOff"},
  {0x820AE430u, 64u, 0x84158D45u, "KickOffSegment"},
  {0x820AD570u, 64u, 0x1E8A35A0u, "BeginRingBig"},
  {0x820AD898u, 64u, 0x5901C2B7u, "RingBufferDeviceAllocate"},
  {0x820ADAA0u, 64u, 0x72A8C079u, "AllocateInlineData"},
  {0x820ADC38u, 64u, 0x747E9FD4u, "NewSegment"},
  {0x820AE790u, 64u, 0x7BF6C936u, "BlockOnFence"},  // (CDevice*, Fence, Type): no NoKick argument
  {0x820B10E8u, 64u, 0xCE3698A3u, "CBlocker_Check"},
  // Presentation.
  {0x820B5F90u, 64u, 0x33B71D9Eu, "SynchronizeToPresentationInterval"},
  {0x820B64C0u, 64u, 0x4EF47090u, "Swap"},
  // Draws, clear, resolve.
  {0x820A8A20u, 64u, 0xA9165CF7u, "DrawVertices"},
  {0x820A8E10u, 64u, 0x30FEF06Bu, "DrawIndexedVertices"},
  {0x820A8538u, 64u, 0x6670567Cu, "BeginVertices"},
  {0x820A89D8u, 64u, 0xC5B30046u, "DrawVerticesUP"},  // BeginVertices + memcpy + inline end; not one of the 29
  {0x820BF910u, 64u, 0xEBABD8EFu, "ClearF"},
  {0x820BB348u, 64u, 0xCD6BDA8Du, "Resolve"},
  {0x820A9C38u, 64u, 0x3C8F883Fu, "WindowScissor"},
  // Shaders, declarations, resources. XGRegisterPixelShader is inlined into the two "_inline" functions.
  {0x820A27B8u, 64u, 0x9E4BEE46u, "XGRegisterVertexShader"},
  {0x820A24E8u, 64u, 0xB31BB30Bu, "CreatePixelShader_XGRegisterPixelShader_inline"},
  {0x820F9CF8u, 64u, 0x841742F9u, "GameShaderLoad_XGRegisterPixelShader_inline"},
  {0x820A2C78u, 64u, 0x670D9E1Bu, "CreateVertexDeclaration"},
  {0x820A9268u, 64u, 0x9812F838u, "D3DResource_Destroy"},
};

// Probable equivalents (different signature from MGR's; see HOOKMAP_BL.md lines 12 and 16). Guarded the same way,
// kept apart until a run confirms them.
inline constexpr GuestCodeGuard kGuestCodeGuardsProbable[] = {
  {0x820AE868u, 64u, 0x013DE29Cu, "InsertCallbackPacket"},  // (CDevice*, PRING, flags, pCallback, Context) -> PRING
  {0x820EA188u, 64u, 0x1502D496u, "InsertCallback_inline_game"},
  {0x820AEAF0u, 64u, 0xC1901C69u, "InitializeRingBuffer"},  // SetRingBufferParameters' internal equivalent
};

// Not in this game: IsFencePending (never used), EngineSwapProgress_reference (MGR's engine).
}  // namespace renut::gpu::bl
