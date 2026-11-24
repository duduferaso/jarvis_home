import br.com.jarvishome.wakeword.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
fun ld(f: File): FloatArray { val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN); return FloatArray(b.remaining() / 4) { b.float } }
// Pontua a saída de alarme_bancada.py com a decisão real do app (WakeMatch, limiar 0,19, sem o próprio exemplo).
// Uso: java ... AlarmeBancadaKt <pasta calls/> <saída do alarme_bancada.py>
fun main(a: Array<String>) {
    val calls = File(a[0]).listFiles()!!.sorted().map { ld(it) }
    val tpls = calls.map { VoiceFeatures.compute(FloatArray(2400) + it).frames }
    val sets = calls.indices.map { i -> TemplateSet(tpls.filterIndexed { k, _ -> k != i }.toMutableList(), 0.19f) }
    val root = File(a[1])
    for (dir in root.listFiles()!!.filter { it.isDirectory }.sortedBy { it.name }) {
        val (s, g) = dir.name.split("_")
        val trials = dir.listFiles()!!.sortedBy { it.name }
        var hit = 0
        for (t in trials) {
            val ci = t.name.substring(1, 3).toInt()
            val ok = (t.listFiles() ?: emptyArray()).any { f ->
                val x = ld(f); val pre = x.copyOf(2400); val seg = x.copyOfRange(2400, x.size)
                seg.size >= 5600 && WakeMatch.decide(sets[ci], 12, 0.19f, pre + seg.copyOf(minOf(seg.size, SegmentEnd.WINDOW)), seg).hit
            }
            if (ok) hit++
        }
        println("%-45s %-26s chamadas que param o alarme: %3d/%d (%.0f%%)".format(File(root, "$s.name").readText(), File(root, "$g.name").readText(), hit, trials.size, 100.0 * hit / trials.size))
    }
}
