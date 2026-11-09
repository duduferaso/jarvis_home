package br.com.jarvishome.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import br.com.jarvishome.calendar.AgendaFormat
import br.com.jarvishome.calendar.CalEvent
import br.com.jarvishome.calendar.CalendarInfo
import br.com.jarvishome.domain.JLog
import br.com.jarvishome.home.HomeApi
import br.com.jarvishome.home.HomeMonitor
import br.com.jarvishome.settings.Prefs
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

/** Agenda dos próximos 14 dias: ver, marcar (+) e desmarcar (toque longo). */
class AgendaActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private var calendars: List<CalendarInfo> = emptyList()

    private val cyan = Color.rgb(34, 229, 255)
    private val faint = Color.argb(160, 200, 230, 235)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.rgb(6, 8, 10))
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(40))
        }
        scroll.addView(root)
        setContentView(scroll)

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "AGENDA"; setTextColor(cyan); textSize = 16f; letterSpacing = 0.25f; typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Button(this).apply { text = "+ Novo"; isAllCaps = false; setOnClickListener { newEvent() } })
        header.addView(Button(this).apply { text = "Atualizar"; isAllCaps = false; setOnClickListener { load() } })
        root.addView(header)
        status = TextView(this).apply { setTextColor(faint); textSize = 13f; setPadding(0, dp(6), 0, dp(6)) }
        root.addView(status)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        root.addView(TextView(this).apply {
            setTextColor(faint); textSize = 12f; setPadding(0, dp(20), 0, 0)
            text = "Por voz: “Hey Tony, marca dentista amanhã às 15h”, “o que eu tenho amanhã?”, " +
                "“desmarca o dentista de amanhã”. Toque longo num compromisso para desmarcar."
        })
        load()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun api() = HomeApi(prefs.serverUrl, prefs.token)

    private fun load() {
        status.text = "Carregando…"
        Thread {
            try {
                val cals = api().calendars()
                val start = LocalDate.now().atStartOfDay()
                val events = if (cals.isEmpty()) emptyList() else api().events(cals.map { it.entityId }, start, start.plusDays(14))
                runOnUiThread { calendars = cals; render(events) }
            } catch (e: Exception) {
                JLog.w("Agenda: falha ao carregar", e)
                runOnUiThread { status.text = "Não consegui ler a agenda: " + HomeMonitor.describe(e) }
            }
        }.start()
    }

    private fun render(events: List<CalEvent>) {
        list.removeAllViews()
        if (calendars.isEmpty()) {
            status.text = ""
            list.addView(TextView(this).apply {
                setTextColor(Color.WHITE); textSize = 15f
                text = "Ainda não há nenhuma agenda no Home Assistant.\n\n" +
                    "Para usar sem internet (recomendado): Configurações > Dispositivos e serviços > " +
                    "Adicionar integração > “Calendário local” (Local Calendar), com o nome “Casa”.\n\n" +
                    "Para usar o Google Agenda: integração “Google Calendar” (precisa de internet para sincronizar)."
            })
            return
        }
        val writable = calendars.filter { it.canCreate }
        status.text = "Agendas: " + calendars.joinToString { it.name + if (it.canCreate) "" else " (só leitura)" } +
            if (writable.isEmpty()) "\nNenhuma aceita compromissos novos: adicione o Calendário local." else ""
        val today = LocalDate.now()
        val now = LocalDateTime.now()
        val upcoming = events.filter { it.end.isAfter(now) }
        if (upcoming.isEmpty()) {
            list.addView(TextView(this).apply { setTextColor(faint); textSize = 15f; text = "Nada marcado nos próximos 14 dias."; setPadding(0, dp(16), 0, 0) })
            return
        }
        for ((day, items) in upcoming.groupBy { it.date }.toSortedMap()) {
            list.addView(TextView(this).apply {
                text = AgendaFormat.dayTitle(day, today); setTextColor(cyan); textSize = 14f
                typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(18), 0, dp(4))
            })
            for (e in items) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(8), 0, dp(8))
                    isLongClickable = true
                    setOnLongClickListener { confirmDelete(e); true }
                }
                row.addView(TextView(this).apply {
                    text = if (e.allDay) "dia todo" else "%02d:%02d".format(e.start.hour, e.start.minute)
                    setTextColor(faint); textSize = 15f
                }, LinearLayout.LayoutParams(dp(80), -2))
                row.addView(TextView(this).apply {
                    text = e.title + (if (calendars.size > 1) "  ·  " + (calendars.firstOrNull { it.entityId == e.calendar }?.name ?: "") else "")
                    setTextColor(Color.WHITE); textSize = 16f
                }, LinearLayout.LayoutParams(0, -2, 1f))
                list.addView(row)
            }
        }
    }

    private fun confirmDelete(e: CalEvent) {
        val cal = calendars.firstOrNull { it.entityId == e.calendar }
        if (cal != null && !cal.canDelete) {
            Toast.makeText(this, "A agenda ${cal.name} não permite desmarcar por aqui.", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setMessage("Desmarcar “${e.title}”?")
            .setPositiveButton("Desmarcar") { _, _ ->
                Thread {
                    try {
                        api().delete(e.calendar, e.uid, e.recurrenceId)
                        runOnUiThread { Toast.makeText(this, "Desmarcado", Toast.LENGTH_SHORT).show(); load() }
                        HomeMonitor.refreshAgendaSoon()
                    } catch (ex: Exception) {
                        JLog.w("Falha ao desmarcar", ex)
                        runOnUiThread { Toast.makeText(this, "Não consegui desmarcar: ${ex.message}", Toast.LENGTH_LONG).show() }
                    }
                }.start()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun newEvent() {
        val writable = calendars.filter { it.canCreate }
        if (writable.isEmpty()) {
            Toast.makeText(this, "Nenhuma agenda aceita compromissos novos. Adicione o Calendário local no Home Assistant.", Toast.LENGTH_LONG).show()
            return
        }
        var date = LocalDate.now()
        var time: LocalTime? = LocalTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0)
        var cal = writable.firstOrNull { it.entityId == prefs.calendarWrite } ?: writable.first()

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val title = EditText(this).apply { hint = "O quê? (ex.: Dentista)"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
        box.addView(title)
        val dateBtn = Button(this).apply { isAllCaps = false }
        val timeBtn = Button(this).apply { isAllCaps = false }
        val allDay = CheckBox(this).apply { text = "Dia inteiro" }
        val calBtn = Button(this).apply { isAllCaps = false; visibility = if (writable.size > 1) View.VISIBLE else View.GONE }
        fun refresh() {
            dateBtn.text = "Dia: " + AgendaFormat.dayTitle(date, LocalDate.now())
            timeBtn.text = "Hora: " + (time?.let { "%02d:%02d".format(it.hour, it.minute) } ?: "—")
            timeBtn.isEnabled = !allDay.isChecked
            calBtn.text = "Agenda: " + cal.name
        }
        dateBtn.setOnClickListener {
            DatePickerDialog(this, { _, y, m, d -> date = LocalDate.of(y, m + 1, d); refresh() },
                date.year, date.monthValue - 1, date.dayOfMonth).show()
        }
        timeBtn.setOnClickListener {
            val t = time ?: LocalTime.of(9, 0)
            TimePickerDialog(this, { _, h, mi -> time = LocalTime.of(h, mi); refresh() }, t.hour, t.minute, true).show()
        }
        allDay.setOnCheckedChangeListener { _, _ -> refresh() }
        calBtn.setOnClickListener {
            AlertDialog.Builder(this).setItems(writable.map { it.name }.toTypedArray()) { _, i -> cal = writable[i]; refresh() }.show()
        }
        box.addView(dateBtn); box.addView(timeBtn); box.addView(allDay); box.addView(calBtn)
        refresh()

        AlertDialog.Builder(this)
            .setTitle("Novo compromisso")
            .setView(box)
            .setPositiveButton("Marcar") { _, _ ->
                val t = title.text.toString().trim().ifEmpty { "Compromisso" }
                val whole = allDay.isChecked || time == null
                val c = cal
                Thread {
                    try {
                        if (whole) api().create(c.entityId, t, null, null, date, date.plusDays(1))
                        else {
                            val s = date.atTime(time!!)
                            api().create(c.entityId, t, s, s.plusHours(1), null, null)
                        }
                        runOnUiThread { Toast.makeText(this, "Marcado", Toast.LENGTH_SHORT).show(); load() }
                        HomeMonitor.refreshAgendaSoon()
                    } catch (ex: Exception) {
                        JLog.w("Falha ao marcar", ex)
                        runOnUiThread { Toast.makeText(this, "Não consegui marcar: ${ex.message}", Toast.LENGTH_LONG).show() }
                    }
                }.start()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
