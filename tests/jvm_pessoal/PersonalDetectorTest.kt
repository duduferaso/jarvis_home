import br.com.jarvishome.wakeword.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
fun load(f: File): FloatArray { val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN); return FloatArray(b.remaining() / 4) { b.float } }
fun ok(c: Boolean, m: String) { println((if (c) "PASSOU " else "FALHOU ") + m); if (!c) System.exit(1) }
fun main(args: Array<String>) {
    val calls = File(args[0], "calls").listFiles()!!.sorted().map { load(it) }
    val negs = File(args[0], "negs").listFiles()!!.sorted().map { load(it) }
    val t0 = System.currentTimeMillis()
    val feats = calls.map { VoiceFeatures.compute(it).frames }
    val set = TemplateSet(feats.toMutableList(), TemplateSet.DEFAULT_THRESHOLD)
    val loo = feats.indices.map { set.score(feats[it], exclude = it).distance }
    println("   deixa-uma-de-fora (Kotlin): " + loo.sorted().joinToString(" ") { "%.3f".format(it) })
    // comparação direta com o protótipo (DTW completa, sem encaixe de prefixo)
    val looFull = feats.indices.map { i -> feats.indices.filter { it != i }.minOf { Dtw.full(feats[i], feats[it]) } }
    println("   DTW completa (Python deu 0.096 … 0.187): " + looFull.sorted().joinToString(" ") { "%.3f".format(it) })
    ok(Math.abs(looFull.maxOrNull()!! - 0.187f) < 0.01f && Math.abs(looFull.minOrNull()!! - 0.096f) < 0.01f, "Kotlin reproduz o protótipo em Python")
    val negScores = negs.map { set.score(VoiceFeatures.compute(it).frames).distance }
    println("   negativos: menor %.3f".format(negScores.minOrNull()))
    ok(loo.count { it <= 0.22f } >= 12, "reconhece as chamadas reais no limiar 0,22 (${loo.count { it <= 0.22f }}/13)")
    ok(negScores.count { it <= 0.22f } == 0, "nenhum disparo nos ${negs.size} trechos de fala/ruído")
    val rep = PersonalTrainer.choose(set, emptyList(), negs.map { VoiceFeatures.compute(it).frames })
    println("   treino escolheu limiar %.3f (pior chamada %.3f, fala mais parecida %.3f), reconhece %d/%d, falsos %d".format(rep.threshold, rep.worstPositive, rep.bestNegative, rep.recognized, rep.total, rep.falseHits))
    ok(rep.recognized == 13 && rep.falseHits == 0, "limiar automático pega 13/13 sem falsos")
    val tmp = File.createTempFile("tpl", ".bin"); set.threshold = rep.threshold; tmp.outputStream().use { set.write(it) }
    val back = tmp.inputStream().use { TemplateSet.read(it) }
    ok(back.templates.size == 13 && back.threshold == rep.threshold && back.templates[3][5][7] == set.templates[3][5][7], "salvar/carregar exemplos")
    // modelo de fábrica a partir das 13 chamadas reais
    File(args[1]).outputStream().use { set.write(it) }
    // tempo: um trecho de 2,2 s contra 13 exemplos
    val t1 = System.nanoTime(); repeat(20) { set.score(VoiceFeatures.compute(negs[0]).frames) }
    println("   tempo por trecho de 2,2 s (13 exemplos): %.0f ms nesta CPU".format((System.nanoTime() - t1) / 20e6))
    println("TODOS OS TESTES PASSARAM  (total %d ms)".format(System.currentTimeMillis() - t0))
}
