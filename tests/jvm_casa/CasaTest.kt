import br.com.jarvishome.calendar.CalendarAssistant
import br.com.jarvishome.home.HomeApi
import br.com.jarvishome.home.HomeParser
import br.com.jarvishome.network.HaDirectBackend
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.OffsetDateTime

const val BASE = "http://127.0.0.1:18123"
var falhas = 0

fun ok(c: Boolean, m: String) {
    println((if (c) "PASSOU " else "FALHOU ") + m)
    if (!c) falhas++
}

fun post(path: String, body: String) {
    val c = URL(BASE + path).openConnection() as HttpURLConnection
    c.requestMethod = "POST"; c.doOutput = true
    c.setRequestProperty("Content-Type", "application/json")
    c.outputStream.use { it.write(body.toByteArray()) }
    c.inputStream.readBytes(); c.disconnect()
}

fun mockState(): JSONObject = JSONObject(URL("$BASE/test/state").readText())

/** Simula uma fala: o "Whisper" do HA simulado devolve [heard]. */
fun say(b: HaDirectBackend, heard: String): List<JSONObject> {
    post("/test/stt", JSONObject().put("text", heard).toString())
    val s = b.newSession()
    val events = ArrayList<JSONObject>()
    val producer = Thread {
        val chunk = ByteArray(3200) { (it % 50).toByte() }
        repeat(20) { s.push(chunk); Thread.sleep(10) }
        s.finish()
    }
    producer.start()
    s.run { synchronized(events) { events.add(it) } }
    producer.join()
    return events
}

fun response(ev: List<JSONObject>) = ev.firstOrNull { it.optString("type") == "response" }

