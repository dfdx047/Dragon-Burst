package com.dfdx047.dragonburst.game

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.dfdx047.dragonburst.R
import com.dfdx047.dragonburst.controls.ControlAction
import com.dfdx047.dragonburst.controls.ControlElement
import com.dfdx047.dragonburst.controls.ControlLayoutStore
import com.dfdx047.dragonburst.controls.LayoutKind
import com.dfdx047.dragonburst.controls.MacroStep
import com.dfdx047.dragonburst.controls.PadInput
import com.dfdx047.dragonburst.data.AppPaths
import com.dfdx047.dragonburst.data.DarkMode
import com.dfdx047.dragonburst.data.GameConfig
import com.dfdx047.dragonburst.data.LauncherPrefs
import com.dfdx047.dragonburst.data.ThemeMode
import com.dfdx047.dragonburst.data.TouchMode
import com.dfdx047.dragonburst.ui.theme.DragonBurstTheme
import org.libsdl.app.DragonBurstPad

/**
 * Controles por toque dentro do jogo:
 *  - TouchControlsView por cima da superficie do SDL, com dois layouts (luta e
 *    menus) trocados sozinhos conforme o jogo esta numa luta ou nos menus;
 *  - modo Automatico / Ligado / Desligado (no automatico aparecem ao tocar na
 *    tela e somem quando se usa um comando fisico);
 *  - engrenagem (canto de cima a esquerda): menu dos controles e editor;
 *  - pause (em cima, ao centro): opcoes do jogo por toque (ex-menu rapido do PC);
 *  - com qualquer menu aberto o jogo fica pausado.
 *
 * Chamado pelo GameActivity (Java) nos eventos do ciclo de vida e de input.
 */
class GameOverlay(private val activity: Activity) : InputManager.InputDeviceListener, InGameActions {
    private val prefs = LauncherPrefs(activity)
    private val engine = HelperEngine()
    private val main = Handler(Looper.getMainLooper())
    private val startedAt = SystemClock.uptimeMillis()
    private var view: TouchControlsView? = null
    private var gear: ImageView? = null
    private var pauseButton: ImageView? = null
    private var layoutButton: ImageView? = null
    private var compose: ComposeView? = null
    private val owner = OverlayOwner()
    private val screen = mutableStateOf(InGameScreen.CLOSED)
    /** Menu do jogo do Android (AndroidMenu.kt): navegacao e opcoes. */
    private val menuController = MenuController()
    private val menuModel by lazy { MenuModel(activity, prefs, this) }
    private val inputManager = activity.getSystemService(Context.INPUT_SERVICE) as InputManager

    /** Modo automatico: os controles estao a mostra (ultimo input foi toque). */
    private var autoShown = true
    private var inBattle = false
    /** Camara livre / modo foto (tem prioridade sobre a luta e os menus). */
    private var onMap = false

    private val attachRunnable = Runnable { attachPad() }
    private var gpuStats: GpuStatsSampler? = null

    fun onCreate() {
        AppPaths.init(activity)
        if (GameConfig.load().perfLogging) {
            val f = java.io.File(AppPaths.logsDir, "gpu_stats.log")
            f.delete()
            gpuStats = GpuStatsSampler(f)
            val ctl = java.io.File(AppPaths.logsDir, "controles.log")
            ctl.delete()
            val dump = java.io.File(AppPaths.logsDir, "lutadores.bin")
            dump.delete()
            engine.fightDumpPath = dump.absolutePath
            val fmt = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.ROOT)
            engine.diag = { line ->
                try {
                    ctl.appendText(fmt.format(java.util.Date()) + " " + line + "\n")
                } catch (_: Exception) {
                }
            }
        }
        engine.pauseQuickMenu = prefs.pauseOnMenus
        // GPU perdida (SDK 0188): o jogo ja nao desenha; volta ao launcher, que mostra o aviso.
        engine.onGpuLost = {
            android.util.Log.w("DragonBurst", "GPU perdida: volta ao launcher")
            prefs.gpuLostAt = System.currentTimeMillis()
            exitToLauncher()
        }
        engine.onBattleChanged = { battle ->
            inBattle = battle
            view?.let {
                it.releaseAll()
                it.layout = currentLayout()
            }
        }
        engine.onMapChanged = { map ->
            onMap = map
            view?.let {
                it.releaseAll()
                it.layout = currentLayout()
            }
        }
        // O motor corre sempre (pausa, luta/menus), mesmo com os controles desligados.
        main.postDelayed(attachRunnable, 2500)
        val density = activity.resources.displayMetrics.density

