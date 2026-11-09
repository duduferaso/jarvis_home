package br.com.jarvishome.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import br.com.jarvishome.calendar.AgendaFormat
import br.com.jarvishome.commands.Timers
import br.com.jarvishome.commands.Workout.Phase as P
import br.com.jarvishome.domain.AssistantState
import br.com.jarvishome.domain.ConnectionState
import br.com.jarvishome.domain.JLog
import br.com.jarvishome.domain.StateBus
import br.com.jarvishome.home.HomeApi
import br.com.jarvishome.home.HomeModel
import br.com.jarvishome.home.HomeMonitor
import br.com.jarvishome.home.HomeParser
import br.com.jarvishome.home.Night
import br.com.jarvishome.service.JarvisForegroundService
import br.com.jarvishome.settings.Prefs
import br.com.jarvishome.settings.Provisioning
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : Activity(), StateBus.Listener, HomeModel.Listener {

    private lateinit var prefs: Prefs
    private lateinit var face: CharacterView
    private lateinit var titleView: TextView
    private lateinit var stateLabel: TextView
    private lateinit var hint: TextView
    private lateinit var transcript: TextView
    private lateinit var clock: TextView
    private lateinit var dateView: TextView
    private lateinit var weatherView: TextView
    private lateinit var connDot: View
    private lateinit var connLabel: TextView
    private lateinit var banner: TextView
    private lateinit var timerView: TextView
    private lateinit var infoBox: LinearLayout
    private lateinit var alertView: TextView
    private lateinit var agendaBox: LinearLayout
    private lateinit var agendaLines: LinearLayout
    private lateinit var lightsScroll: HorizontalScrollView
    private lateinit var lightsRow: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private val clockFmt = SimpleDateFormat("HH:mm", Locale("pt", "BR"))
    private val pt = Locale("pt", "BR")
    private val dateFmt = DateTimeFormatter.ofPattern("d 'de' MMMM", pt)

    private var awakeUntil = 0L
    private var nightDim = false
    private var swallowGesture = false
    private var lastState = AssistantState.IDLE

    private val cyan = Color.rgb(34, 229, 255)
    private val amber = Color.rgb(255, 176, 32)
    private val dim = Color.argb(150, 34, 229, 255)
    private val faint = Color.argb(120, 180, 235, 245)
    private val soft = Color.argb(200, 220, 240, 245)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        br.com.jarvishome.domain.CrashLog.install(this)
        Provisioning.apply(this)
        prefs = Prefs(this)
        window.decorView.setBackgroundColor(Color.BLACK)
        setContentView(buildLayout())
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(size: Float, color: Int, light: Boolean = false) = TextView(this).apply {
        textSize = size
        setTextColor(color)
        if (light) typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }

    private fun buildLayout(): View {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        face = CharacterView(this)
        face.setOnClickListener { JarvisForegroundService.send(this, JarvisForegroundService.ACTION_LISTEN) }
        face.setOnLongClickListener {
            JarvisForegroundService.send(this, JarvisForegroundService.ACTION_TOGGLE_SLEEP)
            true
        }
        root.addView(face, FrameLayout.LayoutParams(-1, -1))

        // ---------------- topo: nome, conexão, relógio, data, clima, aviso
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(26), dp(22), 0)
        }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleView = text(13f, dim).apply {
            text = "JARVIS HOME"
            letterSpacing = 0.32f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        row1.addView(titleView)
        connDot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.GRAY) }
        }
        row1.addView(connDot, LinearLayout.LayoutParams(dp(7), dp(7)).apply { leftMargin = dp(12) })
        connLabel = text(11f, faint)
        row1.addView(connLabel, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })
        clock = text(34f, cyan, light = true)
        row1.addView(clock)
        top.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        dateView = text(14f, faint).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        row2.addView(dateView, LinearLayout.LayoutParams(0, -2, 1f))
        weatherView = text(14f, soft).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.END }
        row2.addView(weatherView, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
        top.addView(row2, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })

        banner = text(16f, Color.WHITE).apply {
            visibility = View.GONE
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.argb(40, 34, 229, 255))
                setStroke(dp(1), cyan)
            }
            setOnClickListener { HomeModel.dismissAnnouncement() }
        }
        top.addView(banner, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        timerView = text(22f, cyan).apply {
            visibility = View.GONE
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.argb(30, 34, 229, 255))
            }
            setOnClickListener { Timers.stopRinging() }
        }
        top.addView(timerView, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // ---------------- base: informação (em espera) + estado da conversa
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), 0, dp(22), dp(54))
        }
        infoBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        alertView = text(14f, amber).apply {
            visibility = View.GONE
            setPadding(0, 0, 0, dp(10))
        }
        infoBox.addView(alertView)

        agendaBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.argb(18, 34, 229, 255))
                setStroke(dp(1), Color.argb(60, 34, 229, 255))
            }
            setOnClickListener { startActivity(Intent(this@MainActivity, AgendaActivity::class.java)) }
        }
        agendaBox.addView(text(11f, dim).apply { text = "AGENDA"; letterSpacing = 0.25f })
        agendaLines = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        agendaBox.addView(agendaLines)
        infoBox.addView(agendaBox, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        lightsScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        lightsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        lightsScroll.addView(lightsRow)
        infoBox.addView(lightsScroll, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        bottom.addView(infoBox)

        stateLabel = text(12f, cyan).apply { letterSpacing = 0.25f; gravity = Gravity.CENTER }
        transcript = text(17f, Color.argb(210, 220, 245, 250)).apply {
            gravity = Gravity.CENTER; maxLines = 3; setPadding(0, dp(8), 0, 0)
        }
        hint = text(13f, faint).apply { gravity = Gravity.CENTER; maxLines = 3; setPadding(0, dp(6), 0, 0) }
        bottom.addView(stateLabel)
        bottom.addView(transcript)
        bottom.addView(hint)
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        val gear = TextView(this).apply {
            text = "⚙"
            textSize = 20f
            setTextColor(Color.argb(110, 34, 229, 255))
            setPadding(dp(18), dp(14), dp(18), dp(14))
            contentDescription = "Configurações"
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        root.addView(gear, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END))
        return root
    }

    // ---------------------------------------------------------------- ciclo de vida

    override fun onResume() {
        super.onResume()
        prefs = Prefs(this)
        face.reduceMotion = prefs.reduceMotion
        awakeUntil = System.currentTimeMillis() + 60_000
        StateBus.addListener(this)
        HomeModel.addListener(this)
        handler.post(tick)
        handler.post(timerTick)
        applyWindowPrefs()
        ensurePermissionsThenStart()
        HomeMonitor.refreshSoon()
    }

    override fun onPause() {
        StateBus.removeListener(this)
        HomeModel.removeListener(this)
        handler.removeCallbacks(tick)
        handler.removeCallbacks(timerTick)
        super.onPause()
    }

    @Deprecated("Terminal dedicado: o botão voltar não fecha a tela principal")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** No modo noite, o primeiro toque só acende a tela (não aciona o Jarvis sem querer). */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            if (Timers.isRinging()) { // qualquer toque para o alarme
                Timers.stopRinging()
                swallowGesture = true
                refreshTimers()
                return true
            }
            if (nightDim) {
                wake(60_000)
                swallowGesture = true
                return true
            }
            awakeUntil = maxOf(awakeUntil, System.currentTimeMillis() + 60_000)
        }
        if (swallowGesture) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) swallowGesture = false
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun wake(ms: Long) {
        awakeUntil = System.currentTimeMillis() + ms
        refreshNight()
    }

    /** Contagem regressiva dos timers (atualiza a cada meio segundo). */
    private val timerTick = object : Runnable {
        override fun run() {
            refreshTimers()
            handler.postDelayed(this, 500)
        }
    }

    private fun refreshTimers() {
        val now = System.currentTimeMillis()
        val ringing = Timers.ringing()
        val list = Timers.all().filter { it.ringingSince == 0L }
        val w = br.com.jarvishome.commands.Workout.state(now)
        when {
            w != null && ringing.isEmpty() -> {
                timerView.visibility = View.VISIBLE
                timerView.textSize = 26f
                timerView.setTextColor(when { w.paused -> Color.WHITE; w.phase == P.WORK -> Color.rgb(57, 255, 136); else -> cyan })
                val where = if (w.exerciseCount > 0) "Exercício ${w.exerciseIndex}/${w.exerciseCount} · Série ${w.lap}/${w.laps}" else "Rodada ${w.round}/${w.rounds}"
                timerView.text = when (w.phase) {
                    P.PREP -> "Treino começa em ${(w.phaseLeftMs + 999) / 1000}"
                    P.DONE -> "Treino concluído ✓"
                    else -> (if (w.paused) "PAUSADO" else if (w.phase == P.WORK) "EXECUÇÃO" else "DESCANSO") +
                        " ${(w.phaseLeftMs + 999) / 1000}s\n$where · faltam ${br.com.jarvishome.commands.Workout.mmss(w.totalLeftMs)}"
                }
            }
            ringing.isNotEmpty() -> {
                timerView.visibility = View.VISIBLE
                timerView.textSize = 22f
                val blink = (now / 500) % 2 == 0L
                timerView.setTextColor(if (blink) amber else Color.WHITE)
                timerView.text = "⏰ " + Timers.finishedPhrase(ringing[0]).removeSuffix(".") + "  ·  toque para parar"
                if (nightDim) wake(60_000)
            }
            list.isNotEmpty() -> {
                timerView.visibility = View.VISIBLE
                timerView.textSize = 22f
                timerView.setTextColor(if (nightDim) Color.argb(110, 34, 229, 255) else cyan)
                timerView.text = list.take(3).joinToString("   ") { i ->
                    if (i.kind == Timers.Kind.ALARM) "⏰ " + java.text.SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(Date(i.endsAt))
                    else "⏱ " + Timers.clock(maxOf(0L, (i.endsAt - now + 999) / 1000)) + (if (i.label.isNotEmpty()) " " + i.label else "")
                } + if (list.size > 3) "  +${list.size - 3}" else ""
            }
            else -> timerView.visibility = View.GONE
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            clock.text = clockFmt.format(Date())
            refreshNight()
            refreshHome()
            handler.postDelayed(this, 10_000)
        }
    }

    // ---------------------------------------------------------------- janela

    private fun applyWindowPrefs() {
        if (prefs.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        clock.visibility = if (prefs.showClock) View.VISIBLE else View.INVISIBLE
        setBrightness(force = true)
        hideSystemBars()
    }

    private var lastBrightness = -2f
    private fun setBrightness(force: Boolean = false) {
        val b = when {
            nightDim -> 0.01f
            prefs.dimScreen -> 0.04f
            else -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        if (!force && b == lastBrightness) return
        lastBrightness = b
        val lp = window.attributes
        lp.screenBrightness = b
        window.attributes = lp
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    // ---------------------------------------------------------------- modo noite

    private fun quietState() = StateBus.state == AssistantState.IDLE || StateBus.state == AssistantState.SLEEPING

    private fun refreshNight() {
        val night = Night.isNight(prefs) && System.currentTimeMillis() > awakeUntil && quietState()
        if (night != nightDim) {
            nightDim = night
            JLog.i(if (night) "Modo noite: tela no mínimo" else "Tela acesa")
            refreshHome()
        }
        setBrightness()
        val c = if (nightDim) Color.argb(110, 34, 229, 255) else cyan
        clock.setTextColor(c)
    }

    // ---------------------------------------------------------------- permissões

    private fun ensurePermissionsThenStart() {
        val needed = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) {
            hint.text = "O Jarvis precisa do microfone para ouvir a chamada. " +
                "A detecção acontece no aparelho; nada é enviado antes de você chamar."
            requestPermissions(needed.toTypedArray(), REQ_PERMS)
            return
        }
        JarvisForegroundService.send(this, JarvisForegroundService.ACTION_START)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            JarvisForegroundService.send(this, JarvisForegroundService.ACTION_START)
        } else {
            StateBus.setState(AssistantState.ERROR,
                "Sem permissão de microfone. Libere em Configurações do Android > Apps > Jarvis Home.")
        }
    }

    // ---------------------------------------------------------------- estado do assistente

    override fun onStateChanged() {
        val st = StateBus.state
        if (st != lastState) {
            lastState = st
            if (!quietState()) wake(60_000)
        }
        stateLabel.text = st.label.uppercase(pt)
        stateLabel.setTextColor(if (st == AssistantState.ERROR) amber else cyan)
        val t = StateBus.transcript
        transcript.text = if (t.isNotEmpty() && !quietState()) "“$t”" else ""
        hint.text = StateBus.message
        val c = StateBus.connection
        val dotColor = when (c) {
            ConnectionState.CONNECTED -> cyan
            ConnectionState.UNKNOWN -> Color.GRAY
            ConnectionState.HA_DOWN, ConnectionState.NOT_CONFIGURED -> amber
            else -> Color.rgb(255, 80, 80)
        }
        (connDot.background as GradientDrawable).setColor(dotColor)
        connLabel.text = if (c == ConnectionState.CONNECTED) "" else c.label
        refreshHome()
    }

    // ---------------------------------------------------------------- casa

    override fun onHomeChanged() {
        val a = HomeModel.announcement
        if (a != null && System.currentTimeMillis() - HomeModel.announcementAt < 5_000) wake(120_000)
        refreshHome()
    }

    /** Um erro ao desenhar o painel não pode fechar o app: fica no log técnico. */
    private fun refreshHome() {
        try { refreshHomeInner() } catch (t: Throwable) { JLog.e("Tela inicial", t) }
    }

    private fun refreshHomeInner() {
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        val quiet = quietState()
        val online = HomeModel.online

        // data e clima (o clima some sozinho sem internet ou se ficar velho)
        // "Quinta, 5 de novembro" (sem "-feira", para caber ao lado do clima)
        dateView.text = AgendaFormat.weekdayName(today).substringBefore("-").replaceFirstChar { it.uppercase() } +
            ", " + today.format(dateFmt)
        val w = HomeModel.weather
        weatherView.text = if (w != null && prefs.showWeather && online)
            (w.temperature?.let { "${it.roundToInt()}° " } ?: "") + w.condition else ""
        dateView.visibility = if (nightDim) View.INVISIBLE else View.VISIBLE
        weatherView.visibility = if (nightDim) View.INVISIBLE else View.VISIBLE

        // aviso (some depois de 10 minutos ou ao tocar)
        val ann = HomeModel.announcement
        val annFresh = ann != null && System.currentTimeMillis() - HomeModel.announcementAt < 10 * 60_000
        banner.visibility = if (annFresh) View.VISIBLE else View.GONE
        if (annFresh) banner.text = "AVISO  ·  $ann"

        // painéis só em espera e fora do modo noite
        val showInfo = quiet && !nightDim
        infoBox.visibility = if (showInfo) View.VISIBLE else View.GONE

        val alerts = if (online) HomeModel.alerts else emptyList()
        alertView.visibility = if (alerts.isNotEmpty()) View.VISIBLE else View.GONE
        alertView.text = "⚠  " + alerts.joinToString("   ·   ")

        // agenda
        val agendaOn = prefs.calendarEnabled && prefs.showAgenda
        agendaBox.visibility = if (agendaOn) View.VISIBLE else View.GONE
        if (agendaOn) {
            agendaLines.removeAllViews()
            val cals = HomeModel.calendars
            val lines = ArrayList<Pair<String, String>>()
            if (cals.isEmpty()) {
                lines.add("" to when {
                    online -> "Nenhuma agenda no Home Assistant · toque para ver como adicionar"
                    HomeModel.lastError != null -> "Sem conexão com a casa: " + HomeModel.lastError
                    else -> "Conectando à casa…"
                })
            } else {
                val next = HomeModel.agenda.filter { it.end.isAfter(now) && it.date.isBefore(today.plusDays(7)) }.take(3)
                if (next.isEmpty()) {
                    lines.add("" to when {
                        HomeModel.agendaLoaded -> "Nada na agenda nos próximos dias"
                        HomeModel.agendaError != null -> "Agenda indisponível: " + HomeModel.agendaError
                        else -> "Carregando…"
                    })
                } else {
                    for (e in next) lines.add(AgendaFormat.screenWhen(e, today) to e.title)
                }
                if (!HomeModel.agendaOnline) lines.add("" to "sem conexão · mostrando o que já estava carregado")
            }
            for ((whenTxt, what) in lines) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
                if (whenTxt.isNotEmpty()) {
                    row.addView(text(14f, cyan).apply { text = whenTxt }, LinearLayout.LayoutParams(dp(128), -2))
                }
                row.addView(text(15f, if (whenTxt.isEmpty()) faint else Color.WHITE).apply {
                    text = what; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, -2, 1f))
                agendaLines.addView(row)
            }
        }

        // luzes
        val lights = if (online && prefs.showLights) HomeModel.lights else emptyList()
        lightsScroll.visibility = if (lights.isNotEmpty()) View.VISIBLE else View.GONE
        if (lights.isNotEmpty()) {
            lightsRow.removeAllViews()
            for (l in lights) lightsRow.addView(chip(l), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        }

        face.compact = showInfo && (agendaOn || lights.isNotEmpty())
    }

    private fun chip(l: HomeParser.Light): TextView = TextView(this).apply {
        text = l.name
        textSize = 15f
        setPadding(dp(14), dp(9), dp(14), dp(9))
        val on = l.on && l.available
        setTextColor(if (on) Color.BLACK else Color.argb(170, 200, 210, 215))
        typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        alpha = if (l.available) 1f else 0.4f
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(if (on) cyan else Color.argb(14, 255, 255, 255))
            setStroke(dp(1), if (on) cyan else Color.argb(70, 200, 210, 215))
        }
        contentDescription = l.name + if (on) ", ligada" else ", desligada"
        setOnClickListener {
            if (!l.available) return@setOnClickListener
            HomeModel.setLightOptimistic(l.entityId, !l.on)
            val p = prefs
            Thread {
                try {
                    HomeApi(p.serverUrl, p.token).toggle(l.entityId)
                } catch (e: Throwable) {
                    JLog.w("Falha ao alternar ${l.entityId}", e)
                    runOnUiThread { Toast.makeText(this@MainActivity, "Não consegui mudar ${l.name}", Toast.LENGTH_SHORT).show() }
                }
                handler.postDelayed({ HomeMonitor.refreshSoon() }, 600)
            }.start()
        }
    }

    companion object {
        private const val REQ_PERMS = 10
    }
}
