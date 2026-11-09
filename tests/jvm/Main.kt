import br.com.jarvishome.domain.ConnectionState
import br.com.jarvishome.network.HaDirectBackend
import org.json.JSONObject
import java.io.File

fun session(b: HaDirectBackend, seconds: Int, cancelAfterMs: Long = -1): List<JSONObject> {
    val s = b.newSession(); val events = ArrayList<JSONObject>()
    val producer = Thread {
        val chunk = ByteArray(3200) { (it % 50).toByte() }
        val t0 = System.currentTimeMillis()
        for (k in 0 until seconds * 10) {
            if (cancelAfterMs in 0..(System.currentTimeMillis() - t0)) { s.cancel(); return@Thread }
            s.push(chunk); Thread.sleep(20)
        }
        s.finish()
    }
    producer.start()
    val t = System.currentTimeMillis()
    s.run { synchronized(events) { events.add(it) } }
    producer.join()
    println("   (${System.currentTimeMillis() - t} ms) " + events.joinToString(" | ") { e -> e.toString().take(110) })
    return events
}

fun stt(base: String, text: String) {
    (java.net.URL("$base/test/stt").openConnection() as java.net.HttpURLConnection).apply {
        requestMethod = "POST"; doOutput = true; setRequestProperty("Content-Type", "application/json")
        outputStream.use { it.write(JSONObject().put("text", text).toString().toByteArray()) }
        responseCode; disconnect()
    }
}
fun mockState(base: String): String = java.net.URL("$base/test/state").readText()

fun ok(cond: Boolean, msg: String) { println((if (cond) "PASSOU " else "FALHOU ") + msg); if (!cond) System.exit(1) }

