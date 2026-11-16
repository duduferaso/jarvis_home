import br.com.jarvishome.commands.Lists
import br.com.jarvishome.commands.Lists.Cmd
import br.com.jarvishome.commands.Lists.Which
import org.json.JSONArray
import org.json.JSONObject

var fl = 0
fun ok(c: Boolean, m: String) { if (!c) fl++; println((if (c) "PASSOU " else "FALHOU ") + m) }

fun main() {
    fun p(s: String) = Lists.parse(s)
    fun add(s: String, items: List<String>, w: Which = Which.SHOPPING) { val c = p(s); ok(c == Cmd.Add(items, w), "\"$s\" → $c") }
    add("coloca leite na lista de compras", listOf("Leite"))
    add("Coloca leite, pão e ovos na lista de compras.", listOf("Leite", "Pão", "Ovos"))
    add("adiciona na lista de compras sabão em pó", listOf("Sabão em pó"))
    add("põe 2 dúzias de ovos na lista", listOf("2 dúzias de ovos"))
    add("bota o detergente na minha lista do mercado", listOf("Detergente"))
    add("anota pagar a conta de luz na lista de tarefas", listOf("Pagar a conta de luz"), Which.TASKS)
    add("adiciona arroz e feijão na lista", listOf("Arroz", "Feijão"))
    add("acrescenta também queijo na lista", listOf("Queijo"))
    ok(p("o que tem na lista?") == Cmd.Read(Which.SHOPPING), "ler lista")
    ok(p("o que tem na lista de compras") == Cmd.Read(Which.SHOPPING), "ler lista de compras")
    ok(p("lê a lista de tarefas") == Cmd.Read(Which.TASKS), "ler tarefas")
    ok(p("o que falta comprar?") == Cmd.Read(Which.SHOPPING), "o que falta comprar")
    ok(p("me fala a lista de compras") == Cmd.Read(Which.SHOPPING), "me fala a lista")
    ok(p("tira o leite da lista") == Cmd.Remove(listOf("Leite"), Which.SHOPPING, false), "tira da lista: ${p("tira o leite da lista")}")
    ok(p("remove pão e ovos da lista de compras") == Cmd.Remove(listOf("Pão", "Ovos"), Which.SHOPPING, false), "remove dois")
    ok(p("comprei leite") == Cmd.Remove(listOf("Leite"), Which.SHOPPING, true), "comprei")
    ok(p("já comprei pão e manteiga") == Cmd.Remove(listOf("Pão", "Manteiga"), Which.SHOPPING, true), "já comprei dois: ${p("já comprei pão e manteiga")}")
    ok(p("fiz pagar a conta de luz da lista de tarefas") == Cmd.Remove(listOf("Pagar a conta de luz"), Which.TASKS, true), "fiz tarefa")
    for (s in listOf("liga a luz da sala", "timer de 5 minutos", "marca dentista amanhã às 15h", "tira a luz", "vai chover amanhã",
            "o que eu tenho amanhã", "apaga a luz da cozinha", "desliga tudo", "lista"))
        ok(p(s) == null, "não é lista: \"$s\"")

    val items = listOf(Lists.Item("1", "Leite"), Lists.Item("2", "Pão francês"), Lists.Item("3", "Ovos"), Lists.Item("4", "Arroz"))
    ok(Lists.match("leite", items)?.uid == "1", "acha leite")
    ok(Lists.match("pão", items)?.uid == "2", "\"pão\" acha \"Pão francês\"")
    ok(Lists.match("ovo", items)?.uid == "3", "\"ovo\" acha \"Ovos\"")
    ok(Lists.match("arros", items)?.uid == "4", "\"arros\" acha \"Arroz\"")
    ok(Lists.match("banana", items) == null, "banana não está")

    val states = JSONArray("""[{"entity_id":"todo.shopping_list","state":"3","attributes":{"friendly_name":"Lista de compras"}},
      {"entity_id":"todo.tarefas","state":"1","attributes":{"friendly_name":"Tarefas"}},{"entity_id":"light.x","state":"on","attributes":{}}]""")
    val lists = Lists.listsFrom(states)
    ok(lists.size == 2, "duas listas")
    ok(Lists.pick(Which.SHOPPING, lists, "")?.entityId == "todo.shopping_list", "compras pelo nome")
    ok(Lists.pick(Which.TASKS, lists, "")?.entityId == "todo.tarefas", "tarefas pelo nome")
    ok(Lists.pick(Which.SHOPPING, lists, "todo.tarefas")?.entityId == "todo.tarefas", "escolha das configurações vale")
    val resp = JSONObject("""{"service_response":{"todo.shopping_list":{"items":[{"summary":"Leite","uid":"a","status":"needs_action"},{"summary":"Pão","uid":"b","status":"completed"}]}}}""")
    ok(Lists.itemsFrom(resp, "todo.shopping_list").map { it.summary } == listOf("Leite"), "só pendentes")

    println("   " + Lists.readSpeech(Which.SHOPPING, items))
    ok(Lists.readSpeech(Which.SHOPPING, items) == "Na lista de compras tem 4 itens: leite, pão francês, ovos e arroz.", "fala da lista")
    ok(Lists.readSpeech(Which.TASKS, emptyList()) == "A lista de tarefas está vazia.", "lista vazia")
    ok(Lists.addSpeech(Which.SHOPPING, listOf("Leite", "Pão"), listOf("Ovos")) == "Coloquei leite e pão na lista de compras. Ovos já estava na lista.", "fala de adicionar")
    ok(Lists.removeSpeech(Which.SHOPPING, listOf("Leite"), listOf("Banana"), true) == "Marquei como comprado: leite. Não achei banana na lista de compras.", "fala de comprado")
    val tony = listOf("Hey Tony", "Tony", "Toni", "Tonie", "Tohny", "Toney", "Tonee", "Tawny", "Toh nee", "Dony", "Tonia")
    for ((heard, want) in listOf("Hey Tony, coloca ovos e manteiga na lista de compras." to "coloca ovos",
            "Tony, comprei ovos." to "comprei ovos", "Ei Toni, o que tem na lista?" to "o que tem")) {
        val st = br.com.jarvishome.network.TextCorrector.stripWakeWord(heard, tony)
        ok(st.lowercase().startsWith(want), "chamada tirada sem comer o comando: \"$heard\" → \"$st\"")
    }
    println(if (fl == 0) "TODOS OS TESTES PASSARAM" else "$fl FALHA(S)")
}
