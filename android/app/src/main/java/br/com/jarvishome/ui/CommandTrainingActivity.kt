package br.com.jarvishome.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import br.com.jarvishome.audio.VoiceActivityDetector
import br.com.jarvishome.commands.LightCommands
import br.com.jarvishome.domain.JLog
import br.com.jarvishome.home.HomeApi
import br.com.jarvishome.network.HaDirectBackend
import br.com.jarvishome.network.TextCorrector
import br.com.jarvishome.service.JarvisForegroundService
import br.com.jarvishome.settings.Prefs
import kotlin.math.log10

/**
 * Treino dos comandos de luz: cada pessoa fala o comando do jeito dela; a gravação vai para o
 * Whisper do Home Assistant e o app guarda COMO ele transcreveu essa voz. Depois, quando o
 * Whisper escrever aquilo de novo (mesmo errado, ex. "lisa luz do zoquart"), o app sabe o comando.
 */
class CommandTrainingActivity : Activity() {

    private class Cmd(val on: Boolean, val light: LightCommands.Light?) {
        val target get() = light?.entityId ?: LightCommands.ALL
        val label get() = LightCommands.label(on, light)
        /** Frase sugerida, do jeito que se fala. */
        val say: String get() {
            val verb = if (on) "liga" else "desliga"
            val l = light ?: return "$verb todas as luzes"
            val n = l.name.lowercase()
            return if (TextCorrector.normalize(n).startsWith("luz")) "$verb a $n" else "$verb $n"
        }
    }

    private lateinit var prefs: Prefs
    private lateinit var instruction: TextView
    private lateinit var status: TextView
    private lateinit var level: ProgressBar
    private lateinit var list: LinearLayout
    private lateinit var guided: Button
    private lateinit var stop: Button
    private var lights: List<LightCommands.Light> = emptyList()
    private var cmds: List<Cmd> = emptyList()
    private var trained = ArrayList<LightCommands.Trained>()
    @Volatile private var busy = false
    @Volatile private var cancelled = false
    private var worker: Thread? = null
    private var tone: ToneGenerator? = null
    private var micPaused = false

