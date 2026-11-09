package br.com.jarvishome.ui

import android.app.Activity
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
import android.widget.TextView
import br.com.jarvishome.audio.VoiceActivityDetector
import br.com.jarvishome.domain.JLog
import br.com.jarvishome.service.JarvisForegroundService
import br.com.jarvishome.settings.Prefs
import br.com.jarvishome.wakeword.PersonalStore
import br.com.jarvishome.wakeword.PersonalTrainer
import br.com.jarvishome.wakeword.TemplateSet
import br.com.jarvishome.wakeword.VoiceFeatures
import java.util.Locale
import kotlin.math.log10

/**
 * Treina o detector pessoal com a voz de quem mora na casa:
 *  1) grava a chamada 6 vezes (cada pessoa pode repetir o treino para somar a própria voz);
 *  2) grava 15 s de fala normal, sem a chamada;
 *  3) escolhe o limiar que reconhece as chamadas sem disparar na fala normal.
 */
class VoiceTrainingActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var titleView: TextView
    private lateinit var instruction: TextView
    private lateinit var status: TextView
    private lateinit var level: ProgressBar
    private lateinit var primary: Button
    private lateinit var secondary: Button
    private lateinit var third: Button
    @Volatile private var cancelled = false
    private var worker: Thread? = null
    private var tone: ToneGenerator? = null
    private var pending: TemplateSet? = null
    private var keepExisting = true

    private val cyan = Color.rgb(34, 229, 255)
    private val phrase = PersonalStore.PHRASE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(28), dp(48), dp(28), dp(28))
        }
        titleView = TextView(this).apply {
            text = "TREINAR “" + phrase.uppercase(Locale("pt", "BR")) + "”"
            setTextColor(cyan); textSize = 14f; letterSpacing = 0.2f; typeface = Typeface.DEFAULT_BOLD
        }
        instruction = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 24f; gravity = Gravity.CENTER
            setPadding(0, dp(36), 0, dp(16))
        }
        status = TextView(this).apply {
            setTextColor(Color.argb(190, 210, 235, 240)); textSize = 15f; gravity = Gravity.CENTER
        }
        level = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        primary = Button(this).apply { isAllCaps = false; textSize = 16f }
        secondary = Button(this).apply { isAllCaps = false }
        third = Button(this).apply { isAllCaps = false; text = "Cancelar"; setOnClickListener { finish() } }
        root.addView(titleView)
        root.addView(instruction)
        root.addView(status)
        root.addView(level, LinearLayout.LayoutParams(-1, dp(16)).apply { topMargin = dp(28) })
        root.addView(primary, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(36) })
        root.addView(secondary, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        root.addView(third, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        setContentView(root)
        showIntro()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun ui(block: () -> Unit) = runOnUiThread { if (!isFinishing) block() }

    private fun showIntro() {
        val current = PersonalStore.load(this).templates.size
        val custom = PersonalStore.hasCustom(this)
        instruction.text = "Ensine o Jarvis a\nreconhecer a sua chamada"
        status.text = "Hoje: $current exemplos" + (if (custom) " treinados aqui." else " da gravação que vocês fizeram pela casa.") +
            "\n\n1. Diga “$phrase” 6 vezes, depois do bipe, do jeito que você chama no dia a dia.\n" +
            "2. Fale normalmente por 15 segundos SEM dizer “$phrase” (ou deixe a TV ligada).\n\n" +
            "Cada pessoa da casa pode fazer o treino: os exemplos se somam. Leva cerca de 1 minuto."
        primary.text = "Começar (somar à voz atual)"
        primary.isEnabled = true
        primary.setOnClickListener { keepExisting = true; start() }
        secondary.text = "Começar do zero (apagar exemplos)"
        secondary.isEnabled = true
        secondary.setOnClickListener { keepExisting = false; start() }
    }

    private fun start() {
        primary.isEnabled = false
        secondary.isEnabled = false
        JarvisForegroundService.send(this, JarvisForegroundService.ACTION_PAUSE_MIC)
        cancelled = false
        worker = Thread({ runTraining() }, "jarvis-voice-training").also { it.start() }
    }

    private fun beep() {
        try {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        } catch (_: Exception) {}
    }

    /** Grava [ms] milissegundos. Devolve null se o microfone não captar nada. */
    private fun record(ms: Int): FloatArray? {
        val total = 16000 * ms / 1000
        val out = FloatArray(total)
        val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 32000))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return null }
        val buf = ShortArray(1600)
        var n = 0
        var energy = 0.0
        try {
            rec.startRecording()
            while (n < total && !cancelled) {
                val r = rec.read(buf, 0, minOf(buf.size, total - n))
                if (r <= 0) break
                for (i in 0 until r) out[n + i] = buf[i] / 32768f
                n += r
                val rms = VoiceActivityDetector.rms(buf, r)
                energy += rms
                val lv = (VoiceActivityDetector.level(rms) * 100).toInt()
                ui { level.progress = lv }
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
        return if (energy == 0.0) null else out
    }

    /** Pico de energia (dBFS) em janelas de 100 ms: para saber se houve fala na gravação. */
    private fun peakDb(a: FloatArray): Double {
        var best = -120.0
        var i = 0
        while (i + 1600 <= a.size) {
            var s = 0.0
            for (k in i until i + 1600) s += a[k] * a[k]
            best = maxOf(best, 10 * log10(s / 1600 + 1e-12))
            i += 1600
        }
        return best
    }

    private fun runTraining() {
        try {
            Thread.sleep(800) // o serviço libera o microfone
            val takes = ArrayList<FloatArray>()
            var k = 1
            var retries = 0
            while (k <= TAKES) {
                if (cancelled) return
                ui {
                    instruction.text = "Diga “$phrase”"
                    status.text = "$k de $TAKES. Fale logo depois do bipe."
                }
                Thread.sleep(700)
                beep()
                Thread.sleep(150)
                val a = record(2400) ?: return fail("O microfone não captou som. Feche outros apps que usam o microfone.")
                if (peakDb(a) < -58.0 && retries < 3) {
                    retries++
                    ui { status.text = "Não ouvi nada. Vamos repetir esta." }
                    Thread.sleep(1200)
                    continue
                }
                takes.add(a)
                ui { status.text = "$k de $TAKES ✓" }
                Thread.sleep(400)
                k++
            }
            if (cancelled) return
            ui {
                instruction.text = "Agora fale normalmente\nSEM dizer “$phrase”"
                status.text = "Converse, leia algo em voz alta ou deixe a TV ligada."
            }
            Thread.sleep(1200)
            beep()
            val speech = ArrayList<FloatArray>()
            for (part in 1..3) {
                if (cancelled) return
                val left = (3 - part + 1) * 5
                ui { status.text = "Continue falando… ${left}s" }
                speech.add(record(5000) ?: return fail("O microfone não captou som."))
            }
            beep()
            ui {
                instruction.text = "Aprendendo a sua voz…"
                status.text = "Comparando as gravações."
                level.progress = 0
            }
            val set = if (keepExisting) PersonalStore.load(this) else TemplateSet(ArrayList(), TemplateSet.DEFAULT_THRESHOLD)
            val firstNew = set.templates.size
            for (t in takes) set.templates.add(VoiceFeatures.compute(t).frames)
            val newIdx = (firstNew until set.templates.size).toList()
            // fala normal: janelas de 2,2 s a cada 1,1 s (igual ao que o detector analisa)
            val negatives = ArrayList<Array<FloatArray>>()
            val all = speech.fold(FloatArray(0)) { acc, a -> acc + a }
            var st = 0
            while (st + 35200 <= all.size) {
                negatives.add(VoiceFeatures.compute(all.copyOfRange(st, st + 35200)).frames)
                st += 17600
            }
            ui { level.progress = 60 }
            val rep = PersonalTrainer.choose(set, newIdx, negatives)
            set.threshold = rep.threshold
            pending = set
            JLog.i("Treino de voz: ${rep.recognized}/${rep.total}, falsos ${rep.falseHits}, limiar " +
                String.format(Locale.US, "%.3f", rep.threshold) + " (pior chamada " +
                String.format(Locale.US, "%.3f", rep.worstPositive) + ", fala mais parecida " +
                String.format(Locale.US, "%.3f", rep.bestNegative) + ")")
            showResult(rep, set.templates.size)
        } catch (e: Throwable) {
            JLog.e("Treino de voz falhou", e)
            fail("O treino falhou: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun fail(msg: String) {
        ui {
            instruction.text = "Não deu certo"
            status.text = msg
            primary.text = "Tentar de novo"
            primary.isEnabled = true
            primary.setOnClickListener { start() }
        }
    }

    private fun showResult(rep: PersonalTrainer.Report, total: Int) {
        ui {
            level.progress = 100
            instruction.text = "Reconheceu ${rep.recognized} de ${rep.total}"
            val note = when {
                rep.falseHits > 0 -> "Atenção: a fala normal ficou parecida demais com a chamada. " +
                    "Ajustei para não disparar sozinho, mas talvez precise chamar mais de uma vez. " +
                    "Tente treinar de novo falando a chamada sempre do mesmo jeito."
                rep.recognized == rep.total -> "Ótimo. Nenhum disparo na fala normal."
                rep.recognized * 10 >= rep.total * 7 -> "Bom. Nenhum disparo na fala normal."
                else -> "Fraco: as suas chamadas variaram muito entre si. Treine de novo falando sempre igual."
            }
            status.text = note + "\n\nExemplos guardados: $total · limiar " +
                String.format(Locale.US, "%.3f", rep.threshold) +
                "\n(quanto menor a distância, mais parecido; a fala normal ficou a " +
                String.format(Locale.US, "%.3f", rep.bestNegative) + ")"
            primary.text = "Salvar e usar"
            primary.isEnabled = true
            primary.setOnClickListener { save() }
            secondary.text = "Repetir o treino"
            secondary.isEnabled = true
            secondary.setOnClickListener { showIntro() }
            third.text = "Descartar"
        }
    }

    private fun save() {
        val set = pending ?: return
        PersonalStore.save(this, set)
        prefs.personalDetector = true
        prefs.wakeWordEnabled = true
        JLog.i("Exemplos de voz salvos: ${set.templates.size}")
        finish()
    }

    override fun onDestroy() {
        cancelled = true
        worker?.interrupt()
        try { tone?.release() } catch (_: Exception) {}
        JarvisForegroundService.send(this, JarvisForegroundService.ACTION_RESUME_MIC)
        JarvisForegroundService.send(this, JarvisForegroundService.ACTION_RELOAD)
        super.onDestroy()
    }

    companion object {
        private const val TAKES = 6
    }
}
