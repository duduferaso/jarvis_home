package br.com.jarvishome.home

import br.com.jarvishome.calendar.AgendaFormat
import br.com.jarvishome.network.TextCorrector
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Previsão do tempo dos próximos dias (serviço weather.get_forecasts do Home Assistant).
 * Respondida pelo próprio app: "previsão da semana", "vai chover amanhã?", "como vai estar o sábado?".
 */
object Forecast {

    data class Day(
        val date: LocalDate,
        val condition: String,   // já em português ("Chuva", "Nublado"…)
        val max: Double?,
        val min: Double?,
        val rainChance: Int?,    // %
        val rainMm: Double?
    )

    /** Agora (do estado da entidade) para "como está o tempo?". */
    data class Now(val temperature: Double?, val condition: String)

    // ------------------------------------------------------------------ busca

    @Volatile private var cache: List<Day> = emptyList()
    @Volatile private var cachedAt = 0L
    private const val CACHE_MS = 30 * 60_000L

    /** Primeira entidade weather.* disponível (a mesma que aparece na tela). */
    fun findEntity(states: JSONArray): String? {
        for (i in 0 until states.length()) {
            val st = states.optJSONObject(i) ?: continue
            val id = st.optString("entity_id")
            val s = st.optString("state")
            if (id.startsWith("weather.") && s != "unavailable" && s != "unknown") return id
        }
        return null
    }

    fun nowFrom(states: JSONArray, entity: String): Now? {
        for (i in 0 until states.length()) {
            val st = states.optJSONObject(i) ?: continue
            if (st.optString("entity_id") != entity) continue
            val t = st.optJSONObject("attributes")?.optDouble("temperature", Double.NaN) ?: Double.NaN
            return Now(if (t.isNaN()) null else t, HomeParser.weatherName(st.optString("state")))
        }
        return null
    }

    /** Próximos dias (usa o que buscou nos últimos 30 min). */
    fun fetch(api: HomeApi, entity: String, now: Long = System.currentTimeMillis()): List<Day> {
        val c = cache
        if (c.isNotEmpty() && now - cachedAt < CACHE_MS) return c
        val days = parse(api.forecast(entity, "daily"), entity)
        if (days.isNotEmpty()) { cache = days; cachedAt = now }
        return days
    }