    private val cyan = Color.rgb(34, 229, 255)
    private val faint = Color.argb(170, 200, 230, 235)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        trained = ArrayList(LightCommands.decode(prefs.trainedCommands))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(6, 8, 10))
            setPadding(dp(20), dp(24), dp(20), dp(12))
        }
        root.addView(TextView(this).apply {
            text = "TREINAR COMANDOS DE LUZ"; setTextColor(cyan); textSize = 15f; letterSpacing = 0.2f
            typeface = Typeface.DEFAULT_BOLD
        })
        instruction = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 22f; gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(6))
            text = "Toque em Gravar e fale o comando"
        }
        status = TextView(this).apply {
            setTextColor(faint); textSize = 14f; gravity = Gravity.CENTER
            text = "Fale do jeito de sempre, na distância de sempre (pode dizer “Hey Tony” antes ou não). " +
                "O app manda para o Home Assistant, vê como ele entendeu a SUA voz e guarda. " +
                "Grave 2 ou 3 vezes cada comando, você e sua esposa."
        }
        level = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(instruction)
        root.addView(status)
        root.addView(level, LinearLayout.LayoutParams(-1, dp(12)).apply { topMargin = dp(10) })
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        guided = Button(this).apply { text = "Treino guiado (todos, 2×)"; isAllCaps = false; setOnClickListener { startGuided() } }
        stop = Button(this).apply { text = "Parar"; isAllCaps = false; isEnabled = false; setOnClickListener { cancelled = true } }
        bar.addView(guided, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(stop, LinearLayout.LayoutParams(-2, -2))
        root.addView(bar)
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(24)) }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(Button(this).apply {
            text = "Apagar todo o treino de comandos"; isAllCaps = false
            setOnClickListener {
                AlertDialog.Builder(this@CommandTrainingActivity).setMessage("Apagar todos os exemplos gravados?")
                    .setPositiveButton("Apagar") { _, _ -> trained.clear(); save(); render() }
                    .setNegativeButton("Cancelar", null).show()
            }
        })
        setContentView(root)
        loadLights()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun ui(block: () -> Unit) = runOnUiThread { if (!isFinishing) block() }

    private fun loadLights() {
        instruction.text = "Lendo as luzes da casa…"
        Thread {
            try {
                val l = LightCommands.lightsFrom(HomeApi(prefs.serverUrl, prefs.token).states())
                    .sortedBy { TextCorrector.normalize(it.name) }
                ui {
                    lights = l
                    cmds = l.flatMap { listOf(Cmd(true, it), Cmd(false, it)) } + listOf(Cmd(true, null), Cmd(false, null))
                    instruction.text = if (l.isEmpty()) "Nenhuma luz no Home Assistant" else "Toque em Gravar e fale o comando"
                    render()
                }
            } catch (e: Exception) {
                JLog.w("Treino de comandos: não li as luzes", e)
                ui { instruction.text = "Não consegui ler o Home Assistant"; status.text = e.message ?: "" }
            }
        }.start()
    }

    private fun examples(c: Cmd) = trained.filter { it.on == c.on && it.target == c.target }

    private fun render() {
        list.removeAllViews()
        for (c in cmds) {
            val ex = examples(c)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                setOnLongClickListener {
                    if (ex.isEmpty()) return@setOnLongClickListener true
                    AlertDialog.Builder(this@CommandTrainingActivity).setMessage("Apagar os ${ex.size} exemplos de “${c.label}”?")
                        .setPositiveButton("Apagar") { _, _ -> trained.removeAll(ex.toSet()); save(); render() }
                        .setNegativeButton("Cancelar", null).show()
                    true
                }
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply { text = c.label; setTextColor(Color.WHITE); textSize = 16f })
            texts.addView(TextView(this).apply {
                setTextColor(if (ex.isEmpty()) faint else cyan); textSize = 12f
                text = if (ex.isEmpty()) "sem exemplos · diga: “${c.say}”"
                    else "${ex.size} exemplo" + (if (ex.size > 1) "s" else "") + ": " + ex.takeLast(3).joinToString(" · ") { "“${it.heard}”" }
            })
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Button(this).apply {
                text = "Gravar"; isAllCaps = false; isEnabled = !busy
                setOnClickListener { startRun(listOf(c), 1) }
            })
            list.addView(row)
        }
        if (cmds.isNotEmpty()) list.addView(TextView(this).apply {
            setTextColor(faint); textSize = 12f; setPadding(0, dp(12), 0, 0)
            text = "Toque longo num comando para apagar os exemplos dele. Os exemplos valem para as duas vozes: " +
                "quando o Home Assistant escrever de novo o que ouviu no treino, o app já sabe o que fazer."
        })
    }

    private fun save() {
        prefs.trainedCommands = LightCommands.encode(trained)
    }

    private fun setBusy(b: Boolean) {
        busy = b
        guided.isEnabled = !b
        stop.isEnabled = b
        render()
    }

    private fun startGuided() {
        if (cmds.isEmpty()) return
        startRun(cmds, 2)
    }

    private fun startRun(which: List<Cmd>, times: Int) {
        if (busy) return
        cancelled = false
        setBusy(true)
        if (!micPaused) {
            JarvisForegroundService.send(this, JarvisForegroundService.ACTION_PAUSE_MIC)
            micPaused = true
        }
        worker = Thread({
            try {
                Thread.sleep(700) // o serviço libera o microfone
                loop@ for (c in which) for (k in 1..times) {
                    if (cancelled) break@loop
                    takeOne(c, if (times > 1) " ($k de $times)" else "")
                }
            } catch (_: InterruptedException) {
            } catch (e: Throwable) {
                JLog.e("Treino de comandos falhou", e)
                ui { status.text = "Falhou: " + (e.message ?: e.javaClass.simpleName) }
            } finally {
                ui { if (cancelled) instruction.text = "Treino parado"; setBusy(false); level.progress = 0 }
            }
        }, "jarvis-cmd-training").also { it.start() }
    }

    /** Grava, transcreve e guarda um exemplo. */
    private fun takeOne(c: Cmd, part: String) {
        var tries = 0
        while (!cancelled) {
            ui { instruction.text = "Diga: “${c.say}”"; status.text = c.label + part + ". Fale logo depois do bipe." }
            Thread.sleep(600)
            beep()
            Thread.sleep(150)
            val audio = record() ?: run { ui { status.text = "O microfone não captou som." }; return }
            ui { status.text = "Vendo como o Home Assistant entendeu…"; level.progress = 0 }
            val heard = try {
                HaDirectBackend(prefs.serverUrl, prefs.token, prefs.pipelineId).transcribeAudio(toPcm(audio))
            } catch (e: Exception) {
                JLog.w("Treino de comandos: transcrição falhou", e)
                ui { status.text = "O Home Assistant não respondeu: " + (e.message ?: "") }
                Thread.sleep(2500)
                return
            }
            val text = TextCorrector.stripWakeWord(heard, listOf("Hey Tony", "Tony", "Toni", "Tonie")).trim().trimEnd('.', '!', '?')
            if (LightCommands.words(text).isEmpty()) {
                if (++tries >= 3) return
                ui { status.text = "Não ouvi nada. Vamos repetir." }
                Thread.sleep(1500)
                continue
            }
            val before = LightCommands.resolve(text, lights, trained)
            val entry = LightCommands.Trained(text, c.on, c.target)
            val already = trained.any { it == entry }
            if (!already) trained.add(entry)
            val after = LightCommands.resolve(text, lights, trained)
            val okNow = after != null && after.on == c.on &&
                (if (c.light == null) after.all else !after.all && after.lights.map { it.entityId } == listOf(c.target))
            val msg = when {
                !okNow -> {
                    if (!already) trained.remove(entry)
                    val other = trained.firstOrNull { LightCommands.words(it.heard) == LightCommands.words(text) && (it.on != c.on || it.target != c.target) }
                    "⚠ Ouvi “$text”, igual a outro comando" + (other?.let { " (" + labelOf(it) + ")" } ?: "") +
                        ". Não guardei: fale o começo do comando mais forte e devagar."
                }
                before != null && sameAs(before, c) -> "✓ Ouvi “$text”. Já entendia; guardei mais esse exemplo."
                else -> "✓ Ouvi “$text”. Aprendido!"
            }
            JLog.i("Treino de comandos: ${c.label} → “$heard” ($msg)")
            save()
            ui { status.text = msg; render() }
            Thread.sleep(1800)
            return
        }
    }

    private fun sameAs(a: LightCommands.Action, c: Cmd) = a.on == c.on &&
        (if (c.light == null) a.all else !a.all && a.lights.map { it.entityId } == listOf(c.target))

    private fun labelOf(t: LightCommands.Trained) =
        LightCommands.label(t.on, if (t.target == LightCommands.ALL) null else lights.firstOrNull { it.entityId == t.target })

    private fun beep() {
        try {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        } catch (_: Exception) {}
    }

    /** Grava até a pessoa parar de falar (máx. 5 s). */
    private fun record(): FloatArray? {
        val max = 16000 * 5
        val out = FloatArray(max)
        val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 32000))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return null }
        val buf = ShortArray(1600) // 100 ms
        var n = 0
        var floor = 0.0
        var chunks = 0
        var spoke = false
        var quiet = 0
        var energy = 0.0
        try {
            rec.startRecording()
            while (n < max && !cancelled) {
                val r = rec.read(buf, 0, minOf(buf.size, max - n))
                if (r <= 0) break
                var s = 0.0
                for (i in 0 until r) { val v = buf[i] / 32768f; out[n + i] = v; s += v * v }
                n += r
                val rms = VoiceActivityDetector.rms(buf, r)
                energy += rms
                ui { level.progress = (VoiceActivityDetector.level(rms) * 100).toInt() }
                val db = 10 * log10(s / r + 1e-12)
                chunks++
                if (chunks <= 2) { floor = if (chunks == 1) db else minOf(floor, db); continue }
                if (db > floor + 12) { spoke = true; quiet = 0 }
                else if (spoke && db < floor + 8) quiet++
                if (spoke && quiet >= 9) break // 0,9 s de silêncio depois da fala
                if (!spoke && n > 16000 * 3) break // ninguém falou em 3 s
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
        return if (energy == 0.0) null else out.copyOf(n)
    }

    private fun toPcm(a: FloatArray): ByteArray {
        val b = ByteArray(a.size * 2)
        for (i in a.indices) {
            val v = (a[i].coerceIn(-1f, 1f) * 32767).toInt()
            b[2 * i] = (v and 0xff).toByte()
            b[2 * i + 1] = ((v shr 8) and 0xff).toByte()
        }
        return b
    }

    override fun onDestroy() {
        cancelled = true
        worker?.interrupt()
        try { tone?.release() } catch (_: Exception) {}
        if (micPaused) JarvisForegroundService.send(this, JarvisForegroundService.ACTION_RESUME_MIC)
        super.onDestroy()
    }
}
