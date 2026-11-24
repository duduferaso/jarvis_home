import br.com.jarvishome.commands.AlarmHold

// Toque do alarme calado enquanto alguém fala (para o "Hey Tony" ser reconhecido por cima do alarme).
// Bancada com as gravações da casa (tests/jvm_pessoal/alarme_bancada.py): bipe 20 dB acima da voz,
// chamadas que paravam o alarme: 15% (antes) → 71% (pausa de 2,6 s + toque calado ao ouvir fala).
fun main() {
    var falhas = 0
    fun ok(c: Boolean, m: String) { if (!c) falhas++; println((if (c) "PASSOU " else "FALHOU ") + m) }

    val h = AlarmHold(holdMs = 3500, minPlayMs = 1200)
    ok(h.canPlay(0), "sem ninguém falando, toca")
    ok(h.speech(1000), "alguém começou a falar: cala o toque")
    ok(!h.canPlay(1500) && !h.canPlay(4400), "fica calado durante a pausa (3,5 s)")
    ok(!h.speech(2000), "fala continuando não estica a pausa")
    ok(h.canPlay(4500), "depois da pausa o toque volta sozinho")
    ok(!h.speech(4600) && !h.speech(5600), "TV falando sem parar: toca pelo menos uma rodada antes de calar de novo")
    ok(h.canPlay(5600), "e continua tocando nesse meio-tempo")
    ok(h.speech(5800), "depois da rodada, pode calar de novo")
    ok(h.canPlay(9300), "e volta de novo")

    // TV falando o tempo todo por 3 minutos: o alarme precisa continuar tocando boa parte do tempo
    val tv = AlarmHold(3500, 1200)
    var tocando = 0; var t = 0L
    while (t < 180_000) { tv.speech(t); if (tv.canPlay(t)) tocando++; t += 100 }
    val frac = tocando / 1800.0
    ok(frac > 0.2, "com TV falando sem parar o alarme ainda toca %.0f%% do tempo (uma rodada a cada ~4,7 s)".format(frac * 100))

    val r = AlarmHold(3500, 1200)
    r.speech(0); r.reset()
    ok(r.canPlay(100) && r.speech(200), "alarme novo começa do zero")

    println(if (falhas == 0) "TODOS OS TESTES PASSARAM" else "$falhas FALHA(S)")
}
