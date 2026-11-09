package br.com.jarvishome.home

import android.os.Handler
import android.os.Looper
import br.com.jarvishome.calendar.CalEvent
import br.com.jarvishome.calendar.CalendarInfo
import br.com.jarvishome.settings.Prefs
import java.time.LocalTime
import java.util.concurrent.CopyOnWriteArrayList

/** O que a tela mostra sobre a casa. Escrito pelo HomeMonitor, lido pela tela (thread principal). */
object HomeModel {
    interface Listener { fun onHomeChanged() }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile var online = false; private set
    @Volatile var lights: List<HomeParser.Light> = emptyList(); private set
    @Volatile var weather: HomeParser.Weather? = null; private set
    @Volatile var alerts: List<String> = emptyList(); private set
    @Volatile var serverInfo: List<String> = emptyList(); private set
    @Volatile var calendars: List<CalendarInfo> = emptyList(); private set
    @Volatile var relays: List<FloorPlan.Relay> = emptyList(); private set
    @Volatile var hasAnnounceEntity = false; private set
    @Volatile var agenda: List<CalEvent> = emptyList(); private set
    @Volatile var agendaLoaded = false; private set
    @Volatile var agendaOnline = true; private set
    @Volatile var announcement: String? = null; private set
    @Volatile var announcementAt = 0L; private set
    /** Motivo da última falha ao ler a casa ou a agenda (diagnóstico). */
    @Volatile var lastError: String? = null
    @Volatile var agendaError: String? = null

    /**
     * Luzes recém-tocadas na tela: o relé/HA pode levar alguns segundos para informar o estado novo.
     * Até lá (ou até 6 s) a leitura antiga não "desfaz" o toque na tela.
     */
    private val pending = java.util.concurrent.ConcurrentHashMap<String, Pair<Boolean, Long>>()

    private fun held(id: String, on: Boolean, confirmed: MutableSet<String>): Boolean {
        val p = pending[id] ?: return on
        if (System.currentTimeMillis() > p.second) { pending.remove(id); return on }
        if (on == p.first) confirmed.add(id)
        return p.first
    }

    fun update(s: HomeParser.Snapshot) {
        online = true
        lastError = null
        val confirmed = HashSet<String>()
        lights = s.lights.map { l -> held(l.entityId, l.on, confirmed).let { if (it == l.on) l else l.copy(on = it) } }
        relays = s.relays.map { r -> held(r.entityId, r.on, confirmed).let { if (it == r.on) r else FloorPlan.Relay(r.entityId, r.name, it, r.available) } }
        confirmed.forEach { pending.remove(it) }
        weather = s.weather
        alerts = s.alerts
        serverInfo = s.serverInfo
        calendars = s.calendars
        hasAnnounceEntity = s.hasAnnouncementEntity
        notifyListeners()
    }

    fun setOffline() {
        if (!online) return
        online = false
        agendaOnline = false
        notifyListeners()
    }

    fun setAgenda(events: List<CalEvent>) {
        agenda = events
        agendaError = null
        agendaLoaded = true
        agendaOnline = true
        notifyListeners()
    }

    fun setAnnouncement(text: String) {
        announcement = text
        announcementAt = System.currentTimeMillis()
        notifyListeners()
    }

    fun dismissAnnouncement() {
        announcement = null
        notifyListeners()
    }

    /** Liga/desliga na tela antes da confirmação do HA (a próxima leitura corrige se falhar). */
    fun setLightOptimistic(entityId: String, on: Boolean) {
        pending[entityId] = on to System.currentTimeMillis() + 6000
        lights = lights.map { if (it.entityId == entityId) it.copy(on = on) else it }
        relays = relays.map { if (it.entityId == entityId) FloorPlan.Relay(it.entityId, it.name, on, it.available) else it }
        notifyListeners()
    }

    fun clear() {
        online = false
        lights = emptyList(); weather = null; alerts = emptyList(); serverInfo = emptyList()
        calendars = emptyList(); agenda = emptyList(); agendaLoaded = false
        notifyListeners()
    }

    fun notifyListeners() {
        main.post { for (l in listeners) l.onHomeChanged() }
    }

    fun addListener(l: Listener) {
        listeners.add(l)
        main.post { l.onHomeChanged() }
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }
}

/** Horário do modo noite (pode atravessar a meia-noite: 23h → 6h). */
object Night {
    fun isNight(prefs: Prefs, t: LocalTime = LocalTime.now()): Boolean {
        if (!prefs.nightMode) return false
        val s = prefs.nightStart
        val e = prefs.nightEnd
        val h = t.hour
        return when {
            s == e -> false
            s < e -> h in s until e
            else -> h >= s || h < e
        }
    }
}
