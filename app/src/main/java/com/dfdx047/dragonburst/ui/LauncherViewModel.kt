package com.dfdx047.dragonburst.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dfdx047.dragonburst.R
import com.dfdx047.dragonburst.data.AppLanguage
import com.dfdx047.dragonburst.data.ControlStyle
import com.dfdx047.dragonburst.data.DarkMode
import com.dfdx047.dragonburst.data.FileStatus
import com.dfdx047.dragonburst.data.GameConfig
import com.dfdx047.dragonburst.data.SaveFiles
import com.dfdx047.dragonburst.data.GameFiles
import com.dfdx047.dragonburst.data.GameInstall
import com.dfdx047.dragonburst.data.GameLib
import com.dfdx047.dragonburst.data.GameOrientation
import com.dfdx047.dragonburst.data.ImportException
import com.dfdx047.dragonburst.data.ImportProgress
import com.dfdx047.dragonburst.data.LauncherPrefs
import com.dfdx047.dragonburst.data.ThemeMode
import com.dfdx047.dragonburst.drivers.DriverAsset
import com.dfdx047.dragonburst.drivers.DriverException
import com.dfdx047.dragonburst.drivers.DriverRelease
import com.dfdx047.dragonburst.drivers.DriverRepository
import com.dfdx047.dragonburst.drivers.DriverSource
import com.dfdx047.dragonburst.drivers.InstalledDriver
import com.dfdx047.dragonburst.game.GameActivity
import com.dfdx047.dragonburst.mods.ModInfo
import com.dfdx047.dragonburst.mods.ModInstallException
import com.dfdx047.dragonburst.mods.ModRepository
import com.dfdx047.dragonburst.textures.RuntimeTextureLine
import com.dfdx047.dragonburst.textures.TextureInstallException
import com.dfdx047.dragonburst.textures.TextureIssue
import com.dfdx047.dragonburst.textures.TexturePackInfo
import com.dfdx047.dragonburst.textures.TexturePackReport
import com.dfdx047.dragonburst.textures.TexturePacks
import com.dfdx047.dragonburst.update.UpdateChecker
import com.dfdx047.dragonburst.update.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Mensagem curta (snackbar), resolvida no ecra com o idioma da atividade. */
data class UiMessage(@StringRes val res: Int, val args: List<Any> = emptyList(), val id: Long = System.nanoTime())

/** Operacao longa em curso (importar, verificar, descarregar...). */
data class Busy(@StringRes val label: Int, val detail: String = "", val progress: Float? = null)

enum class UpdateState { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, FAILED }

class LauncherViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = LauncherPrefs(app)

    // ------------------------------------------------------------- aparencia
    var themeMode by mutableStateOf(prefs.themeMode)
        private set
    var darkMode by mutableStateOf(prefs.darkMode)
        private set
    var appLanguage by mutableStateOf(currentAppLanguage())
        private set

    fun setTheme(v: ThemeMode) { themeMode = v; prefs.themeMode = v }
    fun setDark(v: DarkMode) { darkMode = v; prefs.darkMode = v }

    fun setLanguage(v: AppLanguage) {
        appLanguage = v
        AppCompatDelegate.setApplicationLocales(
            if (v == AppLanguage.SYSTEM) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(v.tag),
        )
    }

    private fun currentAppLanguage(): AppLanguage {
        val tag = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        return when {
            tag.startsWith("pt") -> AppLanguage.PORTUGUESE
            tag.startsWith("en") -> AppLanguage.ENGLISH
            tag.startsWith("es") -> AppLanguage.SPANISH
            else -> AppLanguage.SYSTEM
        }
    }

    // ------------------------------------------------------- jogo e launcher
    var gameOrientation by mutableStateOf(prefs.gameOrientation)
        private set
    fun setOrientation(v: GameOrientation) { gameOrientation = v; prefs.gameOrientation = v }

    var turnipCompatFlags by mutableStateOf(prefs.turnipCompatFlags)
        private set
    fun setTurnipCompatFlag(bit: Int, on: Boolean) {
        turnipCompatFlags = if (on) turnipCompatFlags or bit else turnipCompatFlags and bit.inv()
        prefs.turnipCompatFlags = turnipCompatFlags
    }

    var turnipBisect by mutableStateOf(prefs.turnipBisect)
        private set
    var turnipBisectStep by mutableStateOf(prefs.turnipBisectStep)
        private set
    fun changeTurnipBisect(on: Boolean) {
        turnipBisect = on
        prefs.turnipBisect = on
        if (on) {
            turnipBisectStep = 0
            prefs.turnipBisectStep = 0
        }
    }
    /** O GameActivity avanca o passo; ao voltar ao launcher le-se de novo. */
    fun refreshTurnipBisect() {
        turnipBisectStep = prefs.turnipBisectStep
    }

    var controlStyle by mutableStateOf(prefs.controlStyle)
        private set
    // JvmName: evita o conflito com o setter privado da propriedade (mesma assinatura JVM).
    @JvmName("changeControlStyle")
    fun setControlStyle(v: ControlStyle) { controlStyle = v; prefs.controlStyle = v }

    var directional by mutableStateOf(prefs.directional)
        private set
    @JvmName("changeDirectional")
    fun setDirectional(v: com.dfdx047.dragonburst.data.Directional) { directional = v; prefs.directional = v }

    var buttonBadge by mutableStateOf(prefs.buttonBadge)
        private set
    @JvmName("changeButtonBadge")
    fun setButtonBadge(v: com.dfdx047.dragonburst.data.ButtonBadge) { buttonBadge = v; prefs.buttonBadge = v }

    var lanMode by mutableStateOf(prefs.lanMode)
        private set
    @JvmName("changeLanMode")
    fun setLanMode(v: com.dfdx047.dragonburst.data.LanMode) { lanMode = v; prefs.lanMode = v }

    var lanHostIp by mutableStateOf(prefs.lanHostIp)
        private set
    @JvmName("changeLanHostIp")
    fun setLanHostIp(v: String) {
        val clean = v.filter { it.isDigit() || it == '.' }.take(15)
        lanHostIp = clean
        prefs.lanHostIp = clean
    }

    var pauseOnMenus by mutableStateOf(prefs.pauseOnMenus)
        private set
    @JvmName("changePauseOnMenus")
    fun setPauseOnMenus(v: Boolean) { pauseOnMenus = v; prefs.pauseOnMenus = v }

    var touchMode by mutableStateOf(prefs.touchMode)
        private set
    @JvmName("changeTouchMode")
    fun setTouchMode(v: com.dfdx047.dragonburst.data.TouchMode) { touchMode = v; prefs.touchMode = v }

    var touchEnabled by mutableStateOf(prefs.touchEnabled)
        private set
    // JvmName: evita o conflito com o setter privado da propriedade (mesma assinatura JVM).
    @JvmName("changeTouchEnabled")
    fun setTouchEnabled(v: Boolean) { touchEnabled = v; prefs.touchEnabled = v }

    var touchHideWithPad by mutableStateOf(prefs.touchHideWithPad)
        private set
    // JvmName: evita o conflito com o setter privado da propriedade (mesma assinatura JVM).
    @JvmName("changeTouchHideWithPad")
    fun setTouchHideWithPad(v: Boolean) { touchHideWithPad = v; prefs.touchHideWithPad = v }

    var touchHaptics by mutableStateOf(prefs.touchHaptics)
        private set
    // JvmName: evita o conflito com o setter privado da propriedade (mesma assinatura JVM).
    @JvmName("changeTouchHaptics")
    fun setTouchHaptics(v: Boolean) { touchHaptics = v; prefs.touchHaptics = v }

    var touchOpacity by mutableStateOf(prefs.touchOpacity)
        private set
    // JvmName: evita o conflito com o setter privado da propriedade (mesma assinatura JVM).
    @JvmName("changeTouchOpacity")
    fun setTouchOpacity(v: Float) { touchOpacity = v; prefs.touchOpacity = v }

    var checkUpdatesOnStart by mutableStateOf(prefs.checkUpdatesOnStart)
        private set
    // JvmName: evita o conflito com o setter privado da propriedade (mesma assinatura JVM).
    @JvmName("changeCheckUpdatesOnStart")
    fun setCheckUpdatesOnStart(v: Boolean) { checkUpdatesOnStart = v; prefs.checkUpdatesOnStart = v }

    var config by mutableStateOf(GameConfig())
        private set

    fun updateConfig(transform: (GameConfig) -> GameConfig) {
        val next = transform(config)
        if (next == config) return
        config = next
        viewModelScope.launch(Dispatchers.IO) { GameConfig.save(next) }
    }

    var busy by mutableStateOf<Busy?>(null)
        private set
    var message by mutableStateOf<UiMessage?>(null)
    private fun say(@StringRes res: Int, vararg args: Any) {
        message = UiMessage(res, args.toList())
    }

    var install by mutableStateOf<GameInstall?>(null)
        private set

    /** Ao voltar ao launcher: o menu rapido do jogo pode ter mudado o toml. */
    /** O jogo perdeu a GPU na ultima vez (SDK 0188): aviso ao voltar ao launcher. */
    var showGpuLost by mutableStateOf(false)
        private set

    fun dismissGpuLost() {
        showGpuLost = false
    }

    fun reloadAfterGame() {
        viewModelScope.launch(Dispatchers.IO) {
            val c = GameConfig.load()
            val lost = prefs.gpuLostAt != 0L
            if (lost) prefs.gpuLostAt = 0L
            withContext(Dispatchers.Main) {
                config = c
                if (lost) showGpuLost = true
            }
            runtimeTextureLog = TexturePacks.runtimeLog()
        }
    }

    fun refreshInstall(fullHash: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (fullHash) busy = Busy(R.string.busy_verifying)
            val result = try {
                GameFiles.check { p ->
                    if (p is ImportProgress.Hashing && fullHash) {
                        busy = Busy(R.string.busy_verifying, p.file, p.done.toFloat() / p.total.coerceAtLeast(1))
                    }
                }
            } finally {
                if (fullHash) busy = null
            }
            install = result
            if (fullHash) {
                if (result.playable) say(R.string.msg_verify_ok)
                else say(R.string.msg_verify_problems)
            }
        }
    }

    private fun importProgress(p: ImportProgress): Busy = when (p) {
        is ImportProgress.Copying -> {
            // "ficheiro · 85 MB/s · falta 1 min 20 s"
            val rate = p.bytesPerSecond
            val extra = if (rate > 0) {
                val left = ((p.total - p.done).coerceAtLeast(0) / rate)
                val eta = if (left >= 60) "${left / 60} min ${left % 60} s" else "$left s"
                " · ${rate / (1 shl 20)} MB/s · " + getApplication<Application>().getString(R.string.busy_eta, eta)
            } else {
                ""
            }
            Busy(R.string.busy_importing, p.file.substringAfterLast('/') + extra, p.done.toFloat() / p.total.coerceAtLeast(1))
        }
        is ImportProgress.Hashing -> Busy(R.string.busy_checking_xex, p.file, p.done.toFloat() / p.total.coerceAtLeast(1))
        is ImportProgress.Reading -> Busy(R.string.busy_reading_disc, p.file)
        is ImportProgress.Unpacking -> Busy(R.string.busy_unpacking, "${p.done / (1 shl 20)} / ${p.total / (1 shl 20)} MB", p.done.toFloat() / p.total.coerceAtLeast(1))
    }

    /** Ultimo problema da importacao (fica no cartao dos arquivos ate a proxima). */
    var importIssue by mutableStateOf<Pair<Int, String>?>(null)

    private fun importError(e: ImportException) {
        val res = when (e.reason) {
            ImportException.Reason.NO_XEX -> R.string.err_import_no_xex
            ImportException.Reason.NO_DATA_FOLDER -> R.string.err_import_no_data
            ImportException.Reason.WRONG_REGION -> R.string.err_import_region
            ImportException.Reason.BAD_XEX -> R.string.err_import_bad_xex
            ImportException.Reason.NOT_A_DISC -> R.string.err_import_not_disc
            ImportException.Reason.MISSING_REQUIRED -> R.string.err_import_missing
            ImportException.Reason.NO_SPACE -> R.string.err_import_space
            ImportException.Reason.IO -> R.string.err_import_io
            ImportException.Reason.OTHER_GAME -> R.string.err_import_other_game
            ImportException.Reason.MODIFIED_XEX -> R.string.err_import_modified
            ImportException.Reason.TRUNCATED -> R.string.err_import_truncated
            ImportException.Reason.BAD_DATA -> R.string.err_import_bad_data
            ImportException.Reason.UNSUPPORTED_VERSION -> R.string.err_import_unsupported
        }
        importIssue = res to e.detail.ifEmpty { "-" }
        say(
            when (e.reason) {
                ImportException.Reason.NO_XEX -> R.string.err_import_no_xex
                ImportException.Reason.NO_DATA_FOLDER -> R.string.err_import_no_data
                ImportException.Reason.WRONG_REGION -> R.string.err_import_region
                ImportException.Reason.BAD_XEX -> R.string.err_import_bad_xex
                ImportException.Reason.NOT_A_DISC -> R.string.err_import_not_disc
                ImportException.Reason.MISSING_REQUIRED -> R.string.err_import_missing
                ImportException.Reason.NO_SPACE -> R.string.err_import_space
                ImportException.Reason.IO -> R.string.err_import_io
                else -> res
            },
            e.detail.ifEmpty { "-" },
        )
    }

    /** Importa de uma pasta ja extraida (com default.xex e LONG2DATA/). */
    fun importGame(uri: Uri) {
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            busy = Busy(R.string.busy_importing)
            try {
                importIssue = null
                GameFiles.importFolder(ctx, uri) { p -> busy = importProgress(p) }
                say(R.string.msg_import_ok)
            } catch (e: ImportException) {
                importError(e)
            } finally {
                busy = null
            }
            install = GameFiles.check()
        }
    }

    /** Importa direto da imagem do disco (.iso do Xbox 360), como o instalador do PC. */
    fun importIso(uri: Uri) {
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            busy = Busy(R.string.busy_reading_disc)
            try {
                importIssue = null
                GameFiles.importIso(ctx, uri) { p -> busy = importProgress(p) }
                say(R.string.msg_import_ok)
            } catch (e: ImportException) {
                importError(e)
            } finally {
                busy = null
            }
            install = GameFiles.check()
        }
    }

    /** Grava as opcoes e abre o jogo. */
    fun play(context: Context) {
        val inst = install
        if (inst == null || !inst.playable) {
            say(if (inst?.xex?.status == FileStatus.DIFFERENT) R.string.err_import_region else R.string.msg_not_installed, "-")
            return
        }
        viewModelScope.launch {
            // O libgame.so deste APK (depois de uma atualizacao, ou de um import antigo).
            val ok = withContext(Dispatchers.IO) {
                if (GameLib.ready(context)) return@withContext true
                try {
                    GameLib.install(context) { p -> busy = importProgress(p) }
                    true
                } catch (e: ImportException) {
                    importError(e)
                    false
                } finally {
                    busy = null
                }
            }
            if (!ok) return@launch
            withContext(Dispatchers.IO) { GameConfig.save(config) }
            context.startActivity(Intent(context, GameActivity::class.java).setAction(PLAY_ACTION))
        }
    }

    fun createShortcut(context: Context) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            say(R.string.msg_shortcut_unsupported)
            return
        }
        val info = ShortcutInfoCompat.Builder(context, "play_burstlimit")
            .setShortLabel(context.getString(R.string.shortcut_short))
            .setLongLabel(context.getString(R.string.shortcut_long))
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(Intent(context, GameActivity::class.java).setAction(PLAY_ACTION))
            .build()
        if (ShortcutManagerCompat.requestPinShortcut(context, info, null)) say(R.string.msg_shortcut_requested)
        else say(R.string.msg_shortcut_unsupported)
    }

    // ------------------------------------------------------------------ mods
    var mods by mutableStateOf<List<ModInfo>>(emptyList())
        private set

    fun refreshMods() {
        viewModelScope.launch(Dispatchers.IO) { mods = ModRepository.listAll() }
    }

    fun setModEnabled(mod: ModInfo, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            ModRepository.setEnabled(mod, enabled)
            mods = ModRepository.listAll()
        }
    }

    fun deleteMod(mod: ModInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            if (ModRepository.delete(mod)) say(R.string.msg_mod_deleted, mod.displayName)
            mods = ModRepository.listAll()
        }
    }

    fun fixMod(mod: ModInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            say(if (ModRepository.fixNested(mod)) R.string.msg_mod_fixed else R.string.msg_mod_fix_failed, mod.displayName)
            mods = ModRepository.listAll()
        }
    }

    private fun displayName(uri: Uri): String {
        val ctx = getApplication<Application>()
        return ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "mod.zip"
    }

    fun installModZip(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) { doInstallZip(uri) }
    }

    private fun doInstallZip(uri: Uri) {
        val ctx = getApplication<Application>()
        val name = displayName(uri)
        busy = Busy(R.string.busy_installing_mod, name)
        try {
            val installed = ModRepository.installZip(ctx, uri, name) { bytes ->
                busy = Busy(R.string.busy_installing_mod, "$name — ${bytes / (1024 * 1024)} MB")
            }
            say(R.string.msg_mod_installed, installed.joinToString())
        } catch (e: ModInstallException) {
            say(
                when (e.reason) {
                    ModInstallException.Reason.NOT_ZIP -> R.string.err_mod_not_zip
                    ModInstallException.Reason.EMPTY -> R.string.err_mod_empty
                    ModInstallException.Reason.UNSAFE_PATH -> R.string.err_mod_unsafe
                    ModInstallException.Reason.IO -> R.string.err_mod_io
                },
                e.message ?: "-",
            )
        } finally {
            busy = null
        }
        mods = ModRepository.listAll()
    }

    // -------------------------------------------------------------- texturas
    // ------------------------------------------------------------------ saves
    var saves by mutableStateOf<List<SaveFiles.SaveZip>>(emptyList())
        private set

    fun refreshSaves() {
        viewModelScope.launch(Dispatchers.IO) { saves = SaveFiles.list() }
    }

    fun exportSave(save: SaveFiles.SaveZip) {
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                say(R.string.msg_logs_exported, SaveFiles.exportToDownloads(ctx, save))
            } catch (e: Exception) {
                say(R.string.err_save_io, e.message ?: "-")
            }
        }
    }

    fun addSave(uri: Uri) {
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                say(R.string.msg_save_added, SaveFiles.addFromUri(ctx, uri))
            } catch (e: IllegalArgumentException) {
                say(R.string.err_mod_not_zip, e.message ?: "-")
            } catch (e: Exception) {
                say(R.string.err_save_io, e.message ?: "-")
            }
            saves = SaveFiles.list()
        }
    }

    fun deleteSave(save: SaveFiles.SaveZip) {
        viewModelScope.launch(Dispatchers.IO) {
            if (SaveFiles.delete(save)) say(R.string.msg_mod_deleted, save.file.name)
            saves = SaveFiles.list()
        }
    }

    var texturePacks by mutableStateOf<List<TexturePackInfo>>(emptyList())
        private set
    /** Verificacao do pack ativo (null = ainda nao verificado). */
    var textureReport by mutableStateOf<TexturePackReport?>(null)
        private set
    var runtimeTextureLog by mutableStateOf<List<RuntimeTextureLine>>(emptyList())
        private set

    fun refreshTexturePacks() {
        viewModelScope.launch(Dispatchers.IO) { texturePacks = TexturePacks.list() }
    }

    fun validateTextures() {
        val active = config.texturePackName
        viewModelScope.launch(Dispatchers.IO) {
            val pack = TexturePacks.list().firstOrNull { it.name == active } ?: run {
                textureReport = null
                return@launch
            }
            busy = Busy(R.string.busy_validating_textures)
            try {
                textureReport = TexturePacks.validate(pack) { done, total ->
                    busy = Busy(R.string.busy_validating_textures, "$done / $total", done.toFloat() / total.coerceAtLeast(1))
                }
                runtimeTextureLog = TexturePacks.runtimeLog()
            } finally {
                busy = null
            }
        }
    }

    /** O pack escolhido passa a ser o texture_folder do jogo (um de cada vez, como no PC). */
    fun selectTexturePack(name: String) {
        updateConfig { it.copy(texturePackName = name, texturePack = true) }
        textureReport = null
        validateTextures()
    }

    fun deleteTexturePack(pack: TexturePackInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            if (TexturePacks.delete(pack)) say(R.string.msg_mod_deleted, pack.name)
            if (config.texturePackName == pack.name) {
                withContext(Dispatchers.Main) { updateConfig { it.copy(texturePackName = "") } }
            }
            texturePacks = TexturePacks.list()
            textureReport = null
        }
    }

    fun refreshRuntimeLog() {
        viewModelScope.launch(Dispatchers.IO) { runtimeTextureLog = TexturePacks.runtimeLog() }
    }

    fun importTextureImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            busy = Busy(R.string.busy_importing_textures)
            try {
                val (ok, refused) = TexturePacks.importImages(ctx, uris)
                if (refused.isEmpty()) {
                    say(R.string.msg_textures_imported, ok)
                } else {
                    val first = refused.first()
                    say(R.string.msg_textures_refused, ok, refused.size, first.first, textureIssueName(first.second))
                }
            } finally {
                busy = null
            }
            texturePacks = TexturePacks.list()
            withContext(Dispatchers.Main) { selectTexturePack(TexturePacks.USER_PACK) }
        }
    }

    private fun textureIssueName(i: TextureIssue): String {
        val ctx = getApplication<Application>()
        return ctx.getString(
            when (i) {
                TextureIssue.OK -> R.string.tex_issue_ok
                TextureIssue.BAD_NAME -> R.string.tex_issue_bad_name
                TextureIssue.UNREADABLE -> R.string.tex_issue_unreadable
                TextureIssue.DDS_FORMAT -> R.string.tex_issue_dds
                TextureIssue.DUPLICATE -> R.string.tex_issue_duplicate
            },
        )
    }

    fun installTexturePackZip(uri: Uri) {
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            busy = Busy(R.string.busy_installing_textures)
            var installed: String? = null
            try {
                val (name, count) = TexturePacks.installZip(ctx, uri) { bytes ->
                    busy = Busy(R.string.busy_installing_textures, "${bytes / (1024 * 1024)} MB")
                }
                installed = name
                say(R.string.msg_texture_pack_installed, name, count)
            } catch (e: TextureInstallException) {
                say(
                    when (e.reason) {
                        TextureInstallException.Reason.NOT_ZIP -> R.string.err_mod_not_zip
                        TextureInstallException.Reason.EMPTY -> R.string.err_texture_pack_empty
                        TextureInstallException.Reason.IO -> R.string.err_mod_io
                    },
                    e.message ?: "-",
                )
            } finally {
                busy = null
            }
            texturePacks = TexturePacks.list()
            installed?.let { n -> withContext(Dispatchers.Main) { selectTexturePack(n) } }
        }
    }

    // --------------------------------------------------------------- drivers
    val isAdreno = DriverRepository.isAdreno()
    val socName = DriverRepository.socName()
    /** O que a tabela do Turnip diz deste Snapdragon (GPU, geracao, estado, builds). */
    val turnipGpu = com.dfdx047.dragonburst.drivers.TurnipSupport.detect()
    var driverBootFailed by mutableStateOf(prefs.driverBootFailed)
        private set
    /**
     * O que faltou ao driver da ultima vez que o jogo tentou o Renut (renut_support.txt, escrito pelo jogo:
     * "no" e a lista, p. ex. "shaderInt64"); "" = passou ou ainda nao tentou. Nessa sessao o jogo usou o Xenia.
     */
    val renutMissing: String
        get() = try {
            val lines = java.io.File(com.dfdx047.dragonburst.data.AppPaths.appDir, "renut_support.txt").takeIf { it.isFile }?.readLines().orEmpty()
            if (lines.firstOrNull()?.trim() == "no") lines.getOrNull(1)?.trim().orEmpty().ifEmpty { "?" } else ""
        } catch (e: Exception) {
            ""
        }
    var installedDrivers by mutableStateOf<List<InstalledDriver>>(emptyList())
        private set
    var activeDriver by mutableStateOf<String?>(null)
        private set
    var driverSource by mutableStateOf(DriverRepository.SOURCES.first())
        private set
    var driverReleases by mutableStateOf<List<DriverRelease>>(emptyList())
        private set
    var driverReleasesLoading by mutableStateOf(false)
        private set
    var driverReleasesError by mutableStateOf(false)
        private set
    /**
     * Aviso de primeiro arranque (GPUs e drivers). Mostrado uma vez a todos; tambem se
     * abre pelo Sobre. Substitui o antigo aviso do Turnip (o texto ja fala do Turnip).
     */
    var showGpuNotice by mutableStateOf(!prefs.gpuNoticeShown)
        private set

    fun openGpuNotice() {
        showGpuNotice = true
    }

    fun dismissGpuNotice() {
        showGpuNotice = false
        prefs.gpuNoticeShown = true
        prefs.turnipNoticeShown = true
    }

    fun refreshDrivers() {
        viewModelScope.launch(Dispatchers.IO) {
            installedDrivers = DriverRepository.installed()
            activeDriver = DriverRepository.activeDir()
        }
    }

    fun loadDriverReleases(source: DriverSource = driverSource) {
        driverSource = source
        viewModelScope.launch(Dispatchers.IO) {
            driverReleasesLoading = true
            driverReleasesError = false
            driverReleases = try {
                DriverRepository.fetchReleases(source)
            } catch (e: DriverException) {
                driverReleasesError = true
                emptyList()
            } finally {
                driverReleasesLoading = false
            }
        }
    }

    fun downloadDriver(asset: DriverAsset) {
        viewModelScope.launch(Dispatchers.IO) {
            busy = Busy(R.string.busy_downloading_driver, asset.name)
            try {
                val d = DriverRepository.download(asset) { done, total ->
                    busy = Busy(R.string.busy_downloading_driver, asset.name, if (total > 0) done.toFloat() / total else null)
                }
                say(R.string.msg_driver_installed, d.name)
            } catch (e: DriverException) {
                say(driverError(e), e.message ?: "-")
            } finally {
                busy = null
            }
            refreshDrivers()
        }
    }

    fun importDriver(uri: Uri) {
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val name = displayName(uri)
            busy = Busy(R.string.busy_installing_driver, name)
            try {
                val d = DriverRepository.importUri(ctx, uri, name)
                say(R.string.msg_driver_installed, d.name)
            } catch (e: DriverException) {
                say(driverError(e), e.message ?: "-")
            } finally {
                busy = null
            }
            refreshDrivers()
        }
    }

    private fun driverError(e: DriverException) = when (e.reason) {
        DriverException.Reason.NO_LIBRARY -> R.string.err_driver_no_lib
        DriverException.Reason.NOT_ZIP -> R.string.err_driver_not_zip
        DriverException.Reason.NETWORK -> R.string.err_network
        DriverException.Reason.IO -> R.string.err_driver_io
    }

    fun activateDriver(d: InstalledDriver) {
        viewModelScope.launch(Dispatchers.IO) {
            DriverRepository.activate(d)
            prefs.clearDriverBootState()
            driverBootFailed = ""
            say(R.string.msg_driver_active, d.name)
            refreshDrivers()
        }
    }

    fun useSystemDriver() {
        viewModelScope.launch(Dispatchers.IO) {
            DriverRepository.deactivate()
            prefs.clearDriverBootState()
            driverBootFailed = ""
            say(R.string.msg_driver_system)
            refreshDrivers()
        }
    }

    fun deleteDriver(d: InstalledDriver) {
        viewModelScope.launch(Dispatchers.IO) {
            DriverRepository.delete(d)
            refreshDrivers()
        }
    }

    // ------------------------------------------------------------------ logs
    fun exportLogs(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            busy = Busy(R.string.busy_exporting_logs)
            try {
                val name = com.dfdx047.dragonburst.data.LogExporter.export(context.applicationContext)
                say(R.string.msg_logs_exported, name)
            } catch (e: Exception) {
                say(R.string.err_logs_export, e.message ?: "-")
            } finally {
                busy = null
            }
        }
    }

    // ------------------------------------------------------------ atualizacao
    var updateState by mutableStateOf(UpdateState.IDLE)
        private set
    var updateInfo by mutableStateOf<UpdateInfo?>(null)
        private set
    var showUpdateDialog by mutableStateOf(false)
    private var startupCheckDone = false

    fun checkUpdatesOnLaunch() {
        if (startupCheckDone || !prefs.checkUpdatesOnStart) return
        startupCheckDone = true
        checkUpdates(manual = false)
    }

    fun checkUpdates(manual: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            updateState = UpdateState.CHECKING
            try {
                val info = UpdateChecker.check()
                updateInfo = info
                updateState = if (info == null) UpdateState.UP_TO_DATE else UpdateState.AVAILABLE
                if (info != null && (manual || prefs.skippedUpdate != info.version)) showUpdateDialog = true
                if (info == null && manual) say(R.string.msg_up_to_date)
            } catch (e: Exception) {
                updateState = UpdateState.FAILED
                if (manual) say(R.string.err_update_check)
            }
        }
    }

    fun skipUpdate() {
        prefs.skippedUpdate = updateInfo?.version
        showUpdateDialog = false
    }

    fun downloadUpdate(context: Context) {
        val info = updateInfo ?: return
        if (info.apkUrl == null) {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.pageUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        showUpdateDialog = false
        viewModelScope.launch(Dispatchers.IO) {
            busy = Busy(R.string.busy_downloading_update, info.version)
            try {
                val apk = UpdateChecker.downloadApk(info) { done, total ->
                    busy = Busy(R.string.busy_downloading_update, info.version, if (total > 0) done.toFloat() / total else null)
                }
                withContext(Dispatchers.Main) { UpdateChecker.install(context, apk) }
            } catch (e: Exception) {
                say(R.string.err_network, e.message ?: "-")
            } finally {
                busy = null
            }
        }
    }

    // Fica no fim da classe: as propriedades acima ja tem de estar inicializadas.
    init {
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = GameConfig.load()
            withContext(Dispatchers.Main) { config = loaded }
            // Garante que o toml existe com as opcoes do launcher desde o primeiro arranque.
            GameConfig.save(loaded)
        }
        refreshInstall(false)
        refreshMods()
        refreshTexturePacks()
        refreshSaves()
        refreshDrivers()
    }

    companion object {
        const val PLAY_ACTION = "com.dfdx047.dragonburst.PLAY"
    }
}
