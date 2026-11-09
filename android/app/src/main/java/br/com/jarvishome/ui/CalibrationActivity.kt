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
import br.com.jarvishome.wakeword.CalibrationAnalyzer
import br.com.jarvishome.wakeword.SherpaWakeWord
import br.com.jarvishome.wakeword.WakeWords
import java.util.Locale

/**
 * Calibra a palavra de ativação com a voz real do usuário, no próprio celular:
 *  1) grava a palavra 8 vezes;  2) grava 15 s de fala normal sem a palavra;
 *  3) testa dezenas de grafias em várias sensibilidades e salva a melhor combinação.
 */
class CalibrationActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var titleView: TextView
    private lateinit var instruction: TextView
    private lateinit var status: TextView
    private lateinit var level: ProgressBar
    private lateinit var primary: Button
    private lateinit var secondary: Button
    @Volatile private var cancelled = false
    private var worker: Thread? = null
    private var tone: ToneGenerator? = null
    private var result: CalibrationAnalyzer.Result? = null

    private val cyan = Color.rgb(34, 229, 255)

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
        val word = WakeWords.current(prefs).display
        titleView = TextView(this).apply {
            text = "CALIBRAR “" + word.uppercase(Locale("pt", "BR")) + "”"
            setTextColor(cyan); textSize = 14f; letterSpacing = 0.2f; typeface = Typeface.DEFAULT_BOLD
        }
        instruction = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 24f; gravity = Gravity.CENTER
            setPadding(0, dp(40), 0, dp(16))
        }
        status = TextView(this).apply {
            setTextColor(Color.argb(190, 210, 235, 240)); textSize = 15f; gravity = Gravity.CENTER
        }
        level = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        primary = Button(this).apply { isAllCaps = false; textSize = 16f }
        secondary = Button(this).apply { isAllCaps = false; text = "Cancelar"; setOnClickListener { finish() } }
        root.addView(titleView)
        root.addView(instruction)
        root.addView(status)
        root.addView(level, LinearLayout.LayoutParams(-1, dp(16)).apply { topMargin = dp(28) })
        root.addView(primary, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(40) })
        root.addView(secondary, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        setContentView(root)
        showIntro(word)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun showIntro(word: String) {
        instruction.text = "Vamos ensinar o Jarvis\na reconhecer a sua voz"
        status.text = "1. Você vai dizer “$word” 8 vezes, quando ouvir o bipe. Fale do jeito " +
            "natural, da distância em que costuma falar com ele.\n\n" +
            "2. Depois vai falar normalmente por 15 segundos, SEM dizer “$word” " +
            "(pode ler qualquer coisa em voz alta ou deixar a TV ligada).\n\nLeva cerca de 1 minuto."
        primary.text = "Começar com “$word”"
        primary.isEnabled = true
        primary.setOnClickListener { start() }
        secondary.text = "Trocar palavra (" + WakeWords.ALL.joinToString(", ") { it.display } + ")"
        secondary.setOnClickListener { chooseWord() }
    }

    private fun chooseWord() {
        val all = WakeWords.ALL
        val labels = all.map { it.display + "  —  " + it.note }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Palavra de ativação")
            .setItems(labels) { _, which ->
                WakeWords.select(prefs, all[which])
                val w = all[which].display
                titleView.text = "CALIBRAR “" + w.uppercase(Locale("pt", "BR")) + "”"
                showIntro(w)
            }
            .show()
    }

    private fun ui(block: () -> Unit) = runOnUiThread { if (!isFinishing) block() }

    private fun start() {
        primary.isEnabled = false
        JarvisForegroundService.send(this, JarvisForegroundService.ACTION_PAUSE_MIC)
        cancelled = false
        worker = Thread({ runCalibration() }, "jarvis-calibration").also { it.start() }
    }

    private fun record(ms: Int): FloatArray? {
        val total = 16000 * ms / 1000
        val out = FloatArray(total)
        val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 32000))
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return null
        }
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
        if (energy == 0.0) return null // microfone mudo
        return out
    }

    private fun beep() {
        try {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        } catch (_: Exception) {}
    }

    private fun runCalibration() {
        try {
            Thread.sleep(800) // o serviço libera o microfone
            val word = WakeWords.current(prefs)
            val positives = ArrayList<FloatArray>()
            for (k in 1..TAKES) {
                if (cancelled) return
                ui {
                    instruction.text = "Diga “" + word.display + "”"
                    status.text = "$k de $TAKES. Fale logo depois do bipe."
                }
                Thread.sleep(700)
                beep()
                Thread.sleep(150)
                val a = record(2600) ?: return fail("O microfone não captou som. Feche outros apps que usam o microfone e tente de novo.")
                positives.add(a)
                ui { status.text = "$k de $TAKES ✓" }
                Thread.sleep(500)
            }
            if (cancelled) return
            ui {
                instruction.text = "Agora fale normalmente\nSEM dizer “" + word.display + "”"
                status.text = "Leia qualquer coisa em voz alta, converse ou deixe a TV ligada."
            }
            Thread.sleep(1200)
            beep()
            val negatives = ArrayList<FloatArray>()
            for (part in 1..3) {
                if (cancelled) return
                val left = (3 - part + 1) * 5
                ui { status.text = "Continue falando… ${left}s" }
                negatives.add(record(5000) ?: return fail("O microfone não captou som."))
            }
            beep()
            ui {
                instruction.text = "Analisando sua voz…"
                status.text = "Testando variações. Cada variação é testada sozinha. Leva de 1 a 3 minutos."
                level.progress = 0
            }
            // Carrega o modelo uma vez; cada teste só troca as palavras do fluxo.
            val spotter = SherpaWakeWord.createSpotter(this, word.defaultLines,
                Prefs.thresholdFor(8), CalibrationAnalyzer.BOOST, "calibracao.txt")
            val res = try {
                CalibrationAnalyzer.analyze(positives, negatives, word.candidates,
                    { kw, a -> !cancelled && SherpaWakeWord.detectAny(spotter, kw, a) },
                    { done, total -> ui {
                        level.progress = done * 100 / total
                        if (done < total) status.text = "Testando variação " + (done + 1) + " de " + (total - 1) + "…"
                        else status.text = "Conferindo as melhores juntas…"
                    } })
            } finally {
                spotter.release()
            }
            if (cancelled) return
            showResult(res, word.display)
        } catch (e: Throwable) {
            JLog.e("Calibração falhou", e)
            fail("A calibração falhou: " + (e.message ?: e.javaClass.simpleName))
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

    private fun showResult(res: CalibrationAnalyzer.Result?, word: String) {
        result = res
        ui {
            level.progress = 100
            if (res == null || res.coverage == 0) {
                instruction.text = "Não reconheci “$word”\nna sua voz"
                status.text = "Tente de novo falando um pouco mais perto e mais devagar. " +
                    "Se continuar assim, tente outra palavra (Alexa foi a melhor nos testes). " +
                    "O toque no rosto sempre funciona."
                primary.text = "Repetir"
                primary.isEnabled = true
                primary.setOnClickListener { start() }
                secondary.text = "Tentar com outra palavra"
                secondary.setOnClickListener { chooseWord() }
                return@ui
            }
            val pct = res.coverage * 100 / res.total
            instruction.text = "Reconheceu ${res.coverage} de ${res.total}"
            val quality = when {
                pct >= 85 -> "Ótimo."
                pct >= 60 -> "Bom. Repetir a calibração em outro horário pode melhorar."
                else -> "Fraco. Vale testar outra palavra (Alexa foi a melhor nos testes)."
            }
            status.text = quality + "\n\nTestei " + res.testedVariants + " variações, uma por vez; " +
                res.usefulVariants + " reconheceram sua voz sem disparar na conversa. Fiquei com as " +
                res.keywordLines.size + " melhores (" + res.keywordLines.joinToString { CalibrationAnalyzer.labelOf(it) } +
                "), conferidas juntas."
            primary.text = "Salvar e usar"
            primary.isEnabled = true
            primary.setOnClickListener { save() }
            if (pct < 60 && WakeWords.current(prefs).id != WakeWords.ALEXA.id) {
                secondary.text = "Tentar com outra palavra"
                secondary.setOnClickListener { chooseWord() }
            } else {
                secondary.text = "Descartar"
                secondary.setOnClickListener { finish() }
            }
        }
    }

    private fun save() {
        val res = result ?: return
        prefs.keywords = res.keywordLines.joinToString("\n") // cada linha leva o próprio limiar
        prefs.keywordBoost = CalibrationAnalyzer.BOOST
        prefs.wakeWordEnabled = true
        prefs.calibratedWord = WakeWords.current(prefs).id
        JLog.i("Calibração salva: ${res.coverage}/${res.total}; " + res.keywordLines.joinToString(" | "))
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
        private const val TAKES = 8
    }
}