        val v = TouchControlsView(activity).apply {
            listener = object : TouchControlsView.Listener {
                override fun onPress(element: ControlElement) {
                    // O botao "menu" (Back + Start) abre o menu do Android.
                    val a = element.action
                    if (a is ControlAction.Press && a.inputs == setOf(PadInput.BACK, PadInput.START)) {
                        openMenuAt(0)
                        return
                    }
                    ensurePad()
                    engine.press(element.id, element.action)
                    // Diagnostico: cada botao de ajuda (macro) fica marcado no log, com o
                    // lado que o app julga e uma copia dos lutadores nesse instante.
                    if (element.action is ControlAction.Sequence) {
                        engine.diag?.invoke("macro ${element.id} lado=${NativeGameState.p1Side()}")
                        engine.fightDumpPath?.let { NativeGameState.dumpFight(it, 1) }
                    }
                }

                override fun onRelease(element: ControlElement) = engine.releaseElement(element.id)

                override fun onStick(x: Float, y: Float) {
                    ensurePad()
                    engine.setStick(x, y)
                }

                override fun onDpad(element: ControlElement, inputs: Set<PadInput>) {
                    ensurePad()
                    engine.setDpad(element.id, inputs)
                }
            }
        }
        view = v
        autoShown = !physicalPadConnected()
        applyAppearance()
        activity.addContentView(
            v,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        // Engrenagem (canto de cima a esquerda): menu dos controles.
        val g = roundButton(R.drawable.ic_btn_menu, R.string.ingame_title) { openMenuAt(menuModel.controlsTab) }
        gear = g
        activity.addContentView(
            g,
            FrameLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt(), Gravity.TOP or Gravity.START).apply {
                leftMargin = (14 * density).toInt()
                topMargin = (10 * density).toInt()
            },
        )
        // Pause (em cima, ao centro): opcoes do jogo.
        val pb = roundButton(R.drawable.ic_btn_pause, R.string.port_menu_title) { openMenuAt(0) }
        pauseButton = pb
        activity.addContentView(
            pb,
            FrameLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt(), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = (10 * density).toInt()
            },
        )

        // Trocar a mao entre os layouts (ao lado da engrenagem): menus -> luta -> camara
        // livre (layout MAP, comando completo) -> menus. A troca automatica seguinte volta
        // a mandar.
        val lb = roundButton(R.drawable.ic_btn_layout, R.string.ingame_switch_layout) {
            when (currentLayoutKind()) {
                LayoutKind.MENU -> {
                    inBattle = true
                    onMap = false
                }
                LayoutKind.GAMEPLAY -> {
                    inBattle = false
                    onMap = true
                }
                LayoutKind.MAP -> {
                    inBattle = false
                    onMap = false
                }
            }
            engine.diag?.invoke("botao de troca -> " + currentLayoutKind().name)
            view?.let {
                it.releaseAll()
                it.layout = currentLayout()
            }
        }
        layoutButton = lb
        activity.addContentView(
            lb,
            FrameLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt(), Gravity.TOP or Gravity.START).apply {
                leftMargin = (64 * density).toInt()
                topMargin = (10 * density).toInt()
            },
        )

        // Menus (Compose), escondidos ate um dos botoes ser tocado.
        val c = ComposeView(activity).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setContent {
                DragonBurstTheme(themeMode = ThemeMode.DRAGON, darkMode = DarkMode.DARK) {
                    InGameOverlay(screen.value, prefs, this@GameOverlay, menuModel, menuController)
                }
            }
            visibility = View.GONE
        }
        activity.addContentView(
            c,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        compose = c

        inputManager.registerInputDeviceListener(this, main)
        updateVisibility()
    }

    private fun roundButton(icon: Int, description: Int, onClick: () -> Unit): ImageView {
        val density = activity.resources.displayMetrics.density
        return ImageView(activity).apply {
            setImageResource(icon)
            setColorFilter(Color.parseColor("#FFD27A"))
            val pad = (9 * density).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(60, 255, 255, 255))
                setStroke((1.5f * density).toInt(), Color.argb(140, 255, 243, 222))
            }
            alpha = prefs.touchOpacity.coerceAtLeast(0.4f)
            contentDescription = activity.getString(description)
            setOnClickListener { onClick() }
        }
    }

    /**
     * Layout em uso. O botao de carregar ki (L3) so aparece com a opcao "Carregar ki"
     * ligada: sem ela o L3 nao faz nada na luta (era um dos botoes "que nao funcionavam").
     */
    private fun currentLayout(): com.dfdx047.dragonburst.controls.ControlLayout {
        val layout = ControlLayoutStore.load(currentLayoutKind())
        if (layout.kind != LayoutKind.GAMEPLAY || kiChargeOn()) return layout
        return layout.copy(
            elements = layout.elements.filterNot { e ->
                e.action is ControlAction.Press && (e.action as ControlAction.Press).inputs == setOf(PadInput.L3)
            },
        )
    }

    private var kiChargeCache: Boolean? = null
    private fun kiChargeOn(): Boolean {
        val live = NativeGameState.getCvar("ki_charge")
        if (live.isNotEmpty()) return live == "true"
        return kiChargeCache ?: GameConfig.load().kiCharge.also { kiChargeCache = it }
    }

    override fun currentLayoutKind(): LayoutKind = when {
        onMap -> LayoutKind.MAP
        inBattle -> LayoutKind.GAMEPLAY
        else -> LayoutKind.MENU
    }

    private fun ensurePad() {
        if (!DragonBurstPad.isAttached() && SystemClock.uptimeMillis() - startedAt > 1500) attachPad()
    }

    private fun attachPad() {
        // Sempre ligado (mesmo sem controles na tela): e por ele que a vibracao do
        // jogo chega ao motor do aparelho quando o comando fisico nao tem motor.
        DragonBurstPad.attach()
        engine.start()
    }

    // ---------------------------------------------------- input (GameActivity)

    /**
     * Toque na tela com os controles escondidos no modo automatico: mostra-os e
     * consome esse toque (devolve true).
     */
    fun onScreenTouch(ev: MotionEvent): Boolean {
        if (ev.actionMasked != MotionEvent.ACTION_DOWN) return false
        if (prefs.touchMode != TouchMode.AUTO || isMenuOpen() || autoShown) return false
        autoShown = true
        ensurePad()
        updateVisibility()
        return true
    }

    /** Botao ou eixo de um comando fisico: no modo automatico esconde os controles da tela. */
    fun onPhysicalInput(event: InputEvent) {
        if (prefs.touchMode != TouchMode.AUTO || !autoShown) return
        val dev = event.device ?: return
        if (dev.isVirtual) return
        val s = event.source
        val pad = s and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            s and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
            s and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD
        if (!pad) return
        // Eixos: so um movimento a serio conta (sticks com drift e gatilhos com ruido
        // mandam eventos sem parar e escondiam os controles logo apos aparecerem).
        if (event is MotionEvent && STICK_AXES.none { kotlin.math.abs(event.getAxisValue(it)) > 0.5f }) return
        if (event is KeyEvent && (event.action != KeyEvent.ACTION_DOWN || event.keyCode == KeyEvent.KEYCODE_BACK)) return
        autoShown = false
        view?.releaseAll()
        updateVisibility()
    }

    // ---------------------------------------------------------------- menus

    fun isMenuOpen(): Boolean = screen.value != InGameScreen.CLOSED

    /** Botao Back com o jogo a correr: abre o menu. */
    fun openMenu() = openMenuAt(0)

    private fun openMenuAt(tab: Int) {
        menuModel.refresh()
        menuController.onClose = { resume() }
        menuController.open(tab)
        setScreen(InGameScreen.MENU)
    }

    /** Teclas Back + Start do comando ao mesmo tempo (com o menu fechado). */
    private val comboDown = HashSet<Int>()

    /**
     * Teclas do comando para o menu (GameActivity.dispatchKeyEvent): com o menu aberto as
     * pressoes sao do menu (as teclas soltas seguem para o jogo, que esta em pausa e assim
     * nao fica com nenhuma presa); com ele fechado, Back + Start abre-o. Devolve true se
     * consumiu o evento.
     */
    fun onMenuKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        if (code == KeyEvent.KEYCODE_BACK) return false
        when (screen.value) {
            InGameScreen.MENU -> {
                if (e.action != KeyEvent.ACTION_DOWN) return false
                return menuController.onKeyDown(code)
            }
            InGameScreen.EDITOR -> return false
            InGameScreen.CLOSED -> Unit
        }
        if (code != KeyEvent.KEYCODE_BUTTON_SELECT && code != KeyEvent.KEYCODE_BUTTON_START) return false
        when (e.action) {
            KeyEvent.ACTION_DOWN -> {
                comboDown += code
                if (comboDown.size == 2) {
                    comboDown.clear()
                    openMenuAt(0)
                    return true
                }
            }
            KeyEvent.ACTION_UP -> comboDown -= code
        }
        return false
    }

    /** Analogico / cruz com o menu aberto. Devolve true se consumiu. */
    fun onMenuMotion(ev: MotionEvent): Boolean {
        if (screen.value != InGameScreen.MENU) return false
        val s = ev.source
        if (s and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return false
        menuController.onMotion(ev)
        return true
    }

    private fun setScreen(s: InGameScreen) {
        screen.value = s
        compose?.visibility = if (s == InGameScreen.CLOSED) View.GONE else View.VISIBLE
        if (s != InGameScreen.CLOSED) {
            view?.releaseAll()
        }
        updateVisibility()
        // Com o menu aberto o jogo fica pausado (GPU + audio), se o usuario quiser.
        engine.menuOpen = s != InGameScreen.CLOSED && prefs.pauseOnMenus
        engine.start()
    }

    override fun resume() {
        menuController.stopRepeat()
        setScreen(InGameScreen.CLOSED)
    }

    /**
     * Volta ao launcher. O jogo corre no seu proprio processo (":game"): o runtime
     * recompilado nao se reinicia dentro do mesmo processo, por isso o processo do
     * jogo termina e o proximo "Jogar" comeca do zero.
     */
    override fun setDisplayRate(hz: Int) {
        (activity as? GameActivity)?.requestDisplayRate(hz)
    }

    override fun captureFrames() {
        try {
            java.io.File(AppPaths.appDir, "capture.req").writeText("2")
        } catch (e: Exception) {
            android.util.Log.w("dbz3", "capture.req", e)
        }
    }

    private var selfTest: RenderSelfTest? = null

    override fun startSelfTest(): Boolean {
        val surface = GameActivity.gameSurface() ?: return false
        if (!inBattle || selfTest != null) return false
        setScreen(InGameScreen.CLOSED)
        selfTest = RenderSelfTest(activity, surface) { selfTest = null }.also { it.start() }
        return true
    }

    override fun exitToLauncher() {
        setScreen(InGameScreen.CLOSED)
        NativeGameState.forceResume()
        activity.startActivity(
            android.content.Intent(activity, com.dfdx047.dragonburst.LauncherActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        activity.finishAndRemoveTask()
        main.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 400)
    }

    override fun openEditor() = setScreen(InGameScreen.EDITOR)

    override fun closeEditor() {
        view?.layout = currentLayout()
        setScreen(InGameScreen.MENU)
    }

    override fun isInBattle(): Boolean = inBattle

    override fun applyAppearance() {
        val v = view ?: return
        v.layout = currentLayout()
        v.style = prefs.controlStyle
        v.directional = prefs.directional
        v.badge = prefs.buttonBadge
        v.globalOpacity = prefs.touchOpacity
        v.haptics = prefs.touchHaptics
        val a = prefs.touchOpacity.coerceAtLeast(0.4f)
        gear?.alpha = a
        pauseButton?.alpha = a
        layoutButton?.alpha = a
        updateVisibility()
    }

    // ------------------------------------------------------------ ciclo de vida

    fun onResume() {
        engine.pauseQuickMenu = prefs.pauseOnMenus
        applyAppearance()
        if (DragonBurstPad.isAttached()) engine.start()
        gpuStats?.start()
    }

    fun onPause() {
        gpuStats?.stop()
        view?.releaseAll()
        engine.stop()
    }

    fun onDestroy() {
        gpuStats?.stop()
        NativeGameState.forceResume()
        main.removeCallbacks(attachRunnable)
        inputManager.unregisterInputDeviceListener(this)
        engine.release()
        DragonBurstPad.detach()
        owner.destroy()
        view = null
    }

    /** Back do Android com um menu aberto: volta um nivel. Devolve true se tratou. */
    fun onBackPressed(): Boolean = when (screen.value) {
        InGameScreen.EDITOR -> {
            closeEditor()
            true
        }
        InGameScreen.MENU -> {
            menuController.close()
            true
        }
        InGameScreen.CLOSED -> false
    }

    private fun physicalPadConnected(): Boolean =
        InputDevice.getDeviceIds().any { id ->
            val d = InputDevice.getDevice(id) ?: return@any false
            !d.isVirtual && (d.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                d.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)
        }

    private fun updateVisibility() {
        val v = view ?: return
        val menuOpen = isMenuOpen()
        val show = !menuOpen && when (prefs.touchMode) {
            TouchMode.ON -> true
            TouchMode.OFF -> false
            TouchMode.AUTO -> autoShown
        }
        if (!show) v.releaseAll()
        v.visibility = if (show) View.VISIBLE else View.GONE
        // Engrenagem e pause ficam sempre (menos com um menu aberto). Teste do
        // compositor: "env:DF_HIDE_BUTTONS = 1" no cvars.txt esconde-os (o Back abre o menu).
        val hideAll = android.system.Os.getenv("DF_HIDE_BUTTONS") == "1"
        val buttons = if (menuOpen || hideAll) View.GONE else View.VISIBLE
        gear?.visibility = buttons
        pauseButton?.visibility = buttons
        // A troca de layout so faz sentido com os controles da tela a mostra.
        layoutButton?.visibility = if (show && !hideAll) View.VISIBLE else View.GONE
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        // Comando ligado agora: no automatico os controles da tela saem.
        if (prefs.touchMode == TouchMode.AUTO && physicalPadConnected()) autoShown = false
        updateVisibility()
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        if (prefs.touchMode == TouchMode.AUTO && !physicalPadConnected()) autoShown = true
        updateVisibility()
    }

    override fun onInputDeviceChanged(deviceId: Int) = updateVisibility()

    private companion object {
        val STICK_AXES = intArrayOf(
            MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
            MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y, MotionEvent.AXIS_LTRIGGER,
            MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS, MotionEvent.AXIS_BRAKE,
        )
    }
}
