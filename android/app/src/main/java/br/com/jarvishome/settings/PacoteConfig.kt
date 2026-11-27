package br.com.jarvishome.settings

import br.com.jarvishome.home.Planta
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pacote de configuração do Home Control (formato 1): a casa inteira num arquivo, para o app novo
 * (ha_local_assistant) importar. Especificação: docs/pacote-configuracao.md no ha_local_assistant.
 *
 * Lógica pura (sem Android): recebe os ajustes já com os valores padrão aplicados (chaves das Prefs),
 * os arquivos de voz e a planta. Testada em tests/jvm_pacote/PacoteConfigTest.kt.
 */
object PacoteConfig {
    const val TIPO = "home-control-pacote"
    const val FORMATO = 1
    /** Formato dos arquivos de exemplos de voz (o mesmo vozes.tpl do detector pessoal). */
    const val FORMATO_VOZ = "jarvis-tpl-1"

    class Fonte(
        val ajustes: Map<String, Any?>,
        val arquivos: Map<String, ByteArray>,
        val planta: Planta.Casa?,
        val versaoApp: String,
        val criadoEm: String,
        val token: String? = null
    )

    private fun b64(b: ByteArray) = java.util.Base64.getEncoder().encodeToString(b)

    private fun arr(txt: String): JSONArray = try {
        // org.json (Android) aceita o texto direto; o stub dos testes também
        val o = JSONObject("{\"a\":" + txt.ifBlank { "[]" } + "}")
        o.optJSONArray("a") ?: JSONArray()
    } catch (e: Exception) { JSONArray() }

    fun montar(f: Fonte): JSONObject {
        val a = f.ajustes
        fun b(k: String) = a[k] as? Boolean ?: false
        fun i(k: String) = (a[k] as? Number)?.toInt() ?: 0
        fun s(k: String) = a[k]?.toString() ?: ""

        val conexao = JSONObject().put("endereco", s("server_url")).put("assistente", s("pipeline_id"))
        f.token?.takeIf { it.isNotEmpty() }?.let { conexao.put("token", it) }

        val voz = JSONObject().put("formatoExemplos", FORMATO_VOZ)
        f.arquivos["personal/vozes.tpl"]?.let { voz.put("exemplos", b64(it)) }
        f.arquivos["personal/aprendidos.tpl"]?.let { voz.put("aprendidos", b64(it)) }

        val modulos = JSONObject()
            .put("luzes", JSONObject().put("ligado", b("local_lights")).put("naTela", b("show_lights"))
                .put("comandosTreinados", arr(s("trained_commands")))
                .put("corretor", JSONObject().put("ligado", b("correct_text")).put("vocabularioExtra", s("extra_vocabulary"))))
            .put("planta", JSONObject().put("ligado", f.planta != null).put("naTela", b("show_floor_plan")).apply {
                f.planta?.let { put("planta", Planta.toJson(it)) }
            })
            .put("agenda", JSONObject().put("ligado", b("calendar_enabled")).put("naTela", b("show_agenda"))
                .put("agendaNova", s("calendar_write")).put("lembretes", b("agenda_reminders")).put("antecedenciaMin", i("reminder_lead")))
            .put("listas", JSONObject().put("ligado", b("lists_enabled")).put("compras", s("shopping_list")).put("tarefas", s("tasks_list")))
            .put("clima", JSONObject().put("ligado", true).put("naTela", b("show_weather")))
            .put("timers", JSONObject().put("ligado", true))
            .put("treino", JSONObject().put("ligado", true).put("minutos", i("workout_min")).put("execucaoSeg", i("workout_work"))
                .put("descansoSeg", i("workout_rest")).put("series", i("workout_series")).put("exercicios", i("workout_count"))
                .put("listaExercicios", s("workout_exercises")))
            .put("avisos", JSONObject().put("ligado", true))

        return JSONObject()
            .put("tipo", TIPO).put("formato", FORMATO).put("criadoEm", f.criadoEm)
            .put("origem", JSONObject().put("app", "JarvisHome").put("versao", f.versaoApp))
            .put("conexao", conexao)
            .put("ativacao", JSONObject().put("frase", "Hey Tony").put("aprenderComUso", b("learn_from_success")))
            .put("voz", voz)
            .put("modulos", modulos)
            .put("som", JSONObject().put("bipe", b("beep")).put("volume", i("volume")).put("silencioMs", i("silence_ms")))
            .put("noite", JSONObject().put("ligado", b("night_mode")).put("inicio", i("night_start")).put("fim", i("night_end"))
                .put("respostasBaixas", b("quiet_at_night")).put("volume", i("night_volume")).put("falarAvisos", b("speak_at_night")))
            .put("tela", JSONObject().put("manterLigada", b("keep_screen_on")).put("brilhoMinimo", b("dim_screen"))
                .put("relogio", b("show_clock")).put("reduzirMovimento", b("reduce_motion")))
            .put("sistema", JSONObject().put("iniciarAoLigar", b("start_on_boot")).put("atualizarSozinho", b("auto_update")))
            .put("aprendizado", JSONObject().put("correcoes", arr(s("learned_fixes"))))
            .put("extras", JSONObject().put("servidor", JSONObject().put("limiteTemperatura", i("server_temp_limit")).put("sensores", s("server_sensors"))))
    }

    /** Confere se o texto é um pacote que este formato entende. Lança com mensagem clara se não for. */
    fun conferir(o: JSONObject) {
        require(o.optString("tipo") == TIPO) { "o arquivo não é um pacote do Home Control" }
        val f = o.optInt("formato", 0)
        require(f in 1..FORMATO) { "pacote feito por uma versão mais nova; atualize o app" }
    }
}
