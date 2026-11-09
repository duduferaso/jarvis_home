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
    // diagnóstico da leitura automática
    @Volatile private var startedAt = 0L
    @Volatile private var tickStart = 0L
    @Volatile private var tickEnd = 0L
    @Volatile private var lastOkAt = 0L
    @Volatile private var ticks = 0
    @Volatile private var oks = 0
    @Volatile private var stage = ""
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
        val e = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "jarvis-casa").apply { isDaemon = true } }
        exec = e
        startedAt = System.currentTimeMillis(); tickStart = 0; tickEnd = 0
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
        tickStart = System.currentTimeMillis()
        ticks++
        try {
            tick()
        } catch (t: Throwable) {
            // qualquer falha aparece na tela e no diagnóstico (antes só ia para o log)
            HomeModel.lastError = "erro interno em \"$stage\": " + describe(t)
            HomeModel.notifyListeners()
            JLog.w("Monitor da casa", t)
        } finally {
            tickEnd = System.currentTimeMillis()
        }
    }

    /** Chamado pelo serviço de tempos em tempos: se a leitura travou ou nunca rodou, recomeça. */
    fun watchdog(context: Context) {
        if (exec == null) return
        val now = System.currentTimeMillis()
        val stuck = tickStart > 0 && tickEnd < tickStart && now - tickStart > 45_000
        val neverRan = tickStart == 0L && startedAt > 0 && now - startedAt > 30_000
        val stale = lastOkAt > 0 && now - lastOkAt > 120_000 && now - tickEnd > 30_000
        if (stuck || neverRan || stale) {
            val why = if (stuck) "travou em \"$stage\" há ${(now - tickStart) / 1000}s" else if (neverRan) "não começou" else "parou de ler"
            JLog.w("Monitor da casa $why; reiniciando")
            HomeModel.lastError = "leitura $why (reiniciada)"
            HomeModel.notifyListeners()
            start(context)
        }
    }

    private fun tick() {
        stage = "configuração"
        val p = prefs ?: return
        stage = "token"
        val api = HomeApi(p.serverUrl, p.token)
        val snap = try {
            stage = "lendo estados"
            val states = api.states()
            stage = "interpretando"
            HomeParser.parse(states, p.serverTempLimit.toDouble(), 90.0, p.serverSensorIds())
        } catch (e: Exception) {
            failures++
            HomeModel.lastError = describe(e)
            if (failures == 1 || failures % 60 == 0) JLog.w("Leitura da casa falhou ($failures)", e)
            if (failures >= 2) HomeModel.setOffline()
            return
        }
        failures = 0
        oks++
        lastOkAt = System.currentTimeMillis()
        stage = "atualizando a tela"
        HomeModel.update(snap)
        stage = "avisos"

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
            stage = "agenda"
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

        stage = "lembretes"
        if (p.calendarEnabled && p.agendaReminders) checkReminders(p)
        stage = "atualizações"
        appContext?.let { br.com.jarvishome.update.UpdateFlow.autoCheck(it) }
        stage = "fim"
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
        val now = System.currentTimeMillis()
        fun ago(t: Long) = if (t == 0L) "nunca" else "há ${(now - t) / 1000}s"
        sb.append("Leitura automática: ").append(if (exec != null) "ligada" else "parada")
            .append(" · $ticks leituras, $oks com sucesso\n")
            .append("   última: ").append(ago(tickStart)).append(if (tickStart > 0 && tickEnd < tickStart) " (ainda rodando: $stage)" else "")
            .append(" · último sucesso: ").append(ago(lastOkAt)).append('\n')
            .append("   tela: ").append(if (HomeModel.online) "conectada" else "desconectada")
            .append(" · ").append(HomeModel.calendars.size).append(" agenda(s), ").append(HomeModel.lights.size).append(" luz(es)\n")
            .append(HomeModel.lastError?.let { "Último erro: $it" } ?: "Sem erros")
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
