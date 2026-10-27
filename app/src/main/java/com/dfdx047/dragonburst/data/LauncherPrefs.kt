package com.dfdx047.dragonburst.data

import android.content.Context
import android.content.SharedPreferences

enum class ThemeMode(val key: String) { DRAGON("dragon"), MATERIAL_YOU("material_you") }
enum class DarkMode(val key: String) { SYSTEM("system"), LIGHT("light"), DARK("dark") }
enum class AppLanguage(val tag: String) { SYSTEM(""), PORTUGUESE("pt"), ENGLISH("en"), SPANISH("es") }

/** Orientacao do jogo. Os valores sao lidos tambem pelo GameActivity (Java). */
enum class GameOrientation(val key: String) { LANDSCAPE("landscape"), REVERSE("reverse"), AUTO("auto") }

/** Aspeto dos controles por toque. */
enum class ControlStyle(val key: String) { DRAGON("dragon"), XBOX("xbox"), PLAYSTATION("playstation") }

/** Direcional na tela: D-pad de 8 direcoes (padrao, melhor para os dash do jogo 2D) ou analogico. */
enum class Directional(val key: String) { DPAD("dpad"), STICK("stick") }

/**
 * Online em rede local (o "metodo antigo" do Burst Limit Recompiled, sem lobby): UDP
 * direto entre os IPs. O GameActivity passa REX_XNET_IP (o IP do aparelho) e, a quem
 * entra, REX_XNET_SEARCH_IP (o IP do anfitriao).
 */
enum class LanMode(val key: String) { OFF("off"), HOST("host"), JOIN("join") }

/**
 * Controles na tela. AUTO: aparecem ao tocar na tela e somem quando se usa um
 * comando fisico; ON: sempre; OFF: nunca (a engrenagem e o pause continuam).
 */
enum class TouchMode(val key: String) { AUTO("auto"), ON("on"), OFF("off") }

/** Indicacao do botao do comando no canto de cada botao (estilo Dragon Burst). */
enum class ButtonBadge(val key: String) { XBOX("xbox"), PLAYSTATION("playstation"), NONE("none") }

inline fun <reified T : Enum<T>> enumByKey(value: String?, default: T, key: (T) -> String): T =
    enumValues<T>().firstOrNull { key(it) == value } ?: default

/**
 * Preferencias do launcher (nao do jogo: essas vivem no burstlimit.toml).
 * O GameActivity le as mesmas chaves (PREFS_NAME, KEY_*).
 */
class LauncherPrefs(context: Context) {
    private val appContext = context.applicationContext

