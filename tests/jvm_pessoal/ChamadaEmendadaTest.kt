import br.com.jarvishome.wakeword.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Uso: java ... ChamadaEmendadaTestKt <pasta com calls/ e negs/ (.f32 16 kHz)>
// As gravações da casa não vão para o repositório (privacidade); ver LEIA-ME.md.
fun ld(f: File): FloatArray { val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN); return FloatArray(b.remaining() / 4) { b.float } }
fun rm(a: FloatArray) = Math.sqrt(a.map { (it * it).toDouble() }.average()).toFloat()
fun main(a: Array<String>) {
    var fl = 0
    fun ok(c: Boolean, m: String) { if (!c) fl++; println((if (c) "PASSOU " else "FALHOU ") + m) }
    val calls = File(a[0], "calls").listFiles()!!.sorted().map { ld(it) }
    val negs = File(a[0], "negs").listFiles()!!.sorted().map { ld(it) }
    val feats = calls.map { VoiceFeatures.compute(FloatArray(2400) + it).frames }
    val tvs = negs.filter { it.size > 16000 }.take(13)
    val limit = 0.20f
    // igual ao app: começo (1,9 s) e, se o trecho for maior, o fim
    fun detect(seg: FloatArray, tpls: List<Array<FloatArray>>): Float {
        val set = TemplateSet(tpls.toMutableList(), limit)
        val start = set.score(VoiceFeatures.compute(FloatArray(2400) + seg.copyOf(minOf(seg.size, SegmentEnd.WINDOW))).frames).distance
        if (start <= limit || seg.size <= SegmentEnd.WINDOW) return start
        val end = SegmentEnd.score(set, tpls.size, seg).distance
        return if (end <= limit * SegmentEnd.STRICT) end else minOf(start, end)
    }
    val emendada = calls.indices.map { i ->
        val pre = tvs[i].copyOfRange(tvs[i].size - 16000, tvs[i].size); val g = rm(calls[i]) / (rm(pre) + 1e-9f)
        detect(FloatArray(pre.size) { pre[it] * g } + FloatArray(1600) + calls[i], feats.filterIndexed { k, _ -> k != i }) }
    println("   fala emendada antes: " + emendada.joinToString(" ") { "%.3f".format(it) })
    ok(emendada.count { it <= limit * SegmentEnd.STRICT } >= 11, "chamada com fala emendada antes: ${emendada.count { it <= limit * SegmentEnd.STRICT }}/13 (antes 0/13)")
    val limpas = calls.indices.map { i -> detect(calls[i], feats.filterIndexed { k, _ -> k != i }) }
    ok(limpas.count { it <= limit } == 13, "chamadas limpas: ${limpas.count { it <= limit }}/13")
    val nd = negs.map { detect(it, feats) }
    ok(nd.count { it <= limit } == 0, "nenhum disparo nos ${negs.size} trechos de fala/ruído (menor ${"%.3f".format(nd.minOrNull())})")
    println(if (fl == 0) "TODOS OS TESTES PASSARAM" else "$fl FALHA(S)")
}
