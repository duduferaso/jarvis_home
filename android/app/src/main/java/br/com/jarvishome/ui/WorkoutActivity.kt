package br.com.jarvishome.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import br.com.jarvishome.commands.Workout
import br.com.jarvishome.commands.Workout.Phase
import br.com.jarvishome.settings.Prefs

/**
 * Treino intervalado: configurar (tempo total, execução, descanso, exercícios) e acompanhar
 * em letras grandes (fase, contagem, exercício atual e o próximo, rodada e tempo que falta).
 */
class WorkoutActivity : Activity() {

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var setup: ScrollView
    private lateinit var run: LinearLayout

    private lateinit var phaseView: TextView
    private lateinit var countView: TextView
    private lateinit var exerciseView: TextView
    private lateinit var exerciseInfo: TextView
    private lateinit var nextView: TextView
    private lateinit var roundView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var pauseBtn: Button
    private lateinit var skipBtn: Button

    private var totalMin = 30
    private var workSec = 30
    private var restSec = 30
    private var count = 5
    private var series = 5
    private lateinit var exercisesField: EditText

    private val cyan = Color.rgb(34, 229, 255)
    private val green = Color.rgb(57, 255, 136)
    private val amber = Color.rgb(255, 176, 32)
    private val faint = Color.argb(170, 200, 230, 235)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        totalMin = prefs.workoutMin; workSec = prefs.workoutWork; restSec = prefs.workoutRest; count = prefs.workoutCount; series = prefs.workoutSeries
        root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(6, 8, 10)) }
        setup = buildSetup()
        run = buildRun()
        root.addView(setup, FrameLayout.LayoutParams(-1, -1))
        root.addView(run, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        textSize = size; setTextColor(color); gravity = Gravity.CENTER
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    // ------------------------------------------------------------------ configurar

    private fun slider(box: LinearLayout, label: String, min: Int, max: Int, step: Int, value: Int, fmt: (Int) -> String, set: (Int) -> Unit) {
        val title = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f; setPadding(0, dp(18), 0, dp(4)) }
        fun show(v: Int) { title.text = label + ": " + fmt(v) }
        show(value)
        box.addView(title)
        box.addView(SeekBar(this).apply {
            this.max = (max - min) / step
            progress = (value - min) / step
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { val v = min + p * step; set(v); show(v); updateSummary() }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        })
    }

    private lateinit var summary: TextView

    private fun updateSummary() {
        if (!::summary.isInitialized) return
        val p = currentPlan()
        summary.text = "${p.perSeries} exercícios × ${p.seriesCount} " + (if (p.seriesCount == 1) "série" else "séries") +
            " = ${p.rounds} rodadas de ${p.workSec}s + ${p.restSec}s\nTermina em ${Workout.mmss(p.totalSec * 1000L)}" +
            (if (p.exercises.isNotEmpty() && p.exercises.size != count) "  (usando os ${p.exercises.size} nomes da lista)" else "")
    }

    private fun currentPlan() = Workout.Plan(totalMin, workSec, restSec,
        if (::exercisesField.isInitialized) exercisesField.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() } else emptyList(),
        count, series)

    private fun buildSetup(): ScrollView {
        val sv = ScrollView(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(26), dp(22), dp(40)) }
        sv.addView(box)
        box.addView(TextView(this).apply {
            text = "TREINO"; setTextColor(cyan); textSize = 16f; letterSpacing = 0.25f; typeface = Typeface.DEFAULT_BOLD
        })
        slider(box, "Quantidade de exercícios", 1, 15, 1, count, { "$it" }) { count = it }
        slider(box, "Séries", 1, 15, 1, series, { "$it" }) { series = it }
        slider(box, "Execução", 10, 90, 5, workSec, { "$it s" }) { workSec = it }
        slider(box, "Descanso", 0, 90, 5, restSec, { "$it s" }) { restSec = it }
        box.addView(TextView(this).apply {
            text = "Nomes dos exercícios (opcional, um por linha)"; setTextColor(Color.WHITE); textSize = 16f; setPadding(0, dp(20), 0, dp(4))
        })
        exercisesField = EditText(this).apply {
            setText(prefs.workoutExercises)
            hint = "Agachamento\nFlexão\nPrancha\nPolichinelo"
            setTextColor(Color.WHITE); setHintTextColor(Color.argb(90, 255, 255, 255))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 4; gravity = Gravity.TOP or Gravity.START
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { updateSummary() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        box.addView(exercisesField)
        box.addView(TextView(this).apply {
            setTextColor(faint); textSize = 12f
            text = "Vazio = só o número (“exercício 3 de 5”). Com nomes, a quantidade vem da lista."
        })
        summary = TextView(this).apply { setTextColor(cyan); textSize = 15f; setPadding(0, dp(18), 0, dp(6)) }
        box.addView(summary)
        updateSummary()
        box.addView(Button(this).apply {
            text = "Começar"; isAllCaps = false; textSize = 20f
            setOnClickListener {
                save()
                Workout.start(prefs.workoutPlan())
                refresh()
            }
        }, LinearLayout.LayoutParams(-1, dp(64)).apply { topMargin = dp(8) })
        box.addView(TextView(this).apply {
            setTextColor(faint); textSize = 13f; setPadding(0, dp(20), 0, 0)
            text = "Por voz: “Hey Tony, treino de 5 exercícios e 5 séries” (25 min com 30 s + 30 s), " +
                "“treino de 30 minutos com 6 exercícios” ou “começa o treino” (usa o que está salvo aqui).\n" +
                "Durante o treino: “Hey Tony, pausa”, “continua”, “próximo”, “em que exercício eu estou?”, “quanto falta?”, “para o treino”."
        })
        return sv
    }

    private fun save() {
        prefs.workoutMin = totalMin
        prefs.workoutWork = workSec
        prefs.workoutRest = restSec
        prefs.workoutCount = count
        prefs.workoutSeries = series
        prefs.workoutExercises = exercisesField.text.toString().trim()
    }

    // ------------------------------------------------------------------ em andamento

    private fun buildRun(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(30), dp(20), dp(24)); visibility = View.GONE
        }
        phaseView = text(30f, green, true).apply { letterSpacing = 0.15f }
        countView = text(120f, Color.WHITE, true).apply { includeFontPadding = false }
        exerciseView = text(40f, Color.WHITE, true)
        exerciseInfo = text(16f, faint)
        nextView = text(20f, faint).apply { setPadding(0, dp(18), 0, 0) }
        roundView = text(18f, cyan).apply { setPadding(0, dp(22), 0, dp(6)) }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        box.addView(phaseView)
        box.addView(countView)
        box.addView(exerciseView)
        box.addView(exerciseInfo)
        box.addView(nextView)
        box.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        box.addView(roundView)
        box.addView(progress, LinearLayout.LayoutParams(-1, dp(10)))
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(18), 0, 0) }
        pauseBtn = Button(this).apply { isAllCaps = false; textSize = 18f; setOnClickListener { if (Workout.paused) Workout.resume() else Workout.pause(); refresh() } }
        skipBtn = Button(this).apply { text = "Pular"; isAllCaps = false; textSize = 18f; setOnClickListener { Workout.skip(); refresh() } }
        val stopBtn = Button(this).apply {
            text = "Encerrar"; isAllCaps = false; textSize = 18f
            setOnClickListener {
                if (Workout.state()?.phase == Phase.DONE) { Workout.stop(); refresh(); return@setOnClickListener }
                android.app.AlertDialog.Builder(this@WorkoutActivity).setMessage("Encerrar o treino?")
                    .setPositiveButton("Encerrar") { _, _ -> Workout.stop(); refresh() }
                    .setNegativeButton("Continuar", null).show()
            }
        }
        bar.addView(pauseBtn, LinearLayout.LayoutParams(0, dp(60), 1f))
        bar.addView(skipBtn, LinearLayout.LayoutParams(0, dp(60), 1f))
        bar.addView(stopBtn, LinearLayout.LayoutParams(0, dp(60), 1f))
        box.addView(bar)
        return box
    }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 200)
        }
    }

    private fun refresh() {
        val s = Workout.state()
        if (s == null) {
            setup.visibility = View.VISIBLE; run.visibility = View.GONE
            root.setBackgroundColor(Color.rgb(6, 8, 10))
            return
        }
        setup.visibility = View.GONE; run.visibility = View.VISIBLE
        val (bg, color, label) = when (s.phase) {
            Phase.PREP -> Triple(Color.rgb(40, 30, 6), amber, "PREPARE-SE")
            Phase.WORK -> Triple(Color.rgb(8, 48, 24), green, "EXECUÇÃO")
            Phase.REST -> Triple(Color.rgb(8, 26, 52), cyan, "DESCANSO")
            Phase.DONE -> Triple(Color.rgb(6, 8, 10), cyan, "TREINO CONCLUÍDO")
        }
        root.setBackgroundColor(bg)
        phaseView.setTextColor(color)
        phaseView.text = if (s.paused) "PAUSADO" else label
        countView.text = if (s.phase == Phase.DONE) "✓" else ((s.phaseLeftMs + 999) / 1000).toString()
        countView.setTextColor(if (s.paused) faint else Color.WHITE)
        when (s.phase) {
            Phase.PREP -> {
                exerciseView.text = s.exercise.ifEmpty { if (s.exerciseCount > 0) "Exercício 1 de ${s.exerciseCount}" else "Rodada 1" }
                exerciseInfo.text = if (s.laps > 0) "Série 1 de ${s.laps}" else ""
                nextView.text = ""
            }
            Phase.WORK -> {
                exerciseView.text = when {
                    s.exercise.isNotEmpty() -> s.exercise
                    s.exerciseCount > 0 -> "Exercício ${s.exerciseIndex} de ${s.exerciseCount}"
                    else -> "Rodada ${s.round}"
                }
                exerciseInfo.text = (if (s.exercise.isNotEmpty()) "exercício ${s.exerciseIndex} de ${s.exerciseCount} · " else "") +
                    (if (s.laps > 0) "Série ${s.lap} de ${s.laps}" else "")
                nextView.text = if (s.round == s.rounds) "Último exercício!" else ""
            }
            Phase.REST -> {
                val nextIdx = if (s.exerciseCount > 0) s.exerciseIndex % s.exerciseCount + 1 else 0
                val nextLap = if (s.exerciseCount > 0 && s.exerciseIndex == s.exerciseCount) s.lap + 1 else s.lap
                exerciseView.text = when {
                    s.round >= s.rounds -> ""
                    s.next.isNotEmpty() -> s.next
                    s.exerciseCount > 0 -> "Exercício $nextIdx de ${s.exerciseCount}"
                    else -> "Rodada ${s.round + 1}"
                }
                exerciseInfo.text = if (s.round >= s.rounds) "" else "próximo" + (if (s.laps > 0) " · Série $nextLap de ${s.laps}" else "")
                nextView.text = if (s.exerciseCount > 0 && s.exerciseIndex == s.exerciseCount) "Fim da série ${s.lap}" else ""
            }
            Phase.DONE -> {
                exerciseView.text = "Mandou bem!"
                exerciseInfo.text = if (s.laps > 0) "${s.exerciseCount} exercícios × ${s.laps} séries" else "${s.rounds} rodadas"
                nextView.text = ""
            }
        }
        roundView.text = if (s.phase == Phase.DONE) "" else "Faltam ${Workout.mmss(s.totalLeftMs)} de treino"
        val total = Workout.plan?.let { (Workout.PREP_SEC + it.totalSec) * 1000L } ?: 1L
        progress.progress = (1000 - s.totalLeftMs * 1000 / total).toInt().coerceIn(0, 1000)
        pauseBtn.text = if (s.paused) "Continuar" else "Pausar"
        pauseBtn.isEnabled = s.phase != Phase.DONE
        skipBtn.isEnabled = s.phase != Phase.DONE
    }
}
