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
    println("1c2) Treino sem verbo (\"Obrigada, Jesus, da cozinha\"): inverte o estado atual")
    val semVerbo = HaDirectBackend(base, "HA_FAKE", "", wakeNames = listOf("Tony", "TONY", "TONI"),
        trained = listOf(br.com.jarvishome.commands.LightCommands.Trained("Obrigada, Jesus, da cozinha", true, "light.luz_da_cozinha")))
    val cozinhaAntes = mockState(base).contains("\"light.luz_da_cozinha\": \"on\"")
    stt(base, "Tony, obrigada, Jesus, da cozinha.")
    val inv = session(semVerbo, 3)
    val cozinhaDepois = mockState(base).contains("\"light.luz_da_cozinha\": \"on\"")
    ok(cozinhaDepois != cozinhaAntes && inv.any { it.optString("local").contains("inverteu") }, "cozinha estava " + (if (cozinhaAntes) "acesa → apagou" else "apagada → acendeu"))
    stt(base, "Tony, obrigada, Jesus, da cozinha.")
    session(semVerbo, 3)
    ok(mockState(base).contains("\"light.luz_da_cozinha\": \"on\"") == cozinhaAntes, "a mesma frase de novo volta ao estado anterior")
    println("1c3) Só o nome (\"luz da cozinha\"): funciona como interruptor")
    val antes3 = mockState(base).contains("\"light.luz_da_cozinha\": \"on\"")
    stt(base, "Tony, luz da cozinha.")
    val nome3 = session(bt, 3)
    ok(mockState(base).contains("\"light.luz_da_cozinha\": \"on\"") != antes3 && nome3.any { it.optString("local").contains("só o nome") }, "inverteu a cozinha")
    stt(base, "Tony, a luz da cozinha está acesa?")
    session(bt, 3)
    ok(mockState(base).contains("\"light.luz_da_cozinha\": \"on\"") != antes3, "pergunta não mexe na luz")
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

    println("1g) Reforço: aprende o que deu certo")
    val L = br.com.jarvishome.commands.Learning
    var learnedFix: Pair<String, String>? = null
    var learnedLight: String? = null
    L.onLearnFix = { h, s2 -> learnedFix = h to s2 }
    L.onLearnLight = { h, on, t -> learnedLight = "$h|$on|$t" }
    stt(base, "Jarvis, consultar gastos da.")
    session(b, 10)
    ok(learnedFix?.second == "consultar gastos", "2ª tentativa que deu certo vira correção aprendida: $learnedFix")
    val bl = HaDirectBackend(base, "HA_FAKE", "", wakeNames = listOf("Jarvis"), learnedFixes = mapOf(L.key("consultar gastos da.") to "consultar gastos"))
    stt(base, "Jarvis, consultar gastos da.")
    val e1g = session(bl, 10)
    ok(e1g.count { it.optString("type") == "transcription" } == 1 &&
        e1g.any { it.optString("type") == "response" && it.optString("response_type") != "error" }, "com o aprendizado vai direto, sem errar antes")
    stt(base, "Jarvis, ligar luz do zoquart.")
    session(b, 10)
    ok(learnedLight == "ligar luz do zoquart.|true|light.luz_do_quarto", "luz que só funcionou corrigida: aprende a frase ouvida ($learnedLight)")
    L.onUndo = { "a última correção" }
    stt(base, "Tony, não era isso.")
    val eu = session(b, 3)
    ok(eu.any { it.optString("type") == "response" && it.optString("text") == "Tudo bem, esqueci: a última correção." }, "\"não era isso\" desfaz")
    L.onLearnFix = null; L.onLearnLight = null; L.onUndo = null

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
    ok(e4.last().optString("code") == "only-wake-word" && e4.any { it.optString("heard").isNotBlank() }, "pede o comando de novo (only-wake-word) e registra o que ouviu")

    println("5) Token errado / cancelamento / pipeline quebrada")
    ok(session(HaDirectBackend(base, "errado", ""), 1).let { it.size == 1 && it[0].optString("code") == "unauthorized" }, "token errado → unauthorized")
    val t = System.currentTimeMillis(); session(b, 10, cancelAfterMs = 300)
    ok(System.currentTimeMillis() - t < 3000, "cancelamento devolve rápido")
    ok(session(HaDirectBackend(base, "HA_FAKE", "quebrada"), 1).any { it.optString("code") == "stt-provider-missing" }, "erro da pipeline repassado")
    println("6) Listas por voz")
    stt(base, "Jarvis, coloca ovos e manteiga na lista de compras.")
    val l1 = session(b, 2)
    ok(l1.any { it.optString("type") == "response" && it.optString("text") == "Coloquei ovos e manteiga na lista de compras." }, "adiciona por voz")
    stt(base, "Jarvis, o que tem na lista?")
    val l2 = session(b, 2)
    ok(l2.any { it.optString("type") == "response" && it.optString("text").contains("ovos") && it.optString("text").contains("manteiga") }, "lê a lista por voz")
    stt(base, "Jarvis, comprei ovos.")
    val l3 = session(b, 2)
    ok(l3.any { it.optString("type") == "response" && it.optString("text") == "Marquei como comprado: ovos." }, "comprei por voz")
    ok(l1.any { it.optString("type") == "audio" }, "responde com a voz do Home Assistant")
    stt(base, "Jarvis, obrigado.")
    ok(session(b, 2).any { it.optString("code") == "only-wake-word" }, "\"Obrigado\" inventado → reabre o microfone")
    println("7) Reforço: só ação de verdade confirma a chamada (conversa não vira \"Hey Tony\")")
    stt(base, "Jarvis, comprei banana.")
    val r1 = session(b, 2).first { it.optString("type") == "response" }
    ok(r1.optString("response_type") != "action_done" && !r1.optBoolean("confirms"), "\"comprei banana\" (não está na lista) não confirma: ${r1.optString("text")}")
    stt(base, "Jarvis, quanto falta do treino?")
    val r2 = session(b, 2).first { it.optString("type") == "response" }
    ok(!r2.optBoolean("confirms"), "\"quanto falta do treino?\" (pergunta ao HA) não confirma: ${r2.optString("text")}")
    stt(base, "Jarvis, liga a luz da sala.")
    val r3 = session(b, 3).first { it.optString("type") == "response" }
    ok(r3.optBoolean("confirms"), "luz acionada confirma")
    stt(base, "Jarvis, coloca pão na lista de compras.")
    val r4 = session(b, 2).first { it.optString("type") == "response" }
    ok(r4.optString("response_type") == "action_done" && !r4.optBoolean("confirms"), "lista mudou (action_done), mas não serve para aprender a chamada")
    println("TODOS OS TESTES PASSARAM")
}
