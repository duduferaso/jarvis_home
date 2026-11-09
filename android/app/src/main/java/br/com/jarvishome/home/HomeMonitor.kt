package br.com.jarvishome.home

import android.content.Context
import br.com.jarvishome.calendar.AgendaFormat
import br.com.jarvishome.calendar.CalendarAssistant
import br.com.jarvishome.domain.JLog
import br.com.jarvishome.settings.Prefs
import java.time.LocalDateTime
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Lê o Home Assistant a cada 5 s (estados) e a cada 5 min (agenda), sempre pela rede local.
 * - Atualiza o HomeModel (luzes, clima, alertas, agenda).
 * - Detecta avisos em input_text.jarvis_aviso, entrega ao serviço e limpa o campo.
 * - Avisa os compromissos na hora (lembretes).
 * Sem internet o clima some sozinho; sem rede, a tela marca "sem conexão" e mantém a agenda já lida.
 */
object HomeMonitor {

    /** Chamado (fora da thread principal) com o texto a mostrar e falar. */
    @Volatile var onAnnouncement: ((String) -> Unit)? = null

    private var exec: ScheduledExecutorService? = null
    private var prefs: Prefs? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var agendaDirty = true
    @Volatile private var lastAgendaAt = 0L
    @Volatile private var failures = 0
    private var lastAnnounceText: String? = null
    private var lastAnnounceAt = 0L
    private val remindedKeys = HashSet<String>()

    @Synchronized fun start(context: Context) {
        stop()
        appContext = context.applicationContext
        val p = Prefs(context)
        prefs = p
        if (!p.directMode || !p.isConfigured) {
            HomeModel.clear()
            return
        }
        agendaDirty = true
        CalendarAssistant.onChange = { refreshAgendaSoon() }
        br.com.jarvishome.commands.LightCommands.onChange = { refreshSoon() }
        val e = Executors.newSingleThreadScheduledExecutor()
        exec = e
        e.scheduleWithFixedDelay({ safeTick() }, 0, 5, TimeUnit.SECONDS)
        JLog.i("Monitor da casa iniciado")
    }

    @Synchronized fun stop() {
        exec?.shutdownNow()
        exec = null
    }

    fun refreshSoon() {
        try { exec?.execute { safeTick() } } catch (_: Exception) {}
    }

    fun refreshAgendaSoon() {
        agendaDirty = true
        refreshSoon()
    }

    private fun safeTick() {
        try { tick() } catch (t: Throwable) { JLog.w("Monitor da casa", t) }
    }

    private fun tick() {
        val p = prefs ?: return
        val api = HomeApi(p.serverUrl, p.token)
        val snap = try {
            HomeParser.parse(api.states(), p.serverTempLimit.toDouble(), 90.0, p.serverSensorIds())
        } catch (e: Exception) {
            failures++
            HomeModel.lastError = describe(e)
            if (failures == 1 || failures % 60 == 0) JLog.w("Leitura da casa falhou ($failures)", e)
            if (failures >= 2) HomeModel.setOffline()
            return
        }
        failures = 0
        HomeModel.update(snap)

        val text = snap.announcement
        val entity = snap.announcementEntity
        if (text != null && entity != null) {
            val cleared = try { api.setText(entity, ""); true } catch (e: Exception) {
                JLog.w("Não consegui limpar $entity", e); false
            }
            // Se não deu para limpar, não repete o mesmo aviso a cada leitura.
            val repeated = text == lastAnnounceText && System.currentTimeMillis() - lastAnnounceAt < 10 * 60_000
            if (cleared || !repeated) {
                lastAnnounceText = text
                lastAnnounceAt = System.currentTimeMillis()
                JLog.i("Aviso do Home Assistant: $text")
                onAnnouncement?.invoke(text)
            }
        }

        if (p.calendarEnabled && snap.calendars.isNotEmpty() &&
            (agendaDirty || System.currentTimeMillis() - lastAgendaAt > 5 * 60_000)) {
            try {
                val start = LocalDateTime.now().toLocalDate().atStartOfDay()
                HomeModel.setAgenda(api.events(snap.calendars.map { it.entityId }, start, start.plusDays(15)))
                agendaDirty = false
                lastAgendaAt = System.currentTimeMillis()
            } catch (e: Exception) {
                HomeModel.agendaError = describe(e)
                HomeModel.notifyListeners()
                JLog.w("Agenda indisponível", e)
            }
        }

        if (p.calendarEnabled && p.agendaReminders) checkReminders(p)
        appContext?.let { br.com.jarvishome.update.UpdateFlow.autoCheck(it) }
    }

