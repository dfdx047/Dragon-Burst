# Ferramentas do PC para os shaders do Renut (tools/renut-shaders.sh): XenosRecomp com RENUT_RECOMP (os contentores
# vem do tools/renut/extract_bl_shaders.py). Compiladas com o compilador do PC, nunca com o NDK.
#
# O DXC vem de um release Linux do DirectXShaderCompiler (DXC_ROOT: bin/dxc, lib/libdxcompiler.so); os cabecalhos
# sao os do dxc-bin do XenosRecomp (o checkout do mgr-nx-deko nao traz os binarios).
cmake_minimum_required(VERSION 3.20)
project(renut_host_tools C CXX)

set(CMAKE_CXX_STANDARD 17)

set(MGRNX "${CMAKE_CURRENT_LIST_DIR}/../../external/mgr-nx-deko" CACHE PATH "mgr-nx-deko")
set(DXC_ROOT "" CACHE PATH "Release Linux do DXC (bin/dxc, lib/libdxcompiler.so)")
set(XR "${MGRNX}/app/thirdparty/reblue-XenosRecomp")

add_library(Microsoft::DirectXShaderCompiler SHARED IMPORTED GLOBAL)
set_target_properties(Microsoft::DirectXShaderCompiler PROPERTIES
    IMPORTED_LOCATION "${DXC_ROOT}/lib/libdxcompiler.so"
    INTERFACE_INCLUDE_DIRECTORIES "${XR}/thirdparty/dxc-bin/inc")

set(XENOS_RECOMP_THIRDPARTY_ROOT "${XR}/thirdparty")
set(XENOS_RECOMP_RENUT ON CACHE BOOL "" FORCE)
add_subdirectory("${XR}/thirdparty" xr-thirdparty)
add_subdirectory("${XR}/XenosRecomp" xr)
# O libdxcompiler.so ao lado do executavel (RPATH), sem LD_LIBRARY_PATH.
set_target_properties(XenosRecomp PROPERTIES BUILD_RPATH "${DXC_ROOT}/lib")
