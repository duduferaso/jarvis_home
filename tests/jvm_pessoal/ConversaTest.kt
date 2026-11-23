import br.com.jarvishome.wakeword.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Conversa na sala não pode virar "Hey Tony" (bug de "ativar sozinho").
// Roda a decisão real do app (WakeMatch: começo do trecho, exemplos aprendidos e fim do trecho)
// sobre gravações da casa: 13 chamadas e 105 trechos de conversa/TV de 2,2 s.
// Uso: java ... ConversaTestKt <pasta com calls/ e negs/ (.f32 16 kHz)>
// As gravações não vão para o repositório (privacidade); ver LEIA-ME.md.
private fun carrega(f: File): FloatArray { val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN); return FloatArray(b.remaining() / 4) { b.float } }

private const val PRE = 2400
private const val MAX_SEG = 8 * 16000          // o Silero corta trechos em 8 s
private const val LIMITE = 0.20f               // o maior limiar que o treino automático escolhe (MAX_AUTO)

// igual ao app: 0,15 s antes + até 1,9 s do começo do trecho
private fun crop(antes: FloatArray, seg: FloatArray): FloatArray {
    val pre = if (antes.size >= PRE) antes.copyOfRange(antes.size - PRE, antes.size) else FloatArray(PRE - antes.size) + antes
    return pre + seg.copyOf(minOf(seg.size, SegmentEnd.WINDOW))
}

fun main(a: Array<String>) {
    var falhas = 0
    fun ok(c: Boolean, m: String) { if (!c) falhas++; println((if (c) "PASSOU " else "FALHOU ") + m) }
    val calls = File(a[0], "calls").listFiles()!!.sorted().map { carrega(it) }
    val negs = File(a[0], "negs").listFiles()!!.sorted().map { carrega(it) }
    val tpls = calls.map { VoiceFeatures.compute(FloatArray(PRE) + it).frames }

    fun decide(set: TemplateSet, trained: Int, antes: FloatArray, seg: FloatArray, limit: Float = LIMITE) =
        WakeMatch.decide(set, trained, limit, crop(antes, seg), seg)

    val treinados = TemplateSet(tpls.toMutableList(), LIMITE)

    // 1) cada trecho de conversa sozinho (pausa antes)
    val sozinhos = negs.map { decide(treinados, tpls.size, FloatArray(0), it) }
    val menor1 = sozinhos.minOf { it.score.distance }
    ok(sozinhos.none { it.hit }, "conversa em trechos curtos: ${sozinhos.count { it.hit }}/${negs.size} disparos (menor distância %.3f)".format(menor1))

    // 2) conversa contínua: o Silero junta falas com menos de 0,3 s de pausa num trecho só (até 8 s).
    //    Exercita a comparação pelo FIM do trecho (SegmentEnd), que é a parte mais nova do detector.
    val gap = FloatArray(1600)                  // 0,1 s
    val longos = ArrayList<Pair<FloatArray, FloatArray>>()
    for (n in 2..3) for (i in 0..negs.size - n) {
        var seg = FloatArray(0)
        for (k in i until i + n) seg = if (seg.isEmpty()) negs[k] else seg + gap + negs[k]
        longos.add((if (i > 0) negs[i - 1] else FloatArray(0)) to seg.copyOf(minOf(seg.size, MAX_SEG)))
    }
    val rl = longos.map { (antes, seg) -> decide(treinados, tpls.size, antes, seg) }
    ok(rl.none { it.hit }, "conversa contínua (2 a 3 falas emendadas): ${rl.count { it.hit }}/${rl.size} disparos" +
        " (menor %.3f; pelo fim %d)".format(rl.minOf { it.score.distance }, rl.count { it.atEnd }))

    // 3) com o detector mais sensível de propósito (limiar 10% acima): ainda sem disparo = há folga
    val folga = negs.map { decide(treinados, tpls.size, FloatArray(0), it, LIMITE * 1.1f) }
    ok(folga.none { it.hit }, "folga: com limiar %.2f, ${folga.count { it.hit }} disparos".format(LIMITE * 1.1f))

    // 4) exemplos aprendidos de chamadas de verdade (o que o reforço deve guardar) não abrem a porta para conversa
    val comAprendidos = TemplateSet((tpls + tpls.take(10)).toMutableList(), LIMITE)
    val ra = negs.map { decide(comAprendidos, tpls.size, FloatArray(0), it) }
    ok(ra.none { it.hit }, "com 10 exemplos aprendidos de chamadas reais: ${ra.count { it.hit }} disparos na conversa")

    // 5) o bug: um trecho de conversa aprendido como "Hey Tony" passa a disparar com essa mesma fala.
    //    Por isso o reforço só aprende quando a ação foi confirmada (luz acionada, HA "action_done"),
    //    e a 1.15.10 apaga os aprendidos uma vez (learnedResetV3). Ver tests/jvm/Main.kt seção 7.
    val conversaAprendida = TemplateSet((tpls + listOf(VoiceFeatures.compute(FloatArray(PRE) + negs[0]).frames)).toMutableList(), LIMITE)
    val rb = decide(conversaAprendida, tpls.size, FloatArray(0), negs[0])
    ok(rb.hit && rb.viaLearned, "conversa aprendida como chamada dispara de novo (é por isso que não pode ser aprendida)")

    // 6) e as chamadas de verdade continuam sendo reconhecidas (cada uma sem o próprio exemplo)
    val chamadas = calls.indices.map { i ->
        val outros = tpls.filterIndexed { k, _ -> k != i }
        decide(TemplateSet(outros.toMutableList(), LIMITE), outros.size, FloatArray(0), calls[i])
    }
    ok(chamadas.count { it.hit } == calls.size, "chamadas reais reconhecidas: ${chamadas.count { it.hit }}/${calls.size}")

    // 7) chamada logo depois de 1 s de conversa no mesmo volume, sem pausa: reconhecida pelo fim do trecho
    //    (mesmo cenário de ChamadaEmendadaTest, agora pela decisão completa do app)
    fun rms(x: FloatArray) = Math.sqrt(x.map { (it * it).toDouble() }.average()).toFloat()
    val emendadas = calls.indices.map { i ->
        val outros = tpls.filterIndexed { k, _ -> k != i }
        val n = negs[i]; val antes = n.copyOfRange(n.size - 16000, n.size); val g = rms(calls[i]) / (rms(antes) + 1e-9f)
        decide(TemplateSet(outros.toMutableList(), LIMITE), outros.size, FloatArray(0), FloatArray(antes.size) { antes[it] * g } + gap + calls[i])
    }
    ok(emendadas.count { it.hit } >= 11, "chamada logo depois de conversa: ${emendadas.count { it.hit }}/${calls.size}")

    println(if (falhas == 0) "TODOS OS TESTES PASSARAM" else "$falhas FALHA(S)")
}
