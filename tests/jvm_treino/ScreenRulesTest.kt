import br.com.jarvishome.commands.ScreenRules

// Tela "hibernando" no meio das séries do treino (bug da 1.15.11).
fun main() {
    var falhas = 0
    fun ok(c: Boolean, m: String) { if (!c) falhas++; println((if (c) "PASSOU " else "FALHOU ") + m) }
    val now = 1_000_000L
    ok(ScreenRules.dim(true, now, now - 1, quiet = true, workoutActive = false, ringing = false), "noite, casa quieta, sem toque há 1 min: escurece")
    ok(!ScreenRules.dim(true, now, now - 1, quiet = true, workoutActive = true, ringing = false), "noite com treino em andamento: NÃO escurece")
    ok(!ScreenRules.dim(true, now, now - 1, quiet = true, workoutActive = false, ringing = true), "alarme tocando: não escurece")
    ok(!ScreenRules.dim(true, now, now + 5000, quiet = true, workoutActive = false, ringing = false), "logo depois de tocar na tela: não escurece")
    ok(!ScreenRules.dim(true, now, now - 1, quiet = false, workoutActive = false, ringing = false), "assistente ouvindo/falando: não escurece")
    ok(!ScreenRules.dim(false, now, now - 1, quiet = true, workoutActive = false, ringing = false), "de dia: não escurece")
    ok(ScreenRules.keepOn(false, true), "treino mantém a tela ligada mesmo com \"Manter tela ligada\" desligado")
    ok(!ScreenRules.keepOn(false, false) && ScreenRules.keepOn(true, false), "sem treino, vale a configuração")
    println(if (falhas == 0) "TODOS OS TESTES PASSARAM" else "$falhas FALHA(S)")
}
