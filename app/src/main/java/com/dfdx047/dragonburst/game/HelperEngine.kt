package com.dfdx047.dragonburst.game

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.dfdx047.dragonburst.controls.ControlAction
import com.dfdx047.dragonburst.controls.MacroStep
import com.dfdx047.dragonburst.controls.PadInput
import org.libsdl.app.DragonBurstPad

/**
 * Junta tudo o que os controles por toque pedem (botoes premidos, analogico,
 * sequencias em curso) num estado de comando e envia so as diferencas para o
 * comando virtual do SDL. Corre a 60 Hz numa thread propria, como o jogo.
 *
 * Os botoes de ajuda que precisam de "para tras"/"para a frente" pedem o lado
 * do P1 ao NativeGameState a cada tick (o seguimento do lado precisa de
 * leituras frequentes para notar quando os lutadores trocam de lado).
 */
class HelperEngine {
    private val thread = HandlerThread("DragonBurstInput").apply { start() }
    private val handler = Handler(thread.looper)
    private val lock = Any()

    /** Entradas premidas diretamente, por elemento (id -> entradas). */
    private val held = HashMap<String, Set<PadInput>>()
    private class Running(val steps: List<MacroStep>, val holdLast: Boolean) {
        var index = 0
        var framesLeft = steps.firstOrNull()?.frames ?: 0
        var released = false
        val done get() = index >= steps.size
    }
    private val sequences = HashMap<String, Running>()
    private var stickX = 0f
    private var stickY = 0f

    /** Quando cada elemento foi premido (uptime, ms), para o toque minimo de [MIN_PRESS_MS]. */
    private val pressedAt = HashMap<String, Long>()

    private val sent = HashSet<PadInput>()
    private var sentLx = 0f
    private var sentLy = 0f
    private var running = false
    @Volatile private var side = NativeGameState.SIDE_UNKNOWN

    /** Menu dos controles aberto por cima do jogo: o jogo fica pausado. */
    @Volatile var menuOpen = false
        set(value) {
            field = value
            handler.post { step() }
        }

    /** Pausar tambem enquanto o menu rapido do port (Back+Start) estiver aberto. */
    @Volatile var pauseQuickMenu = true

    /**
     * Luta ou menus (para trocar o layout dos controles). Chamado na thread principal
     * so quando muda. Com histerese: entra na luta logo que a bandeira do jogo vai a
     * 1 e so volta aos menus depois de [MENU_DELAY_TICKS] ticks seguidos a 0 (entre
     * assaltos, KO e apresentacao a bandeira tambem vai a 0).
     */
    var onBattleChanged: ((Boolean) -> Unit)? = null

    /**
     * Camara livre / modo foto do Burst Limit (layout "mapa": dois analogicos e comando completo).
     * Entra logo e so sai depois de [MAP_DELAY_TICKS] ticks fora do mapa (cenas e dialogos
     * curtos por cima do mapa nao trocam o layout). Chamado na thread principal.
     */
    var onMapChanged: ((Boolean) -> Unit)? = null
    @Volatile var onMap = false
        private set
    private var offMapTicks = 0

    /** A GPU perdeu-se (SDK 0188). Chamado uma vez, na thread principal. */
    var onGpuLost: (() -> Unit)? = null
    private var gpuLostTicks = 0
    private var gpuLostReported = false

    /** Diagnostico (so com "Diagnostico de desempenho"): recebe linhas para logs/controles.log. */
    @Volatile var diag: ((String) -> Unit)? = null
    /** Ficheiro para os blocos dos lutadores (diagnostico do lado), ou null. */
    @Volatile var fightDumpPath: String? = null
    private var diagTicks = 0
    private var diagLast = ""
    @Volatile var inBattle = false
        private set
    private var zeroTicks = 0
    private val mainHandler = Handler(android.os.Looper.getMainLooper())

    /** Se o jogo esta pausado agora (pelo menu dos controles ou pelo menu rapido). */
    @Volatile var paused = false
        private set