    // O jogo corre noutro processo (":game"). MODE_MULTI_PROCESS (obsoleto mas ainda
    // funcional) faz o Android reler o ficheiro quando o outro processo o mudou: sem
    // isto, o launcher gravaria por cima do que se mudou no menu dentro do jogo.
    @Suppress("DEPRECATION")
    private val prefs: SharedPreferences
        get() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_MULTI_PROCESS)

    var themeMode: ThemeMode
        get() = enumByKey(prefs.getString(KEY_THEME, null), ThemeMode.DRAGON) { it.key }
        set(v) = prefs.edit().putString(KEY_THEME, v.key).apply()

    var darkMode: DarkMode
        get() = enumByKey(prefs.getString(KEY_DARK, null), DarkMode.DARK) { it.key }
        set(v) = prefs.edit().putString(KEY_DARK, v.key).apply()

    var gameOrientation: GameOrientation
        get() = enumByKey(prefs.getString(KEY_ORIENTATION, null), GameOrientation.AUTO) { it.key }
        set(v) = prefs.edit().putString(KEY_ORIENTATION, v.key).apply()

    /**
     * Correcoes para o Turnip nos Adreno 6xx: bits independentes (ver TURNIP_* no
     * GameActivity, que le esta chave antes de o driver carregar).
     */
    var turnipCompatFlags: Int
        get() = prefs.getInt(KEY_TURNIP_COMPAT_FLAGS, 0)
        set(v) = prefs.edit().putInt(KEY_TURNIP_COMPAT_FLAGS, v).apply()

    /** Bisect do Turnip (TurnipSupport.BISECT): um passo por arranque do jogo. */
    var turnipBisect: Boolean
        get() = prefs.getBoolean("turnip_bisect", false)
        set(v) = prefs.edit().putBoolean("turnip_bisect", v).apply()
    var turnipBisectStep: Int
        get() = prefs.getInt("turnip_bisect_step", 0)
        set(v) = prefs.edit().putInt("turnip_bisect_step", v).apply()

    /** Driver que fechou o jogo no arranque (o GameActivity voltou ao do sistema); "" = nenhum. */
    val driverBootFailed: String
        get() = prefs.getString("driver_boot_failed", "") ?: ""

    fun clearDriverBootState() {
        prefs.edit().remove("driver_boot_failed").remove("driver_boot_pending").apply()
    }

    var controlStyle: ControlStyle
        get() = enumByKey(prefs.getString(KEY_CONTROL_STYLE, null), ControlStyle.DRAGON) { it.key }
        set(v) = prefs.edit().putString(KEY_CONTROL_STYLE, v.key).apply()

    var directional: Directional
        get() = enumByKey(prefs.getString(KEY_DIRECTIONAL, null), Directional.DPAD) { it.key }
        set(v) = prefs.edit().putString(KEY_DIRECTIONAL, v.key).apply()

    var buttonBadge: ButtonBadge
        get() = enumByKey(prefs.getString(KEY_BADGE, null), ButtonBadge.XBOX) { it.key }
        set(v) = prefs.edit().putString(KEY_BADGE, v.key).apply()

    var lanMode: LanMode
        get() = enumByKey(prefs.getString(KEY_LAN_MODE, null), LanMode.OFF) { it.key }
        set(v) = prefs.edit().putString(KEY_LAN_MODE, v.key).apply()

    /** IPv4 do anfitriao (modo JOIN). */
    var lanHostIp: String
        get() = prefs.getString(KEY_LAN_HOST_IP, "") ?: ""
        set(v) = prefs.edit().putString(KEY_LAN_HOST_IP, v).apply()

    /** Pausar o jogo enquanto o menu dos controles ou o menu rapido do port estao abertos. */
    var pauseOnMenus: Boolean
        get() = prefs.getBoolean(KEY_PAUSE_MENUS, true)
        set(v) = prefs.edit().putBoolean(KEY_PAUSE_MENUS, v).apply()

    var touchMode: TouchMode
        get() {
            val v = prefs.getString(KEY_TOUCH_MODE, null)
            if (v == null && !prefs.getBoolean(KEY_TOUCH_ENABLED, true)) return TouchMode.OFF
            return enumByKey(v, TouchMode.AUTO) { it.key }
        }
        set(v) = prefs.edit().putString(KEY_TOUCH_MODE, v.key).apply()

    var touchEnabled: Boolean
        get() = prefs.getBoolean(KEY_TOUCH_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_TOUCH_ENABLED, v).apply()

    var touchHideWithPad: Boolean
        get() = prefs.getBoolean(KEY_TOUCH_HIDE_WITH_PAD, true)
        set(v) = prefs.edit().putBoolean(KEY_TOUCH_HIDE_WITH_PAD, v).apply()

    var touchHaptics: Boolean
        get() = prefs.getBoolean(KEY_TOUCH_HAPTICS, true)
        set(v) = prefs.edit().putBoolean(KEY_TOUCH_HAPTICS, v).apply()

    /** Opacidade global dos controles (0.1-1), multiplica a de cada botao. */
    var touchOpacity: Float
        get() = prefs.getFloat(KEY_TOUCH_OPACITY, 0.75f)
        set(v) = prefs.edit().putFloat(KEY_TOUCH_OPACITY, v).apply()

    var turnipNoticeShown: Boolean
        get() = prefs.getBoolean(KEY_TURNIP_NOTICE, false)
        set(v) = prefs.edit().putBoolean(KEY_TURNIP_NOTICE, v).apply()

    /** Aviso de primeiro arranque sobre GPUs/drivers (substitui o aviso so do Turnip). */
    var gpuNoticeShown: Boolean
        get() = prefs.getBoolean(KEY_GPU_NOTICE, false)
        set(v) = prefs.edit().putBoolean(KEY_GPU_NOTICE, v).apply()

    /** Quando o jogo perdeu a GPU (ms, 0 = nada por avisar). Gravado no processo do jogo. */
    var gpuLostAt: Long
        get() = prefs.getLong(KEY_GPU_LOST_AT, 0L)
        set(v) {
            prefs.edit().putLong(KEY_GPU_LOST_AT, v).commit()
        }

    /** Migracao unica: liga dbz3_rt_direct para todos (caminho principal de desempenho). */
    var rtDirectMigrated: Boolean
        get() = prefs.getBoolean(KEY_RT_DIRECT_MIGRATED, false)
        set(v) = prefs.edit().putBoolean(KEY_RT_DIRECT_MIGRATED, v).apply()

    var checkUpdatesOnStart: Boolean
        get() = prefs.getBoolean(KEY_CHECK_UPDATES, true)
        set(v) = prefs.edit().putBoolean(KEY_CHECK_UPDATES, v).apply()

    /** Versao que o usuario mandou ignorar no pop-up de atualizacao. */
    var skippedUpdate: String?
        get() = prefs.getString(KEY_SKIPPED_UPDATE, null)
        set(v) = prefs.edit().putString(KEY_SKIPPED_UPDATE, v).apply()

    companion object {
        const val PREFS_NAME = "dragonburst"
        const val KEY_THEME = "theme"
        const val KEY_DARK = "dark_mode"
        const val KEY_ORIENTATION = "game_orientation"
        const val KEY_TURNIP_COMPAT_FLAGS = "turnip_compat_flags"
        const val KEY_CONTROL_STYLE = "control_style"
        const val KEY_DIRECTIONAL = "touch_directional"
        const val KEY_BADGE = "touch_badge"
        const val KEY_PAUSE_MENUS = "pause_on_menus"
        const val KEY_TOUCH_MODE = "touch_mode"
        const val KEY_TOUCH_ENABLED = "touch_enabled"
        const val KEY_TOUCH_HIDE_WITH_PAD = "touch_hide_with_pad"
        const val KEY_TOUCH_HAPTICS = "touch_haptics"
        const val KEY_TOUCH_OPACITY = "touch_opacity"
        const val KEY_TURNIP_NOTICE = "turnip_notice_shown"
        const val KEY_GPU_NOTICE = "gpu_notice_shown"
        const val KEY_GPU_LOST_AT = "gpu_lost_at"
        const val KEY_RT_DIRECT_MIGRATED = "rt_direct_migrated"
        const val KEY_CHECK_UPDATES = "check_updates"
        const val KEY_SKIPPED_UPDATE = "skipped_update"
        const val KEY_LAN_MODE = "lan_mode"
        const val KEY_LAN_HOST_IP = "lan_host_ip"
    }
}
