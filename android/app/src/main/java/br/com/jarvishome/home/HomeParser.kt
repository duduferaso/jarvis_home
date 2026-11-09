package br.com.jarvishome.home

import br.com.jarvishome.calendar.CalendarInfo
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * Lê a lista de estados do Home Assistant (GET /api/states) e extrai o que a tela mostra.
 * Lógica pura (sem Android), testável na JVM.
 */
object HomeParser {

    data class Light(val entityId: String, val name: String, val on: Boolean, val available: Boolean)
    data class Weather(val temperature: Double?, val unit: String, val condition: String)
    data class Snapshot(
        val lights: List<Light>,
        val weather: Weather?,
        val alerts: List<String>,
        val serverInfo: List<String>,
        val announcement: String?,
        val announcementEntity: String?,
        val hasAnnouncementEntity: Boolean,
        val calendars: List<CalendarInfo>,
        /** Todas as luzes e relés (light.* e switch.*), para a planta da casa. */
        val relays: List<FloorPlan.Relay> = emptyList()
    )

    const val ANNOUNCE_ENTITY = "input_text.jarvis_aviso"
    private const val WEATHER_MAX_AGE_MIN = 180L
    private const val MAX_LIGHTS = 8

    private val WEATHER_PT = mapOf(
        "clear-night" to "Céu limpo", "cloudy" to "Nublado", "exceptional" to "Tempo instável", "fog" to "Neblina",
        "hail" to "Granizo", "lightning" to "Trovoadas", "lightning-rainy" to "Tempestade", "partlycloudy" to "Parcialmente nublado",
        "pouring" to "Chuva forte", "rainy" to "Chuva", "snowy" to "Neve", "snowy-rainy" to "Neve e chuva",
        "sunny" to "Ensolarado", "windy" to "Ventando", "windy-variant" to "Ventando"
    )

    fun weatherName(condition: String): String = WEATHER_PT[condition] ?: condition.replaceFirstChar { it.uppercase() }

    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    private fun str(o: JSONObject?, k: String): String = if (o == null || !o.has(k) || o.isNull(k)) "" else o.optString(k)

    /** "Luz do Quarto" → "Quarto"; "Lâmpada da Sala" → "Sala". */
    fun shortLightName(name: String): String {
        val s = name.replace(Regex("^(?i)(luz(es)?|l[âa]mpadas?|ilumina[çc][ãa]o)\\s+(d[aoe]s?\\s+)?"), "").trim()
        return (if (s.isEmpty()) name else s).replaceFirstChar { it.uppercase() }
    }

    private fun isFresh(state: JSONObject, now: OffsetDateTime): Boolean {
        val ts = str(state, "last_updated").ifEmpty { str(state, "last_changed") }
        if (ts.isEmpty()) return true
        return try {
            ChronoUnit.MINUTES.between(OffsetDateTime.parse(ts), now) <= WEATHER_MAX_AGE_MIN
        } catch (e: Exception) { true }
    }

    fun parse(
        states: JSONArray,
        tempLimit: Double = 75.0,
        diskLimit: Double = 90.0,
        sensorIds: Set<String> = emptySet(),
        now: OffsetDateTime = OffsetDateTime.now()
    ): Snapshot {
        val lights = ArrayList<Light>()
        var weather: Weather? = null
        val alerts = ArrayList<String>()
        val info = ArrayList<String>()
        var announcement: String? = null
        var announcementEntity: String? = null
        var hasAnnounce = false
        val calendars = ArrayList<CalendarInfo>()
        val relays = ArrayList<FloorPlan.Relay>()

        for (i in 0 until states.length()) {
            val st = states.optJSONObject(i) ?: continue
            val id = str(st, "entity_id")
            val domain = id.substringBefore('.')
            val state = str(st, "state")
            val attrs = st.optJSONObject("attributes")
            val name = str(attrs, "friendly_name").ifEmpty { id.substringAfter('.').replace('_', ' ') }
            val n = norm(name)
            val available = state != "unavailable" && state != "unknown"

            if (domain == "light" || domain == "switch") relays.add(FloorPlan.Relay(id, name, state == "on", available))
            when (domain) {
                "light" -> lights.add(Light(id, shortLightName(name), state == "on", available))
                "switch" -> if (n.startsWith("luz") || n.contains("lampada") || n.contains(" luz ")) {
                    lights.add(Light(id, shortLightName(name), state == "on", available))
                }
                "weather" -> if (weather == null && available && isFresh(st, now)) {
                    val t = attrs?.optDouble("temperature", Double.NaN) ?: Double.NaN
                    weather = Weather(if (t.isNaN()) null else t, str(attrs, "temperature_unit").ifEmpty { "°C" }, weatherName(state))
                }
                "calendar" -> {
                    val features = attrs?.optInt("supported_features", 0) ?: 0
                    calendars.add(CalendarInfo(id, name, features and 1 != 0, features and 2 != 0))
                }
                "input_text" -> if (id == ANNOUNCE_ENTITY || id.contains("jarvis_aviso")) {
                    hasAnnounce = true
                    if (available && state.isNotBlank() && announcement == null) {
                        announcement = state.trim()
                        announcementEntity = id
                    }
                }
                "sensor" -> {
                    val v = state.toDoubleOrNull() ?: continue
                    val unit = str(attrs, "unit_of_measurement")
                    val dc = str(attrs, "device_class")
                    val key = norm("$name $id")
                    val listed = id in sensorIds
                    val isTemp = (dc == "temperature" || unit == "°C") &&
                        (listed || (sensorIds.isEmpty() && Regex("servidor|server|cpu|processador|processor").containsMatchIn(key)))
                    val isDisk = !isTemp && unit == "%" &&
                        (listed || (sensorIds.isEmpty() && Regex("disco|disk|armazenamento|storage|\\bhd\\b|\\bssd\\b").containsMatchIn(key)))
                    if (isTemp) {
                        info.add("$name: ${v.roundToInt()} °C")
                        if (v >= tempLimit) alerts.add("Servidor a ${v.roundToInt()} °C")
                    } else if (isDisk) {
                        val free = Regex("livre|free|dispon").containsMatchIn(key)
                        val used = if (free) 100 - v else v
                        info.add("$name: ${v.roundToInt()}%")
                        if (used >= diskLimit) alerts.add("Disco ${used.roundToInt()}% cheio")
                    }
                }
            }
        }
        lights.sortBy { norm(it.name) }
        return Snapshot(lights.take(MAX_LIGHTS), weather, alerts.distinct().take(2), info, announcement,
            announcementEntity, hasAnnounce, calendars, relays)
    }
}