    private val tick = object : Runnable {
        override fun run() {
            val start = SystemClock.uptimeMillis()
            step()
            if (running) handler.postAtTime(this, start + 16)
        }
    }

    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
        }
        handler.post(tick)
    }

    fun stop() {
        synchronized(lock) {
            running = false
            held.clear()
            pressedAt.clear()
            sequences.clear()
            stickX = 0f
            stickY = 0f
        }
        handler.removeCallbacks(tick)
        handler.post { flush(emptySet(), 0f, 0f) }
    }

    fun release() {
        stop()
        thread.quitSafely()
    }

    fun press(id: String, action: ControlAction) {
        synchronized(lock) {
            pressedAt[id] = SystemClock.uptimeMillis()
            when (action) {
                is ControlAction.Press -> held[id] = action.inputs
                is ControlAction.Sequence -> if (action.steps.isNotEmpty()) sequences[id] = Running(action.steps, action.holdLast)
            }
        }
        // Resposta imediata para botoes simples (nao espera pelo proximo tick).
        if (action is ControlAction.Press) handler.post { step() }
    }

    /**
     * Solta o elemento, mas so depois de [MIN_PRESS_MS] desde que foi premido: o jogo le o
     * comando uma vez por frame e um toque mais curto que isso perdia-se entre duas leituras.
     */
    fun releaseElement(id: String) {
        val stamp: Long
        val wait: Long
        synchronized(lock) {
            stamp = pressedAt[id] ?: 0L
            wait = if (stamp == 0L) 0L else MIN_PRESS_MS - (SystemClock.uptimeMillis() - stamp)
        }
        if (wait > 0) {
            // Premido outra vez entretanto: essa pressao manda.
            handler.postDelayed({ if (synchronized(lock) { pressedAt[id] } == stamp) releaseNow(id) }, wait)
            return
        }
        releaseNow(id)
    }

    private fun releaseNow(id: String) {
        synchronized(lock) {
            pressedAt.remove(id)
            held.remove(id)
            sequences[id]?.let { seq ->
                seq.released = true
                // Sequencia com o ultimo passo "segurado": soltar o dedo acaba-a.
                if (seq.holdLast && seq.index >= seq.steps.size - 1) sequences.remove(id)
            }
        }
        handler.post { step() }
    }

    fun setStick(x: Float, y: Float) {
        synchronized(lock) {
            stickX = x.coerceIn(-1f, 1f)
            stickY = y.coerceIn(-1f, 1f)
        }
    }

    /** Uma "pressao" da direcao do D-pad do ecra (ou do analogico em modo digital). */
    fun setDpad(id: String, inputs: Set<PadInput>) {
        if (inputs.isEmpty()) {
            releaseElement(id)
            return
        }
        synchronized(lock) {
            if (held[id] == null) pressedAt[id] = SystemClock.uptimeMillis()
            held[id] = inputs
        }
        handler.post { step() }
    }

    private fun step() {
        if (!gpuLostReported && ++gpuLostTicks >= GPU_LOST_CHECK_TICKS) {
            gpuLostTicks = 0
            if (NativeGameState.gpuLost()) {
                gpuLostReported = true
                mainHandler.post { onGpuLost?.invoke() }
            }
        }
        paused = NativeGameState.syncPause(menuOpen, pauseQuickMenu)
        side = NativeGameState.p1Side()
        if (!paused) {
            trackBattle(NativeGameState.fightFlag())
            trackMap(NativeGameState.mapFlag())
        }
        diag?.let { d ->
            if (++diagTicks >= 30) {
                diagTicks = 0
                fightDumpPath?.let { NativeGameState.dumpFight(it, 0) }
                val line = NativeGameState.battleDebug() ?: ""
                if (line != diagLast) {
                    diagLast = line
                    d("$line layout=${if (onMap) "mapa" else if (inBattle) "luta" else "menus"}${if (paused) " (pausa)" else ""}")
                }
            }
        }
        val wanted = HashSet<PadInput>()
        val lx: Float
        val ly: Float
        synchronized(lock) {
            held.values.forEach { wanted.addAll(it) }
            val it = sequences.entries.iterator()
            while (it.hasNext()) {
                val (_, seq) = it.next()
                if (seq.done) {
                    it.remove()
                    continue
                }
                val stepNow = seq.steps[seq.index]
                wanted.addAll(stepNow.inputs)
                val last = seq.index == seq.steps.size - 1
                if (last && seq.holdLast && !seq.released) continue // segura ate soltar
                seq.framesLeft--
                if (seq.framesLeft <= 0) {
                    seq.index++
                    seq.framesLeft = seq.steps.getOrNull(seq.index)?.frames ?: 0
                }
            }
            lx = stickX
            ly = stickY
        }
        flush(resolveRelative(wanted), lx, ly)
    }

    private fun trackBattle(flag: Int) {
        if (flag < 0) return
        val next = when {
            flag == 1 -> {
                zeroTicks = 0
                true
            }
            inBattle && ++zeroTicks < MENU_DELAY_TICKS -> true
            else -> false
        }
        if (next != inBattle) {
            inBattle = next
            android.util.Log.i("DragonBurst", "controles: " + if (next) "luta" else "menus")
            diag?.invoke("troca -> " + (if (next) "luta" else "menus") + " (bandeira=$flag)")
            mainHandler.post { onBattleChanged?.invoke(next) }
        }
    }

    private fun trackMap(flag: Int) {
        if (flag < 0) return
        val next = when {
            flag == 1 -> {
                offMapTicks = 0
                true
            }
            onMap && ++offMapTicks < MAP_DELAY_TICKS -> true
            else -> false
        }
        if (next != onMap) {
            onMap = next
            android.util.Log.i("DragonBurst", "controles: mapa " + if (next) "sim" else "nao")
            diag?.invoke("troca -> " + if (next) "mapa" else "fora do mapa")
            mainHandler.post { onMapChanged?.invoke(next) }
        }
    }

    /** FORWARD/BACKWARD -> RIGHT/LEFT conforme o lado do P1 (esquerda se desconhecido). */
    private fun resolveRelative(inputs: Set<PadInput>): Set<PadInput> {
        if (PadInput.FORWARD !in inputs && PadInput.BACKWARD !in inputs) return inputs
        val p1Right = side == NativeGameState.SIDE_RIGHT
        val out = inputs.toMutableSet()
        if (out.remove(PadInput.BACKWARD)) out += if (p1Right) PadInput.RIGHT else PadInput.LEFT
        if (out.remove(PadInput.FORWARD)) out += if (p1Right) PadInput.LEFT else PadInput.RIGHT
        return out
    }

    private fun flush(wanted: Set<PadInput>, lx: Float, ly: Float) {
        if (!DragonBurstPad.isAttached()) {
            sent.clear()
            return
        }
        for (i in PadInput.entries) {
            val on = i in wanted
            if (on == (i in sent)) continue
            when (i) {
                PadInput.LT -> DragonBurstPad.axis(4, if (on) 1f else -1f)
                PadInput.RT -> DragonBurstPad.axis(5, if (on) 1f else -1f)
                else -> keyFor(i)?.let { DragonBurstPad.button(it, on) }
            }
            if (on) sent += i else sent -= i
        }
        if (lx != sentLx) {
            DragonBurstPad.axis(0, lx)
            sentLx = lx
        }
        if (ly != sentLy) {
            DragonBurstPad.axis(1, ly)
            sentLy = ly
        }
    }

    companion object {
        /** 1,5 s a 60 Hz (pedido do usuario: no maximo ~2 s). */
        const val MENU_DELAY_TICKS = 90
        /** ~1,5 s fora do estado de voo antes de sair do layout do mapa. */
        const val MAP_DELAY_TICKS = 90
        /** Toque minimo (ms): a 30 fps o jogo le o comando a cada 33 ms. */
        const val MIN_PRESS_MS = 80L
        /** De quantos em quantos ticks (~16 ms) ver se a GPU se perdeu. */
        const val GPU_LOST_CHECK_TICKS = 30
    }

    private fun keyFor(i: PadInput): Int? = when (i) {
        PadInput.A -> DragonBurstPad.KEY_A
        PadInput.B -> DragonBurstPad.KEY_B
        PadInput.X -> DragonBurstPad.KEY_X
        PadInput.Y -> DragonBurstPad.KEY_Y
        PadInput.LB -> DragonBurstPad.KEY_LB
        PadInput.RB -> DragonBurstPad.KEY_RB
        PadInput.BACK -> DragonBurstPad.KEY_BACK
        PadInput.START -> DragonBurstPad.KEY_START
        PadInput.L3 -> DragonBurstPad.KEY_L3
        PadInput.R3 -> DragonBurstPad.KEY_R3
        PadInput.UP -> DragonBurstPad.KEY_UP
        PadInput.DOWN -> DragonBurstPad.KEY_DOWN
        PadInput.LEFT -> DragonBurstPad.KEY_LEFT
        PadInput.RIGHT -> DragonBurstPad.KEY_RIGHT
        else -> null
    }
}
