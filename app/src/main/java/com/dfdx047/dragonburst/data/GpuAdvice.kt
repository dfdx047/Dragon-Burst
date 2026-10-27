package com.dfdx047.dragonburst.data

import android.content.Context
import android.content.pm.PackageManager
import com.dfdx047.dragonburst.drivers.DriverRepository
import com.dfdx047.dragonburst.drivers.TurnipSupport

/**
 * Sugestao do primeiro arranque: a GPU deste aparelho, a predefinicao recomendada
 * (recommendedPreset) e o driver, pelo que o driver do sistema tem e nao pela marca.
 *
 * Medido (docs/renut/RELATORIO_2026-10-27.md): Adreno 740 (Odin 2 Mini) com o driver do sistema
 * 59,8 FPS / 12,4 ms de GPU na luta; Turnip Rutabaga v3 30 FPS / 26 ms. O Turnip so e
 * necessario quando o driver do sistema nao chega ao Vulkan 1.1.
 */
object GpuAdvice {
    enum class Driver { SYSTEM, TURNIP_NEEDED, NOT_ADRENO }

    /** Vulkan 1.1 (VK_MAKE_API_VERSION(0, 1, 1, 0)). */
    private const val VULKAN_1_1 = (1 shl 22) or (1 shl 12)

    /** Versao Vulkan do driver do sistema (FEATURE_VULKAN_HARDWARE_VERSION), 0 se nao houver. */
    fun systemVulkanVersion(context: Context): Int =
        context.packageManager.systemAvailableFeatures
            .firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }?.version ?: 0

    fun vulkanLabel(version: Int): String =
        if (version == 0) "?" else "${version ushr 22}.${(version ushr 12) and 0x3FF}"

    fun driver(context: Context): Driver = when {
        !DriverRepository.isAdreno() -> Driver.NOT_ADRENO
        systemVulkanVersion(context) < VULKAN_1_1 -> Driver.TURNIP_NEEDED
        else -> Driver.SYSTEM
    }

    /** "Adreno 740 (QTI SM8550)", ou so o SoC fora da Qualcomm. */
    fun deviceLabel(): String {
        val soc = DriverRepository.socName().trim()
        if (!DriverRepository.isAdreno()) return soc
        val adreno = TurnipSupport.detect().adreno
        return if (adreno > 0) "Adreno $adreno ($soc)" else soc
    }
}
