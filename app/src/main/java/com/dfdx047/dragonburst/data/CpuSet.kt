package com.dfdx047.dragonburst.data

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Qual dos dois conjuntos nativos do APK universal este CPU corre (GameLib).
 *
 * O conjunto ARMv8.2 (-march=armv8.2-a) usa os atomicos LSE em linha, mais RDM e CRC32: num CPU sem eles (ARMv8.0,
 * ex. Snapdragon 665 / Kryo 260, Cortex-A73/A53) o jogo morre com SIGILL. Nesses fica o conjunto ARMv8.0 ("_v80"),
 * em que os atomicos sao chamadas __aarch64_* que usam LSE em runtime onde existir.
 *
 * Le o AT_HWCAP de /proc/self/auxv (o mesmo que o getauxval do bionic); sem isso, a linha Features do /proc/cpuinfo.
 * Na duvida, ARMv8.0 (corre em todos).
 */
object CpuSet {
    private const val AT_HWCAP = 16L
    private const val HWCAP_CRC32 = 1L shl 7
    private const val HWCAP_ATOMICS = 1L shl 8
    private const val HWCAP_ASIMDRDM = 1L shl 12
    private const val NEEDED = HWCAP_CRC32 or HWCAP_ATOMICS or HWCAP_ASIMDRDM

    /** O HWCAP do processo, ou null se nao se conseguir ler. */
    private val hwcap: Long? by lazy {
        try {
            val b = ByteBuffer.wrap(File("/proc/self/auxv").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            var found: Long? = null
            while (b.remaining() >= 16) {
                val type = b.long
                val value = b.long
                if (type == AT_HWCAP) { found = value; break }
                if (type == 0L) break
            }
            found
        } catch (_: Exception) {
            null
        }
    }

    private val cpuinfoFeatures: Set<String>? by lazy {
        try {
            File("/proc/cpuinfo").useLines { lines ->
                lines.firstOrNull { it.startsWith("Features") }?.substringAfter(':')?.trim()?.split(Regex("\\s+"))?.toSet()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** O CPU tem o que o conjunto ARMv8.2 usa (LSE, RDM, CRC32). */
    val armv82: Boolean by lazy {
        hwcap?.let { it and NEEDED == NEEDED }
            ?: cpuinfoFeatures?.containsAll(listOf("atomics", "asimdrdm", "crc32"))
            ?: false
    }

    /** Nome do libgame deste conjunto nos assets (sem extensao). */
    val libgame: String get() = if (armv82) "libgame" else "libgame_v80"

    fun describe(): String = (if (armv82) "ARMv8.2" else "ARMv8.0") +
        (hwcap?.let { " hwcap %x".format(it) } ?: " cpuinfo")
}
