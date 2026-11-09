package br.com.jarvishome.home

import br.com.jarvishome.calendar.CalEvent
import br.com.jarvishome.calendar.CalendarApi
import br.com.jarvishome.calendar.CalendarInfo
import br.com.jarvishome.network.MiniWebSocket
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Acesso direto ao Home Assistant pela rede local (REST + WebSocket), com o token do usuário "jarvis".
 * Sem Android: testável na JVM.
 */
class HomeApi(baseUrl: String, private val token: String) : CalendarApi {

    private val base = baseUrl.trimEnd('/')

    class HttpError(val code: Int, message: String) : IOException(message)

    private fun request(method: String, path: String, body: JSONObject? = null, timeoutMs: Int = 8000): String {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.connectTimeout = 3000
            conn.readTimeout = timeoutMs
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw HttpError(code, "HTTP $code: " + text.take(200))
            return text
        } finally {
            conn.disconnect()
        }
    }

    fun states(): JSONArray = JSONArray(request("GET", "/api/states"))

    /** Estado de uma entidade, ou null se ela não existir. */
    fun state(entityId: String): JSONObject? = try {
        JSONObject(request("GET", "/api/states/$entityId"))
    } catch (e: HttpError) {
        if (e.code == 404) null else throw e
    }

    fun callService(domain: String, service: String, data: JSONObject) {
        request("POST", "/api/services/$domain/$service", data, 10000)
    }

    fun toggle(entityId: String) = callService("homeassistant", "toggle", JSONObject().put("entity_id", entityId))

    fun setText(entityId: String, value: String) =
        callService("input_text", "set_value", JSONObject().put("entity_id", entityId).put("value", value))

    // ------------------------------------------------------------------ agenda

    private val iso = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
    private fun isoLocal(t: LocalDateTime): String = t.atZone(ZoneId.systemDefault()).format(iso)
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun str(o: JSONObject?, k: String): String = if (o == null || !o.has(k) || o.isNull(k)) "" else o.optString(k)

    override fun calendars(): List<CalendarInfo> = HomeParser.parse(states()).calendars

    private fun whenOf(o: JSONObject?): Pair<LocalDateTime, Boolean>? {
        val dt = str(o, "dateTime")
        if (dt.isNotEmpty()) {
            return try {
                OffsetDateTime.parse(dt).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime() to false
            } catch (e: Exception) {
                LocalDateTime.parse(dt) to false
            }
        }
        val d = str(o, "date")
        if (d.isNotEmpty()) return LocalDate.parse(d).atStartOfDay() to true
        return null
    }

    override fun events(calendars: List<String>, from: LocalDateTime, to: LocalDateTime): List<CalEvent> {
        val out = ArrayList<CalEvent>()
        for (cal in calendars) {
            val text = try {
                request("GET", "/api/calendars/$cal?start=" + enc(isoLocal(from)) + "&end=" + enc(isoLocal(to)))
            } catch (e: Exception) {
                if (calendars.size == 1) throw e
                continue // uma agenda com problema não derruba as outras
            }
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val s = whenOf(e.optJSONObject("start")) ?: continue
                val en = whenOf(e.optJSONObject("end")) ?: (s.first.plusHours(1) to s.second)
                out.add(CalEvent(cal, str(e, "uid"), str(e, "recurrence_id"), str(e, "summary").ifEmpty { "Compromisso" },
                    s.first, en.first, s.second))
            }
        }
        out.sortBy { it.start }
        return out
    }

    override fun create(calendar: String, title: String, start: LocalDateTime?, end: LocalDateTime?, date: LocalDate?, endDate: LocalDate?) {
        val data = JSONObject().put("entity_id", calendar).put("summary", title)
        if (start != null && end != null) {
            data.put("start_date_time", isoLocal(start)).put("end_date_time", isoLocal(end))
        } else {
            data.put("start_date", date.toString()).put("end_date", endDate.toString())
        }
        callService("calendar", "create_event", data)
    }

    override fun delete(calendar: String, uid: String, recurrenceId: String) {
        val ws = MiniWebSocket.connect("$base/api/websocket", 4000)
        try {
            ws.readTimeoutMs = 10000
            ws.readText()
            ws.sendText(JSONObject().put("type", "auth").put("access_token", token).toString())
            if (JSONObject(ws.readText()).optString("type") != "auth_ok") throw IOException("Token recusado")
            val req = JSONObject().put("id", 1).put("type", "calendar/event/delete").put("entity_id", calendar).put("uid", uid)
            if (recurrenceId.isNotEmpty()) req.put("recurrence_id", recurrenceId)
            ws.sendText(req.toString())
            while (true) {
                val msg = JSONObject(ws.readText())
                if (msg.optString("type") == "result" && msg.optInt("id") == 1) {
                    if (!msg.optBoolean("success", false)) {
                        throw IOException(msg.optJSONObject("error")?.optString("message") ?: "Falha ao desmarcar")
                    }
                    return
                }
            }
        } finally {
            ws.close()
        }
    }
}
