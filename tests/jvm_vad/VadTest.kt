import br.com.jarvishome.audio.VoiceActivityDetector
import br.com.jarvishome.audio.VoiceActivityDetector.Result

fun main() {
    var fl = 0
    fun ok(c: Boolean, m: String) { if (!c) fl++; println((if (c) "PASSOU " else "FALHOU ") + m) }
    fun run(v: VoiceActivityDetector, frames: List<Double>): Pair<Result, Int> {
        for ((i, r) in frames.withIndex()) { val res = v.process(r); if (res != Result.CONTINUE) return res to i }
        return Result.CONTINUE to frames.size
    }
    val quiet = 150.0; val loud = 3000.0
    fun rep(x: Double, n: Int) = List(n) { x }
    // 1) bipe (2 quadros), pessoa espera 1,2 s, fala 0,8 s, silêncio → só termina depois da fala
    val beepThenPause = rep(loud, 2) + rep(quiet, 12) + rep(loud, 8) + rep(quiet, 12)
    val v1 = VoiceActivityDetector(900); v1.start(300)
    val (r1, at1) = run(v1, beepThenPause)
    ok(r1 == Result.END_OF_SPEECH && at1 >= 22, "bipe + espera não encerra antes do comando (fim no quadro $at1)")
    // 2) mesmo sem a proteção do bipe, um estalo curto não vira comando
    val v2 = VoiceActivityDetector(900); v2.start(0)
    val (r2, at2) = run(v2, beepThenPause)
    ok(r2 == Result.END_OF_SPEECH && at2 >= 22, "ruído curto não conta como fala (fim no quadro $at2)")
    // 3) fala normal termina no silêncio
    val v3 = VoiceActivityDetector(900); v3.start(300)
    val (r3, at3) = run(v3, rep(quiet, 3) + rep(loud, 10) + rep(quiet, 12))
    ok(r3 == Result.END_OF_SPEECH && at3 == 3 + 10 + 9 - 1, "fala normal termina após 900 ms de silêncio (quadro $at3)")
    // 4) ninguém fala → NO_SPEECH em 5 s
    val v4 = VoiceActivityDetector(900); v4.start(300)
    ok(run(v4, rep(loud, 2) + rep(quiet, 60)).first == Result.NO_SPEECH, "só o bipe e silêncio → não ouvi nada")
    // 5) frase única: termina no primeiro silêncio
    val v5 = VoiceActivityDetector(900); v5.startAfterSpeech()
    ok(run(v5, rep(quiet, 12)).first == Result.END_OF_SPEECH, "frase única termina no silêncio")
    // 6) uma palavra curta ("pausa") não se perde: vai para a transcrição no fim do prazo
    val v6 = VoiceActivityDetector(900); v6.start(300)
    val (r6, _) = run(v6, rep(quiet, 4) + rep(loud, 2) + rep(quiet, 60))
    ok(r6 == Result.END_OF_SPEECH && v6.heardSpeech, "palavra curta é enviada ($r6)")
    println(if (fl == 0) "TODOS OS TESTES PASSARAM" else "$fl FALHA(S)")
}