    /** Texto curto para mostrar ao usuário. */
    fun describe(e: Throwable): String = when (e) {
        is HomeApi.HttpError -> when (e.code) {
            401 -> "token recusado (HTTP 401)"
            403 -> "acesso negado (HTTP 403)"
            404 -> "endereço não encontrado (HTTP 404)"
            else -> e.message ?: "HTTP ${e.code}"
        }
        is java.net.SocketTimeoutException -> "o Home Assistant demorou para responder"
        is java.net.ConnectException, is java.net.NoRouteToHostException -> "não alcancei o Home Assistant na rede"
        is java.net.UnknownHostException -> "endereço desconhecido"
        is org.json.JSONException -> "resposta inesperada do Home Assistant"
        else -> (e.javaClass.simpleName + ": " + (e.message ?: "")).take(120)
    }

    /** Diagnóstico passo a passo da agenda, para a tela de configurações. */
    fun diagnose(p: Prefs): String {
        val sb = StringBuilder()
        val api = HomeApi(p.serverUrl, p.token)
        sb.append("Endereço: ").append(p.serverUrl).append('\n')
        val states = try {
            api.states().also { sb.append("✓ Li o Home Assistant (").append(it.length()).append(" entidades)\n") }
        } catch (e: Exception) {
            JLog.w("Diagnóstico: leitura falhou", e)
            sb.append("✗ Não consegui ler o Home Assistant: ").append(describe(e)).append('\n')
            return sb.toString()
        }
        val snap = try { HomeParser.parse(states) } catch (e: Exception) {
            JLog.w("Diagnóstico: leitura dos estados falhou", e)
            sb.append("✗ Erro ao interpretar os estados: ").append(describe(e)).append('\n')
            return sb.toString()
        }
        if (snap.calendars.isEmpty()) {
            sb.append("✗ Nenhuma agenda (calendar.*) no Home Assistant.\n")
            sb.append("   → Adicione a integração “Calendário local” com o nome “Casa”.\n")
        } else {
            for (c in snap.calendars) {
                sb.append("✓ Agenda ").append(c.name).append(" (").append(c.entityId).append(")")
                    .append(if (c.canCreate) " · aceita marcar" else " · só leitura")
                    .append(if (c.canDelete) " e desmarcar" else "").append('\n')
            }
            val start = LocalDateTime.now().toLocalDate().atStartOfDay()
            for (c in snap.calendars) {
                try {
                    val n = api.events(listOf(c.entityId), start, start.plusDays(15)).size
                    sb.append("✓ ").append(c.name).append(": ").append(n).append(" compromissos nos próximos 15 dias\n")
                } catch (e: Exception) {
                    sb.append("✗ ").append(c.name).append(": ").append(describe(e)).append('\n')
                }
            }
            if (snap.calendars.none { it.canCreate }) sb.append("   → Nenhuma aceita marcar: adicione o Calendário local.\n")
        }
        sb.append(if (snap.hasAnnouncementEntity) "✓ Auxiliar de avisos encontrado\n" else "• Auxiliar input_text.jarvis_aviso ainda não criado (avisos)\n")
        sb.append("Monitor: ").append(if (exec != null) "rodando" else "parado").append(" · ")
            .append(HomeModel.lastError?.let { "último erro: $it" } ?: "sem erros")
        return sb.toString()
    }

    /** Fala "Lembrete: …" quando um compromisso com hora começa (ou X minutos antes). */
    private fun checkReminders(p: Prefs) {
        val now = LocalDateTime.now()
        for (e in HomeModel.agenda) {
            if (e.allDay) continue
            val at = e.start.minusMinutes(p.reminderLeadMin.toLong())
            // janela de 2 minutos: não fala lembretes antigos ao ligar o aparelho
            if (now.isBefore(at) || now.isAfter(at.plusMinutes(2))) continue
            val key = e.calendar + "|" + e.uid + "|" + e.start
            synchronized(remindedKeys) {
                if (!remindedKeys.add(key)) return@synchronized
                if (remindedKeys.size > 200) remindedKeys.clear()
                val txt = if (p.reminderLeadMin == 0) "Lembrete: ${AgendaFormat.spoken(e.title)}."
                    else "Daqui a ${p.reminderLeadMin} minutos: ${AgendaFormat.spoken(e.title)}, " +
                        AgendaFormat.timePhrase(e.start.toLocalTime()) + "."
                JLog.i("Lembrete: ${e.title}")
                onAnnouncement?.invoke(txt)
            }
        }
    }
}