fun main() {
    post("/test/reset", "{}")
    CalendarAssistant.clearState()
    val api = HomeApi(BASE, "HA_FAKE")

    println("-- leitura da casa")
    val snap = HomeParser.parse(api.states(), 75.0, 90.0, emptySet())
    ok(snap.lights.map { it.name } == listOf("Cozinha", "Jantar", "Quarto", "Sala"),
        "luzes com nomes curtos, incluindo o interruptor do jantar: " + snap.lights.map { it.name + "=" + it.on })
    ok(snap.weather?.condition == "Parcialmente nublado" && snap.weather?.temperature == 24.0, "clima: ${snap.weather}")
    ok(snap.alerts == listOf("Servidor a 78 °C"), "alerta do servidor acima de 75 °C: ${snap.alerts}")
    ok(snap.serverInfo.any { it.contains("63%") }, "disco detectado sem alerta: ${snap.serverInfo}")
    ok(snap.calendars.map { it.entityId + ":" + it.canCreate } == listOf("calendar.casa:true", "calendar.feriados:false"),
        "agendas e quem aceita compromissos: ${snap.calendars}")
    ok(snap.hasAnnouncementEntity && snap.announcement == null, "auxiliar de aviso encontrado, sem aviso pendente")
    val stale = HomeParser.parse(api.states(), now = OffsetDateTime.now().plusHours(4))
    ok(stale.weather == null, "clima velho (sem internet há 4 h) some da tela")

    println("-- luzes e avisos")
    api.toggle("light.luz_do_quarto")
    ok(HomeParser.parse(api.states()).lights.first { it.name == "Quarto" }.on, "tocar na luz do quarto liga")
    post("/test/aviso", JSONObject().put("value", "A máquina de lavar terminou").toString())
    val a = HomeParser.parse(api.states())
    ok(a.announcement == "A máquina de lavar terminou" && a.announcementEntity == HomeParser.ANNOUNCE_ENTITY, "aviso lido: ${a.announcement}")
    api.setText(a.announcementEntity!!, "")
    ok(HomeParser.parse(api.states()).announcement == null, "aviso limpo depois de lido (não repete)")
    ok(api.state("input_text.jarvis_aviso") != null && api.state("input_text.nao_existe") == null, "consulta de entidade (existe / não existe)")

    val tony = listOf("Tony", "TONY", "TONI", "TONEY", "TONEE")
    val b = HaDirectBackend(BASE, "HA_FAKE", "", wakeNames = tony)
    val url = b.synthesize("A máquina de lavar terminou")
    ok(url != null, "aviso transformado em voz pelo Piper do HA: $url")
    val f = File.createTempFile("aviso", ".mp3"); b.download(url!!, f)
    ok(f.length() == 1000L, "áudio do aviso baixado")

    println("-- agenda por voz")
    val tomorrow = LocalDate.now().plusDays(1)
    var ev = say(b, "Hey Tony, marca dentista amanhã às 15h.")
    ok(ev.first { it.optString("type") == "transcription" }.optString("text") == "marca dentista amanhã às 15h.", "chamada removida do texto")
    ok(response(ev)?.optString("text") == "Marquei dentista amanhã às 15 horas." && response(ev)?.optString("response_type") == "action_done",
        "resposta: \"${response(ev)?.optString("text")}\"")
    ok(ev.any { it.optString("type") == "audio" }, "resposta falada com a voz do HA")
    val call = mockState().optJSONArray("calls")!!.let { arr -> (0 until arr.length()).map { arr.optJSONObject(it)!! } }
        .last { it.optString("service") == "calendar.create_event" }.optJSONObject("data")!!
    ok(call.optString("entity_id") == "calendar.casa" && call.optString("summary") == "Dentista" &&
        call.optString("start_date_time") == "${tomorrow}T15:00:00-03:00" && call.optString("end_date_time") == "${tomorrow}T16:00:00-03:00",
        "evento criado na agenda certa e no horário certo: $call")

    ev = say(b, "Hey Tony, o que eu tenho amanhã?")
    ok(response(ev)?.optString("text") == "Amanhã você tem dentista às 15 horas.", "consulta: \"${response(ev)?.optString("text")}\"")

    ev = say(b, "Ei Tony, marca academia.")
    ok(response(ev)?.optString("text") == "Certo, academia. Para quando?" && response(ev)?.optBoolean("continue_conversation") == true,
        "pergunta para quando e reabre o microfone")
    ev = say(b, "amanhã às 7 da noite")
    ok(response(ev)?.optString("text") == "Marquei academia amanhã às 19 horas.", "completa com a resposta: \"${response(ev)?.optString("text")}\"")

    ev = say(b, "Hey Tony, desmarca o último compromisso.")
    ok(response(ev)?.optString("text") == "Desmarquei academia.", "desfaz o último: \"${response(ev)?.optString("text")}\"")
    ev = say(b, "Hey Tony, desmarca o dentista de amanhã.")
    ok(response(ev)?.optString("text") == "Desmarquei dentista de amanhã às 15 horas.", "desmarca pelo nome: \"${response(ev)?.optString("text")}\"")
    ok(mockState().optJSONArray("events")!!.length() == 0, "agenda vazia no HA depois de desmarcar")

    println("-- o resto continua indo para o Home Assistant")
    ev = say(b, "Hey Tony, liga a luz do quarto.")
    ok(response(ev)?.optString("text") == "Luz do quarto ligada.", "luz resolvida no app: \"${response(ev)?.optString("text")}\"")
    ev = say(b, "Hey Tony, liga o ventilador.")
    ok(response(ev)?.optString("text")?.contains("Luz da cozinha ligada") == true, "comando que não é de luz vai para o HA: \"${response(ev)?.optString("text")?.take(40)}\"")

    println("-- sem agenda que aceite compromissos")
    post("/test/nocal", "{\"on\": true}")
    ev = say(b, "Hey Tony, marca dentista amanhã às 15h.")
    ok(response(ev)?.optString("text")?.startsWith("Não encontrei uma agenda que aceite") == true && response(ev)?.optString("response_type") == "error",
        "explica o que falta: \"${response(ev)?.optString("text")}\"")
    post("/test/nocal", "{\"on\": false}")

    println(if (falhas == 0) "TODOS OS TESTES PASSARAM" else "$falhas FALHAS")
}