    /** Resposta de weather.get_forecasts → dias. */
    fun parse(resp: JSONObject, entity: String, zone: ZoneId = ZoneId.systemDefault()): List<Day> {
        val root = resp.optJSONObject("service_response") ?: resp
        val ent = root.optJSONObject(entity) ?: run {
            // se veio de outra entidade, pega a primeira
            val it = root.keys()
            if (it.hasNext()) root.optJSONObject(it.next()) else null
        } ?: return emptyList()
        val arr = ent.optJSONArray("forecast") ?: return emptyList()
        val out = ArrayList<Day>()
        for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            val date = try {
                OffsetDateTime.parse(f.optString("datetime")).atZoneSameInstant(zone).toLocalDate()
            } catch (e: Exception) {
                try { LocalDate.parse(f.optString("datetime").take(10)) } catch (e2: Exception) { continue }
            }
            if (out.any { it.date == date }) continue
            fun num(k: String): Double? = f.optDouble(k, Double.NaN).takeIf { !it.isNaN() }
            out.add(Day(date, HomeParser.weatherName(f.optString("condition")), num("temperature"), num("templow"),
                num("precipitation_probability")?.roundToInt(), num("precipitation")))
        }
        return out
    }

    // ------------------------------------------------------------------ entender a pergunta

    sealed class Ask {
        object Now : Ask()
        data class Days(val dates: List<LocalDate>, val label: String) : Ask()
        object Week : Ask()
    }
    data class Query(val ask: Ask, val rain: Boolean)

    private val WEEKDAY_WORDS = listOf(
        "segunda" to DayOfWeek.MONDAY, "terca" to DayOfWeek.TUESDAY, "quarta" to DayOfWeek.WEDNESDAY,
        "quinta" to DayOfWeek.THURSDAY, "sexta" to DayOfWeek.FRIDAY, "sabado" to DayOfWeek.SATURDAY,
        "domingo" to DayOfWeek.SUNDAY
    )

    /** null = não é pergunta de tempo (segue para os outros comandos). */
    fun parseQuery(text: String, today: LocalDate): Query? {
        val n = " " + TextCorrector.normalize(text) + " "
        val rain = Regex(" (chover|chove|chuva|chuvoso|chovendo|guarda chuva) ").containsMatchIn(n)
        val forecastWord = Regex(" (previsao|tempo|clima) ").containsMatchIn(n) && !n.contains(" quanto tempo ") &&
            !n.contains(" tempo de ") && !Regex(" (timer|alarme|treino|serie|exercicio|descanso) ").containsMatchIn(n)
        val tempWord = Regex(" (frio|calor|quente|esquentar|esfriar|temperatura) ").containsMatchIn(n)

        // dias citados
        val dates = ArrayList<LocalDate>()
        var label = ""
        when {
            n.contains(" fim de semana ") || n.contains(" final de semana ") -> {
                // no sábado: hoje e amanhã; no domingo: o próximo fim de semana
                var d = if (today.dayOfWeek == DayOfWeek.SUNDAY) today.plusDays(1) else today
                while (d.dayOfWeek != DayOfWeek.SATURDAY) d = d.plusDays(1)
                dates.add(d); dates.add(d.plusDays(1))
                label = "no fim de semana"
            }
            n.contains(" depois de amanha ") -> { dates.add(today.plusDays(2)); label = AgendaFormat.weekdayName(today.plusDays(2)) }
            n.contains(" amanha ") -> { dates.add(today.plusDays(1)); label = "amanhã" }
            n.contains(" hoje ") -> { dates.add(today); label = "hoje" }
            else -> for ((w, dow) in WEEKDAY_WORDS) {
                if (n.contains(" $w ")) {
                    var d = today
                    while (d.dayOfWeek != dow) d = d.plusDays(1)
                    if (d == today && n.contains(" que vem ")) d = d.plusDays(7)
                    dates.add(d)
                    label = when {
                        d == today -> "hoje"
                        dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY -> "no " + AgendaFormat.weekdayName(d)
                        else -> "na " + AgendaFormat.weekdayName(d)
                    }
                    break
                }
            }
        }
        val week = Regex(" (semana|proximos dias|esses dias|estes dias|dias) ").containsMatchIn(n) && dates.isEmpty()

        // só intercepta o que é claramente sobre o tempo
        val about = rain || forecastWord || (tempWord && (dates.isNotEmpty() || week) && !n.contains(" ar condicionado ") &&
            !Regex(" (sala|quarto|cozinha|banheiro|geladeira|freezer|piscina|agua) ").containsMatchIn(n))
        if (!about) return null
        if (Regex(" (liga|desliga|acende|apaga|ligar|desligar) ").containsMatchIn(n)) return null

        val ask = when {
            week -> Ask.Week
            dates.isNotEmpty() -> Ask.Days(dates, label)
            rain -> Ask.Days(listOf(today), "hoje")
            n.contains(" previsao ") -> Ask.Week
            else -> Ask.Now
        }
        return Query(ask, rain)
    }

    // ------------------------------------------------------------------ resposta falada

    private fun deg(v: Double?) = v?.roundToInt()?.toString()

    private fun rainPhrase(d: Day): String? {
        val p = d.rainChance
        return when {
            p != null && p >= 20 -> "$p% de chance de chuva"
            p == null && (d.rainMm ?: 0.0) >= 1.0 -> "chuva de uns ${d.rainMm!!.roundToInt()} milímetros"
            else -> null
        }
    }

    private fun dayDetail(d: Day): String {
        val parts = ArrayList<String>()
        parts.add(d.condition.lowercase())
        val mx = deg(d.max); val mn = deg(d.min)
        if (mx != null && mn != null) parts.add("máxima de $mx e mínima de $mn graus")
        else if (mx != null) parts.add("máxima de $mx graus")
        rainPhrase(d)?.let { parts.add("com $it") }
        return parts.joinToString(", ")
    }

    private fun rainAnswer(d: Day, label: String): String {
        val p = d.rainChance
        val l = label.replaceFirstChar { it.uppercase() }
        return when {
            p == null -> if ((d.rainMm ?: 0.0) >= 1.0 || d.condition.contains("Chuva") || d.condition.contains("Tempestade"))
                "Sim, $label a previsão é de ${d.condition.lowercase()}." else "Não parece. $l: ${dayDetail(d)}."
            p >= 60 -> "Sim, $label tem $p% de chance de chuva. ${d.condition}, máxima de ${deg(d.max) ?: "?"} graus."
            p >= 30 -> "Talvez. $l tem $p% de chance de chuva."
            else -> "Provavelmente não. $l tem só $p% de chance de chuva."
        }
    }

    fun answer(q: Query, days: List<Day>, now: Now?, today: LocalDate): String {
        when (val a = q.ask) {
            is Ask.Now -> {
                val t = days.firstOrNull { it.date == today }
                val head = if (now != null) "Agora está " + (deg(now.temperature)?.let { "$it graus, " } ?: "") + now.condition.lowercase() + "." else ""
                val rest = t?.let { " Hoje: " + dayDetail(it) + "." } ?: ""
                return (head + rest).trim().ifEmpty { "Não consegui a previsão do tempo agora." }
            }
            is Ask.Days -> {
                val found = a.dates.mapNotNull { d -> days.firstOrNull { it.date == d } }
                if (found.isEmpty()) return "A previsão ainda não chega até esse dia."
                if (q.rain) {
                    if (found.size == 1) return rainAnswer(found[0], a.label)
                    val wet = found.filter { (it.rainChance ?: 0) >= 40 }
                    return if (wet.isEmpty()) "Provavelmente não chove ${a.label}: " +
                        found.joinToString("; ") { AgendaFormat.weekdayName(it.date) + " " + (it.rainChance ?: 0) + "%" } + "."
                    else "Pode chover ${a.label}: " + found.joinToString("; ") { AgendaFormat.weekdayName(it.date) + " " + (it.rainChance ?: 0) + "%" } + "."
                }
                if (found.size == 1) {
                    val d = found[0]
                    val nowPart = if (d.date == today && now != null && now.temperature != null) " Agora está ${deg(now.temperature)} graus." else ""
                    return a.label.replaceFirstChar { it.uppercase() } + ": " + dayDetail(d) + "." + nowPart
                }
                return found.joinToString(" ") { AgendaFormat.weekdayName(it.date).replaceFirstChar { c -> c.uppercase() } + ": " + dayDetail(it) + "." }
            }
            is Ask.Week -> {
                val list = days.filter { !it.date.isBefore(today) }.take(7)
                if (list.isEmpty()) return "Não consegui a previsão da semana agora."
                if (q.rain) {
                    val wet = list.filter { (it.rainChance ?: 0) >= 40 }
                    return if (wet.isEmpty()) "Não tem previsão de chuva forte nos próximos dias."
                    else "Deve chover " + wet.joinToString(", ") { dayWord(it.date, today) + " (" + it.rainChance + "%)" } + "."
                }
                return "Previsão: " + list.joinToString(". ") { d ->
                    dayWord(d.date, today).replaceFirstChar { it.uppercase() } + ", " + d.condition.lowercase() +
                        (deg(d.max)?.let { ", $it" } ?: "") + (deg(d.min)?.let { " e $it" } ?: "") +
                        ((d.rainChance)?.takeIf { it >= 40 }?.let { ", chuva $it%" } ?: "")
                } + "."
            }
        }
    }

    private fun dayWord(d: LocalDate, today: LocalDate) = when (d) {
        today -> "hoje"
        today.plusDays(1) -> "amanhã"
        else -> AgendaFormat.weekdayName(d).substringBefore("-")
    }

    /** Uma linha por dia para a tela: "Seg 17  ☁ Nublado  28° / 19°  💧 80%". */
    fun lines(days: List<Day>, today: LocalDate): List<String> = days.filter { !it.date.isBefore(today) }.take(7).map { d ->
        val name = dayWord(d.date, today).replaceFirstChar { it.uppercase() } + (if (d.date.isAfter(today.plusDays(1))) " " + d.date.dayOfMonth else "")
        name + " · " + d.condition + " · " + (deg(d.max) ?: "–") + "° / " + (deg(d.min) ?: "–") + "°" +
            (d.rainChance?.takeIf { it > 0 }?.let { " · chuva $it%" } ?: "")
    }
}