fun main() {
    val base = "http://127.0.0.1:18123"
    val wake = listOf("Jarvis", "JAVIS", "JOVIS")
    ok(HaDirectBackend(base, "HA_FAKE", "").health() == ConnectionState.CONNECTED, "health com token certo = CONNECTED")
    ok(HaDirectBackend(base, "errado", "").health() == ConnectionState.UNAUTHORIZED, "health com token errado = UNAUTHORIZED")
    ok(HaDirectBackend("http://127.0.0.1:1", "x", "").health() == ConnectionState.DISCONNECTED, "health sem servidor = DISCONNECTED")
    ok(HaDirectBackend(base, "", "").health() == ConnectionState.NOT_CONFIGURED, "health sem token = NOT_CONFIGURED")

    val b = HaDirectBackend(base, "HA_FAKE", "", wakeNames = wake, extraVocabulary = listOf("financeiro"))
    HaDirectBackend.vocabularyAt = 0; b.refreshVocabulary()
    val v = HaDirectBackend.vocabulary
    ok("consultar quanto foi amortizado" in v && "consultar gastos" in v && "WakeUp" !in v,
        "automações \"Voz: …\" viram frases conhecidas (sem o prefixo e sem parênteses)")
    ok("Luz do Quarto" in v && "Quarto" in v && "dormitório" in v && "Eduardo" !in v,
        "nomes da casa lidos do HA (dispositivos + cômodos + apelidos, sem pessoas): $v")

    println("1) Whisper ouve \"Jarvis, ligar luz do zoquart.\"")
    val e1 = session(b, 10)
    val tr = e1.first { it.optString("type") == "transcription" }
    ok(tr.optString("heard") == "Jarvis, ligar luz do zoquart.", "texto original preservado para diagnóstico")
    ok(tr.optString("text") == "ligar luz do quarto.", "app removeu \"Jarvis\" e corrigiu para: \"${tr.optString("text")}\"")
    ok(e1.any { it.optString("type") == "response" && it.optString("response_type") == "action_done" &&
        it.optString("text") == "Luz do quarto ligada." }, "luz ligada pelo próprio app")
    ok(tr.optString("local") == "Ligar Luz do Quarto", "comando de luz resolvido no app: ${tr.optString("local")}")
    ok(mockState(base).contains("\"light.luz_do_quarto\": \"on\""), "Home Assistant recebeu homeassistant.turn_on")
    val audio = e1.first { it.optString("type") == "audio" }.optString("url")
    val f = File.createTempFile("tts", ".mp3"); b.download(audio, f)
    ok(f.length() == 1000L && b.resolve(audio).startsWith(base), "resposta falada baixada pelo IP configurado")

    println("1a) \"Hey Tony, desliga a luz da sala e da cozinha\" (frase inteira, com a chamada)")
    val bt = HaDirectBackend(base, "HA_FAKE", "", wakeNames = listOf("Tony", "TONY", "TONI"))
    stt(base, "Ei, Tony, desliga a luz da sala e da cozinha.")
    val e1a = session(bt, 3)
    ok(e1a.any { it.optString("type") == "response" && it.optString("text") == "Desliguei: sala e cozinha." }, "duas luzes numa frase")
    ok(mockState(base).let { it.contains("\"light.luz_da_sala\": \"off\"") && it.contains("\"light.luz_da_cozinha\": \"off\"") }, "as duas desligaram")
    stt(base, "E aí Toni, de liga a luz do quarto.")
    session(bt, 3)
    ok(mockState(base).contains("\"light.luz_do_quarto\": \"off\""), "\"de liga\" entendido como desliga")
    println("1c) Exemplo treinado: a voz da pessoa vira \"lisa luz do zoquart\"")
    val trained = HaDirectBackend(base, "HA_FAKE", "", wakeNames = listOf("Tony", "TONY", "TONI"),
        trained = listOf(br.com.jarvishome.commands.LightCommands.Trained("lisa luz do zoquart", true, "light.luz_do_quarto")))
    stt(base, "Tony, lisa luz do zoquart.")
    val e1c = session(trained, 3)
    ok(e1c.first { it.optString("type") == "transcription" }.optString("local") == "Ligar Luz do Quarto (treino)", "reconhecido pelo treino")
    ok(mockState(base).contains("\"light.luz_do_quarto\": \"on\""), "e a luz ligou")
    println("1d) Treino: só transcrever uma gravação")
    stt(base, "Liga a luz da sala.")
    ok(bt.transcribeAudio(ByteArray(64000)) == "Liga a luz da sala.", "transcribeAudio devolve o texto do Whisper")

    println("1e) \"Hey Tony, alarme de 5 minutos\" vira timer no app")
    br.com.jarvishome.commands.Timers.clear()
    stt(base, "Ei Tony, alarme de 5 minutos.")
    val e1e = session(bt, 3)
    ok(e1e.any { it.optString("type") == "response" && it.optString("text") == "5 minutos, começando agora." }, "resposta do timer")
    ok(br.com.jarvishome.commands.Timers.all().size == 1, "timer criado")
    ok(e1e.any { it.optString("type") == "audio" }, "resposta falada pela voz do HA")
    stt(base, "Tony, quanto falta?")
    val q = session(bt, 3).firstOrNull { it.optString("type") == "response" }?.optString("text") ?: ""
    ok(q.startsWith("Faltam 4 minutos") || q.startsWith("Faltam 5 minutos"), "quanto falta: $q")
    br.com.jarvishome.commands.Timers.clear()

    println("1f) Treino por voz")
    stt(base, "Ei Tony, começa o treino de 20 minutos.")
    val e1f = session(bt, 3)
    ok(br.com.jarvishome.commands.Workout.active && br.com.jarvishome.commands.Workout.plan!!.totalMin == 20, "treino de 20 minutos começou")
    ok(e1f.any { it.optString("type") == "response" && it.optString("text").startsWith("Treino de 20 minutos") }, "resposta do treino")
    stt(base, "Tony, pausa.")
    session(bt, 3)
    ok(br.com.jarvishome.commands.Workout.paused, "\"pausa\" pausou o treino")
    stt(base, "Tony, para o treino.")
    session(bt, 3)
    ok(!br.com.jarvishome.commands.Workout.active, "\"para o treino\" encerrou")

    println("1b) Whisper ouve \"Jarvis, consultar gastos da.\" e a casa não entende")
    stt(base, "Jarvis, consultar gastos da.")
    val e1b = session(b, 10)
    val trs = e1b.filter { it.optString("type") == "transcription" }
    println("   transcrições: " + trs.map { it.optString("text") })
    ok(trs.size == 2 && trs[1].optBoolean("retry") && trs[1].optString("text") == "consultar gastos",
        "2ª tentativa com o comando mais parecido")
    val rs = e1b.filter { it.optString("type") == "response" }
    ok(rs.size == 1 && rs[0].optString("response_type") == "action_done", "só a resposta da 2ª tentativa chega (sem o \"não entendi\")")
    ok(e1b.count { it.optString("type") == "audio" } == 1, "um único áudio de resposta")

    println("2) Mesma frase com a correção DESLIGADA (mostra o problema original)")
    val e2 = session(HaDirectBackend(base, "HA_FAKE", "", wakeNames = wake, correctText = false), 2)
    ok(e2.first { it.optString("type") == "transcription" }.optString("text") == "ligar luz do zoquart.", "sem correção o texto chega errado")
    ok(e2.any { it.optString("type") == "response" && it.optString("response_type") == "error" }, "e o HA não entende")

    println("3) Conversa continua (conversation_id) e mensagem grande")
    stt(base, "Jarvis, como está a casa?")
    val e3 = session(b, 2)
    ok(e3.first { it.optString("type") == "response" }.optString("text").let { it.startsWith("CONTEXTO conv123") && it.length > 70000 },
        "conversation_id reenviado e resposta de mais de 64 KB lida")

    println("4) Ouviu só \"Jarvis\"")
    val e4 = session(HaDirectBackend(base, "HA_FAKE", "so_jarvis", wakeNames = wake), 2)
    ok(e4.size == 1 && e4[0].optString("code") == "only-wake-word", "pede o comando de novo (only-wake-word)")

    println("5) Token errado / cancelamento / pipeline quebrada")
    ok(session(HaDirectBackend(base, "errado", ""), 1).let { it.size == 1 && it[0].optString("code") == "unauthorized" }, "token errado → unauthorized")
    val t = System.currentTimeMillis(); session(b, 10, cancelAfterMs = 300)
    ok(System.currentTimeMillis() - t < 3000, "cancelamento devolve rápido")
    ok(session(HaDirectBackend(base, "HA_FAKE", "quebrada"), 1).any { it.optString("code") == "stt-provider-missing" }, "erro da pipeline repassado")
    println("TODOS OS TESTES PASSARAM")
}
