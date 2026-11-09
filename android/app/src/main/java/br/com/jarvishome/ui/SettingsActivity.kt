package br.com.jarvishome.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import br.com.jarvishome.audio.AudioPlayer
import br.com.jarvishome.domain.ConnectionState
import br.com.jarvishome.domain.JLog
import br.com.jarvishome.domain.StateBus
import br.com.jarvishome.network.Backends
import br.com.jarvishome.network.HaDirectBackend
import br.com.jarvishome.service.JarvisForegroundService
import br.com.jarvishome.settings.Prefs
import br.com.jarvishome.wakeword.WakeWords
import br.com.jarvishome.wakeword.PersonalStore
import br.com.jarvishome.home.HomeApi
import br.com.jarvishome.home.HomeModel
import br.com.jarvishome.home.HomeParser
import java.util.Locale

class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    /** Aba onde os próximos campos entram (cada seção vai para a sua aba). */
    private lateinit var form: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var tabBar: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var bottomBar: LinearLayout
    private val tabs = LinkedHashMap<String, LinearLayout>()
    private var tabBox: LinearLayout? = null
    private val chips = HashMap<String, TextView>()
    private val handler = Handler(Looper.getMainLooper())
    private var player: AudioPlayer? = null

    private lateinit var urlField: EditText
    private lateinit var terminalField: EditText
    private lateinit var pipelineField: EditText
    private lateinit var tokenField: EditText
    private lateinit var repoField: EditText
    private lateinit var ghTokenField: EditText
    private lateinit var swAutoUpdate: Switch
    private lateinit var keywordsField: EditText
    private lateinit var connResult: TextView
    private lateinit var diag: TextView
    private lateinit var micBar: ProgressBar

    private lateinit var swDirect: Switch
    private lateinit var swEnabled: Switch
    private lateinit var swPersonal: Switch
    private var personalThreshold = 0.22f
    private lateinit var swCorrect: Switch
    private lateinit var swLocalLights: Switch
    private lateinit var swFloorPlan: Switch
    private lateinit var vocabField: EditText
    private lateinit var swWake: Switch
    private lateinit var swBeep: Switch
    private lateinit var swScreenOn: Switch
    private lateinit var swDim: Switch
    private lateinit var swMotion: Switch
    private lateinit var swClock: Switch
    private lateinit var swBoot: Switch
    private lateinit var swHome: Switch
    private lateinit var swCalendar: Switch
    private lateinit var swReminders: Switch
    private lateinit var swShowAgenda: Switch
    private lateinit var swShowLights: Switch
    private lateinit var swShowWeather: Switch
    private lateinit var swNight: Switch
    private lateinit var swSpeakNight: Switch
    private lateinit var sensorsField: EditText
    private var reminderLead = 0
    private var nightStart = 23
    private var nightEnd = 6
    private var tempLimit = 75
    private var sensitivity = 6
    private var silenceMs = 900
    private var volume = 90

    private val cyan = Color.rgb(34, 229, 255)
    private val homeAlias by lazy { ComponentName(this, "br.com.jarvishome.ui.HomeAlias") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        sensitivity = prefs.sensitivity
        silenceMs = prefs.silenceMs
        volume = prefs.volume
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(6, 8, 10))
        }
        // cabeçalho
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(4))
        }
        head.addView(TextView(this).apply {
            text = "CONFIGURAÇÕES"
            setTextColor(cyan)
            textSize = 15f
            letterSpacing = 0.25f
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(TextView(this).apply {
            text = "v" + (try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" })
            setTextColor(Color.argb(140, 200, 230, 235))
            textSize = 12f
        })
        head.addView(TextView(this).apply {
            text = "✕"
            setTextColor(Color.argb(170, 200, 230, 235))
            textSize = 18f
            setPadding(dp(18), dp(6), 0, dp(6))
            contentDescription = "Fechar"
            setOnClickListener { finish() }
        })
        root.addView(head)
        tabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(12), dp(12), dp(8)) }
        root.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabBar)
        }, LinearLayout.LayoutParams(-1, -2))
        scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), dp(24))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        bottomBar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(6), dp(20), dp(14)) }
        root.addView(bottomBar, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        TAB_ORDER.forEach { tab(it) }
        build()
        val update = intent?.getBooleanExtra(br.com.jarvishome.update.UpdateFlow.EXTRA_UPDATE, false) == true
        selectTab(if (update) "Sistema" else lastTab)
        if (update) br.com.jarvishome.update.UpdateFlow.run(this)
    }

    // ------------------------------------------------------------------ abas

    companion object {
        private val TAB_ORDER = listOf("Conexão", "Voz", "Comandos", "Agenda", "Tela", "Sistema")
        /** Em que aba fica cada seção. */
        private val SECTION_TAB = mapOf(
            "Home Assistant (rede local)" to "Conexão",
            "Assistente" to "Voz",
            "Chamada por voz: detector pessoal (recomendado)" to "Voz",
            "Chamada por voz: modelo em inglês (alternativo)" to "Voz",
            "Avançado: variações da palavra" to "Voz",
            "Comandos de luz" to "Comandos",
            "Entender melhor os comandos" to "Comandos",
            "Treino" to "Comandos",
            "Agenda" to "Agenda",
            "Avisos do Home Assistant" to "Agenda",
            "Informações na tela" to "Tela",
            "Planta da casa" to "Tela",
            "Modo noite" to "Tela",
            "Tela e terminal" to "Tela",
            "Diagnóstico" to "Sistema",
            "Atualizações" to "Sistema"
        )
        /** Volta na mesma aba ao reabrir (ex.: depois de treinar a voz). */
        private var lastTab = "Conexão"
    }

    private fun tab(name: String) {
        form = tabs.getOrPut(name) {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
            content.addView(box)
            val chip = TextView(this).apply {
                text = name
                textSize = 15f
                setPadding(dp(16), dp(9), dp(16), dp(9))
                setOnClickListener { selectTab(name) }
            }
            tabBar.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            chips[name] = chip
            box
        }
        tabBox = form
    }

    private fun selectTab(name: String) {
        val n = if (tabs.containsKey(name)) name else TAB_ORDER.first()
        lastTab = n
        for ((k, v) in tabs) v.visibility = if (k == n) View.VISIBLE else View.GONE
        for ((k, c) in chips) {
            val on = k == n
            c.setTextColor(if (on) Color.BLACK else cyan)
            c.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            c.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                if (on) setColor(cyan) else setStroke(dp(1), Color.argb(120, 34, 229, 255))
            }
        }
        scroll.scrollTo(0, 0)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Título da seção + um cartão onde entram os campos seguintes. */
    private fun section(title: String) {
        SECTION_TAB[title]?.let { tab(it) }
        val box = tabBox ?: form
        box.addView(TextView(this).apply {
            text = title.uppercase(Locale("pt", "BR"))
            setTextColor(cyan)
            textSize = 12f
            letterSpacing = 0.2f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(22), 0, dp(8))
        })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(14))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.rgb(13, 21, 25))
                setStroke(dp(1), Color.argb(40, 34, 229, 255))
            }
        }
        box.addView(card)
        form = card
    }

    private fun note(text: String): TextView {
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(Color.argb(170, 220, 235, 240))
            textSize = 12f
            setPadding(0, dp(2), 0, dp(6))
        }
        form.addView(tv)
        return tv
    }

    private fun field(label: String, value: String, hintText: String, type: Int): EditText {
        form.addView(TextView(this).apply {
            text = label
            setTextColor(Color.rgb(232, 246, 248))
            textSize = 14f
            setPadding(0, dp(8), 0, 0)
        })
        val et = EditText(this).apply {
            setText(value)
            hint = hintText
            inputType = type
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(94, 122, 128))
        }
        form.addView(et)
        return et
    }

    private fun switch(label: String, value: Boolean): Switch {
        val sw = Switch(this).apply {
            text = label
            isChecked = value
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, dp(10), 0, dp(10))
        }
        form.addView(sw)
        return sw
    }

    private fun slider(label: String, min: Int, max: Int, step: Int, value: Int, fmt: (Int) -> String, onChange: (Int) -> Unit) {
        val tv = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, dp(10), 0, 0)
            text = label + ": " + fmt(value)
        }
        form.addView(tv)
        val sb = SeekBar(this).apply {
            this.max = (max - min) / step
            progress = (value - min) / step
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = min + p * step
                    tv.text = label + ": " + fmt(v)
                    onChange(v)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        form.addView(sb)
    }

    private fun button(label: String, onClick: () -> Unit): Button {
        val b = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }
        form.addView(b, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        return b
    }

    private fun build() {
        section("Atualizações")
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        note("Versão instalada: $ver")
        button("Procurar atualização") {
            save()
            br.com.jarvishome.update.UpdateFlow.run(this)
        }
        swAutoUpdate = switch("Avisar quando sair versão nova", prefs.autoUpdate)
        repoField = field("Repositório no GitHub", prefs.updateRepo, "dono/repositorio",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        ghTokenField = field("Chave do GitHub (só se for privado)", "",
            if (prefs.updateToken.isNotEmpty()) "•••••• salva (deixe vazio para manter)" else "vazio = repositório público",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        note("O app baixa a versão nova e o Android pergunta “Instalar?”. Configurações, token e treino de voz continuam. " +
            "Na primeira vez o Android pede para permitir instalar a partir do Jarvis.")

        section("Home Assistant (rede local)")
        swDirect = switch("Conectar direto ao Home Assistant", prefs.directMode)
        urlField = field("Endereço", prefs.serverUrl, "192.168.18.108:8123",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val hasToken = prefs.token.isNotEmpty()
        tokenField = field("Token", "",
            if (hasToken) "•••••• salvo (deixe vazio para manter)" else "cole o token de longa duração",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        note("Modo direto: token de longa duração do usuário “jarvis” do Home Assistant (Perfil > Segurança). " +
            "Use um usuário SEM administrador. O token fica cifrado no Keystore do Android.")
        pipelineField = field("Pipeline (opcional)", prefs.pipelineId, "vazio = assistente preferido do HA",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        terminalField = field("Terminal (só com orquestrador)", prefs.terminalId, "redmi10", InputType.TYPE_CLASS_TEXT)
        note("Desligue “Conectar direto” apenas se usar o orquestrador opcional (porta 8765).")
        button("Testar conexão") { testConnection() }
        connResult = note("")

        section("Assistente")
        swEnabled = switch("Assistente ativo (desligado = microfone fechado)", prefs.assistantEnabled)
        swWake = switch("Ativar por voz", prefs.wakeWordEnabled)

        section("Chamada por voz: detector pessoal (recomendado)")
        swPersonal = switch("Reconhecer “" + PersonalStore.PHRASE + "” pela voz da casa", prefs.personalDetector)
        val personalInfo = note("")
        val personalSet = PersonalStore.load(this)
        personalThreshold = personalSet.threshold
        personalInfo.text = "Compara o som de cada fala com exemplos de quem mora aqui. Não depende de sotaque. " +
            "Exemplos: " + personalSet.templates.size +
            (if (PersonalStore.hasCustom(this)) " (treinados neste aparelho)." else " (da gravação de vocês pela casa).") +
            "\nPode chamar com pausa (“Hey Tony”… comando) ou tudo de uma vez (“Hey Tony, liga a luz”)."
        button("Treinar a minha voz (cada pessoa da casa)") {
            save()
            startActivity(Intent(this, VoiceTrainingActivity::class.java))
            finish()
        }
        slider("Exigência do detector pessoal", 14, 30, 1, (personalThreshold * 100).toInt(),
            { v -> String.format(Locale.US, "%.2f", v / 100f) + (if (v >= 25) "  (aceita mais, risco de ativar sozinho)" else if (v <= 17) "  (mais rígido)" else "") }) {
            personalThreshold = it / 100f
        }
        note("Valor maior = aciona com mais facilidade. Se não atender a primeira chamada, suba 0,01 por vez; " +
            "se ativar sozinho com TV ou conversa, desça. “Última fala” no diagnóstico mostra a distância de cada fala " +
            "(a chamada aciona quando fica abaixo deste valor).")
        button("Voltar aos exemplos de fábrica") {
            AlertDialog.Builder(this)
                .setMessage("Apagar os exemplos treinados neste aparelho e voltar aos da gravação de vocês?")
                .setPositiveButton("Apagar") { _, _ ->
                    PersonalStore.resetToFactory(this)
                    JarvisForegroundService.send(this, JarvisForegroundService.ACTION_RELOAD)
                    Toast.makeText(this, "Exemplos de fábrica restaurados", Toast.LENGTH_SHORT).show()
                    finish()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        section("Chamada por voz: modelo em inglês (alternativo)")
        note((if (prefs.personalDetector) "⚠ Desativado agora: o detector pessoal acima está ligado, então esta calibração não muda nada. " +
            "Para ensinar a sua voz, use “Treinar a minha voz” acima. " else "") +
            "Usado só com o detector pessoal desligado. Nos testes com a voz de vocês, reconheceu no máximo 2 de 13 chamadas.")
        val wordLabel = note("")
        fun refreshWord() {
            val p = WakeWords.current(prefs)
            wordLabel.text = "Palavra: “" + p.display + "”  ·  " +
                (if (prefs.calibratedWord == p.id) "calibrada para a sua voz ✓" else "ainda não calibrada")
        }
        refreshWord()
        button("Calibrar o modelo em inglês") {
            save()
            startActivity(Intent(this, CalibrationActivity::class.java))
            finish()
        }
        button("Trocar palavra de ativação") {
            val all = WakeWords.ALL
            AlertDialog.Builder(this)
                .setTitle("Palavra de ativação")
                .setItems(all.map { it.display + "  —  " + it.note }.toTypedArray()) { _, which ->
                    WakeWords.select(prefs, all[which])
                    keywordsField.setText(prefs.keywords)
                    refreshWord()
                    Toast.makeText(this, "Agora calibre a sua voz para “" + all[which].display + "”", Toast.LENGTH_LONG).show()
                }
                .show()
        }
        slider("Sensibilidade do wake word", 1, 10, 1, sensitivity,
            { v -> v.toString() + "  (limiar " + String.format(Locale.US, "%.2f", Prefs.thresholdFor(v)) + ")" }) { sensitivity = it }
        note("Se aciona sozinho com TV ou conversa, diminua. Se não reconhece você, aumente. " +
            "Depois de calibrar, cada variação já tem a sensibilidade ideal para a sua voz e este ajuste não se aplica a ela.")
        slider("Silêncio para encerrar o comando", 400, 2500, 100, silenceMs, { v -> "$v ms" }) { silenceMs = it }
        slider("Volume da resposta", 0, 100, 5, volume, { v -> "$v%" }) { volume = it }
        swBeep = switch("Bipe ao começar a ouvir", prefs.beep)

        section("Treino")
        note("Por voz: “Hey Tony, treino de 5 exercícios e 5 séries”, “pausa”, “continua”, “quanto falta?”, “para o treino”. " +
            "Ele avisa a série e o exercício em voz alta; a tela inicial mostra a fase e a contagem.")
        button("Ajustar o padrão do treino (tempos e quantidades)") {
            save()
            startActivity(Intent(this, WorkoutActivity::class.java))
        }

        section("Comandos de luz")
        swLocalLights = switch("Ligar e desligar luzes pelo próprio app", prefs.localLights)
        note("O app entende “liga/desliga/acende/apaga a luz do …” sozinho, tolerando erros do Whisper " +
            "(“deliga”, “luz do quatro”), e aciona a luz direto, sem depender das frases do Home Assistant. " +
            "Perguntas, porcentagem e cores continuam com o Home Assistant.")
        val nTrained = br.com.jarvishome.commands.LightCommands.decode(prefs.trainedCommands).size
        button("Treinar comandos de luz ($nTrained exemplos)") {
            save()
            startActivity(Intent(this, CommandTrainingActivity::class.java))
        }
        note("Cada pessoa grava os comandos do seu jeito; o app aprende como o Whisper escreve a voz de cada um.")

        section("Entender melhor os comandos")
        swCorrect = switch("Corrigir nomes da casa", prefs.correctText)
        note("Compara o que o Whisper ouviu com os nomes dos cômodos e dispositivos do Home Assistant " +
            "(lidos automaticamente) e corrige erros como “luz do zoquart” → “luz do quarto”. " +
            "Nomes lidos agora: " + HaDirectBackend.vocabulary.size + ".")
        form.addView(TextView(this).apply {
            text = "Palavras e frases extras (uma por linha)"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, dp(8), 0, 0)
        })
        vocabField = EditText(this).apply {
            setText(prefs.extraVocabulary)
            setTextColor(Color.WHITE)
            textSize = 14f
            minLines = 4
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        form.addView(vocabField)
        note("Ex.: nomes de scripts e comandos personalizados. Escreva do jeito que você fala.")

        section("Agenda")
        swCalendar = switch("Marcar e consultar a agenda por voz", prefs.calendarEnabled)
        val cals = HomeModel.calendars
        val writable = cals.filter { it.canCreate }
        val chosen = writable.firstOrNull { it.entityId == prefs.calendarWrite } ?: writable.firstOrNull()
        val agendaInfo = note(
            if (cals.isEmpty()) "Nenhuma agenda encontrada no Home Assistant. Adicione a integração “Calendário local” " +
                "(funciona sem internet) ou “Google Calendar” (precisa de internet)."
            else "Agendas: " + cals.joinToString { it.name + if (it.canCreate) "" else " (só leitura)" } +
                "\nCompromissos novos vão para: " + (chosen?.name ?: "nenhuma aceita compromissos novos")
        )
        if (writable.size > 1) button("Escolher a agenda dos compromissos novos") {
            AlertDialog.Builder(this).setItems(writable.map { it.name }.toTypedArray()) { _, i ->
                prefs.calendarWrite = writable[i].entityId
                agendaInfo.text = "Compromissos novos vão para: " + writable[i].name
            }.show()
        }
        button("Abrir a agenda") { startActivity(Intent(this, AgendaActivity::class.java)) }
        button("Testar agenda (diagnóstico)") {
            save()
            val p = prefs
            val dlg = AlertDialog.Builder(this).setTitle("Diagnóstico da agenda").setMessage("Testando…")
                .setPositiveButton("Fechar", null).show()
            Thread {
                val r = br.com.jarvishome.home.HomeMonitor.diagnose(p)
                JLog.i("Diagnóstico da agenda:\n$r")
                handler.post { dlg.setMessage(r) }
                br.com.jarvishome.home.HomeMonitor.refreshAgendaSoon()
            }.start()
        }
        swReminders = switch("Falar os lembretes na hora", prefs.agendaReminders)
        reminderLead = prefs.reminderLeadMin
        slider("Avisar antes do compromisso", 0, 60, 5, reminderLead,
            { v -> if (v == 0) "na hora" else "$v min antes" }) { reminderLead = it }
        note("Exemplos: “Hey Tony, marca dentista amanhã às 15h”, “me lembra de tirar o lixo amanhã de manhã”, " +
            "“o que eu tenho amanhã?”, “desmarca o último compromisso”.")

        section("Avisos do Home Assistant")
        note(if (HomeModel.hasAnnounceEntity) "✓ Auxiliar input_text.jarvis_aviso encontrado. Qualquer automação que escrever nele " +
                "faz o Jarvis mostrar e falar o aviso."
            else "Crie o auxiliar: Configurações > Dispositivos e serviços > Auxiliares > Criar auxiliar > Texto, " +
                "nome “Jarvis aviso”, comprimento máximo 255. Depois, nas automações, use a ação " +
                "“Texto: definir valor” nele com a frase a falar.")
        button("Testar aviso") {
            val p = prefs
            Thread {
                val msg = try {
                    val api = HomeApi(p.serverUrl, p.token)
                    if (api.state(HomeParser.ANNOUNCE_ENTITY) == null) "O auxiliar input_text.jarvis_aviso ainda não existe no Home Assistant."
                    else { api.setText(HomeParser.ANNOUNCE_ENTITY, "Teste de aviso do Jarvis Home."); "Enviado. Em até 5 segundos o Jarvis fala." }
                } catch (e: Exception) { "Falhou: " + (e.message ?: e.javaClass.simpleName) }
                handler.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
            }.start()
        }

        section("Informações na tela")
        swShowAgenda = switch("Mostrar próximos compromissos", prefs.showAgenda)
        swShowLights = switch("Mostrar luzes (tocar liga e desliga)", prefs.showLights)
        swShowWeather = switch("Mostrar clima (some sozinho sem internet)", prefs.showWeather)
        tempLimit = prefs.serverTempLimit
        slider("Alerta de temperatura do servidor", 50, 95, 5, tempLimit, { v -> "$v °C" }) { tempLimit = it }
        note("Sensores do servidor encontrados: " + HomeModel.serverInfo.joinToString("; ").ifEmpty { "nenhum" } +
            ". O alerta também aparece se o disco passar de 90%.")
        sensorsField = field("Sensores do servidor (opcional)", prefs.serverSensors,
            "vazio = detectar sozinho (ex.: sensor.processor_temperature)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)

        section("Planta da casa")
        swFloorPlan = switch("Mostrar a planta 3D na tela inicial (em espera)", prefs.showFloorPlan)
        note("Cada cômodo acende quando a luz dele está ligada; toque para ligar/desligar. " +
            "Toque e segure num cômodo para escolher quais luzes/relés do Home Assistant ele controla " +
            "(sem escolha, o app procura pelo nome: sala, jantar, cozinha, quarto…).")

        section("Modo noite")
        swNight = switch("Tela no mínimo à noite (o primeiro toque só acende)", prefs.nightMode)
        nightStart = prefs.nightStart
        nightEnd = prefs.nightEnd
        slider("Começa às", 18, 23, 1, nightStart.coerceIn(18, 23), { v -> "${v}h" }) { nightStart = it }
        slider("Termina às", 4, 9, 1, nightEnd.coerceIn(4, 9), { v -> "${v}h" }) { nightEnd = it }
        swSpeakNight = switch("Falar avisos e lembretes durante a noite", prefs.speakAtNight)
        note("Desligado: à noite os avisos só aparecem na tela.")

        section("Tela e terminal")
        swScreenOn = switch("Manter tela ligada", prefs.keepScreenOn)
        swDim = switch("Brilho mínimo (modo noturno)", prefs.dimScreen)
        swMotion = switch("Reduzir animações", prefs.reduceMotion)
        swClock = switch("Mostrar relógio", prefs.showClock)
        swBoot = switch("Iniciar com o aparelho", prefs.startOnBoot)
        swHome = switch("Modo terminal (Jarvis como tela inicial)",
            packageManager.getComponentEnabledSetting(homeAlias) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
        note("Recomendado para o Redmi fixo na parede: após ativar, aperte o botão Home e escolha " +
            "“Jarvis Home” como padrão. Assim o Jarvis volta sozinho depois de reiniciar, já com o microfone liberado.")

        section("Diagnóstico")
        diag = note("")
        micBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        form.addView(micBar, LinearLayout.LayoutParams(-1, dp(14)))
        note("Barra = nível do microfone em tempo real. Fale “Hey Tony” e veja se “Última ativação” muda.")
        button("Simular ativação (sem wake word)") {
            JarvisForegroundService.send(this, JarvisForegroundService.ACTION_LISTEN)
            finish()
        }
        button("Testar alto-falante") {
            val p = player ?: AudioPlayer(this).also { player = it }
            p.beep(true)
            handler.postDelayed({ p.speakLocal("Teste de áudio do Jarvis Home.") {} }, 600)
        }
        button("Desativar otimização de bateria") { openBatterySettings() }
        note("No Redmi/MIUI também ative: Configurações > Apps > Jarvis Home > Início automático, " +
            "e em Economia de bateria escolha “Sem restrições”. Trave o app na tela de recentes (cadeado).")
        button("Ver logs técnicos") { showLogs() }
        button("Últimos comandos (o que ele ouviu)") { showCommands() }
        val crash = br.com.jarvishome.domain.CrashLog.last(this)
        if (crash != null) button("Ver a última vez que o app fechou sozinho") {
            AlertDialog.Builder(this).setTitle("Última falha")
                .setView(ScrollView(this).apply {
                    addView(TextView(this@SettingsActivity).apply {
                        text = crash; setTextColor(Color.WHITE); textSize = 11f; setTextIsSelectable(true)
                        setPadding(dp(16), dp(12), dp(16), dp(12))
                    })
                })
                .setPositiveButton("Fechar", null)
                .setNeutralButton("Copiar") { _, _ ->
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("jarvis-falha", crash))
                    Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Apagar") { _, _ -> br.com.jarvishome.domain.CrashLog.clear(this) }
                .show()
        }

        section("Avançado: variações da palavra")
        keywordsField = EditText(this).apply {
            setText(prefs.keywords)
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            minLines = 4
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        form.addView(keywordsField)
        note("Tokens BPE do modelo (formato sherpa-onnx). Grave sua voz dizendo “Jarvis” e rode " +
            "tools/calibrar_wakeword.py no servidor: ele gera as linhas certas para colar aqui.")
        button("Restaurar padrão da palavra") {
            keywordsField.setText(WakeWords.current(prefs).defaultLines.joinToString("\n"))
            prefs.keywordBoost = 1.5f
            prefs.calibratedWord = ""
        }

        val save = Button(this).apply {
            text = "Salvar"
            isAllCaps = false
            setTextColor(Color.BLACK)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(cyan)
            }
            setOnClickListener {
                save()
                finish()
            }
        }
        bottomBar.addView(save, LinearLayout.LayoutParams(-1, -2))
    }

    private fun save() {
        prefs.autoUpdate = swAutoUpdate.isChecked
        prefs.updateRepo = repoField.text.toString().ifBlank { "duduferaso/jarvis_home" }
        ghTokenField.text.toString().trim().let { if (it == "-") prefs.updateToken = "" else if (it.isNotEmpty()) prefs.updateToken = it }
        prefs.directMode = swDirect.isChecked   // antes da URL: define a porta padrão
        prefs.serverUrl = urlField.text.toString()
        prefs.pipelineId = pipelineField.text.toString()
        prefs.terminalId = terminalField.text.toString()
        val tok = tokenField.text.toString().trim()
        if (tok.isNotEmpty()) prefs.token = tok
        prefs.sensitivity = sensitivity
        prefs.silenceMs = silenceMs
        prefs.volume = volume
        prefs.wakeWordEnabled = swWake.isChecked
        prefs.beep = swBeep.isChecked
        prefs.keepScreenOn = swScreenOn.isChecked
        prefs.dimScreen = swDim.isChecked
        prefs.reduceMotion = swMotion.isChecked
        prefs.showClock = swClock.isChecked
        prefs.startOnBoot = swBoot.isChecked
        prefs.keywords = keywordsField.text.toString()
        prefs.personalDetector = swPersonal.isChecked
        val ps = PersonalStore.load(this)
        if (Math.abs(ps.threshold - personalThreshold) > 0.0005f) {
            ps.threshold = personalThreshold
            PersonalStore.save(this, ps)
            JLog.i("Exigência do detector pessoal: " + String.format(Locale.US, "%.2f", personalThreshold))
        }
        prefs.correctText = swCorrect.isChecked
        prefs.localLights = swLocalLights.isChecked
        prefs.showFloorPlan = swFloorPlan.isChecked
        prefs.extraVocabulary = vocabField.text.toString()
        prefs.calendarEnabled = swCalendar.isChecked
        prefs.agendaReminders = swReminders.isChecked
        prefs.reminderLeadMin = reminderLead
        prefs.showAgenda = swShowAgenda.isChecked
        prefs.showLights = swShowLights.isChecked
        prefs.showWeather = swShowWeather.isChecked
        prefs.serverTempLimit = tempLimit
        prefs.serverSensors = sensorsField.text.toString()
        prefs.nightMode = swNight.isChecked
        prefs.nightStart = nightStart
        prefs.nightEnd = nightEnd
        prefs.speakAtNight = swSpeakNight.isChecked
        packageManager.setComponentEnabledSetting(homeAlias,
            if (swHome.isChecked) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP)
        val wasEnabled = prefs.assistantEnabled
        JarvisForegroundService.send(this, JarvisForegroundService.ACTION_RELOAD)
        if (wasEnabled != swEnabled.isChecked) {
            JarvisForegroundService.send(this,
                if (swEnabled.isChecked) JarvisForegroundService.ACTION_WAKE else JarvisForegroundService.ACTION_SLEEP)
        }
        JLog.i("Configurações salvas")
        Toast.makeText(this, "Configurações salvas", Toast.LENGTH_SHORT).show()
    }

    private fun testConnection() {
        save()
        connResult.text = "Testando…"
        Thread {
            val r = Backends.create(prefs).health()
            StateBus.setConnection(r)
            val msg = when (r) {
                ConnectionState.CONNECTED -> "✓ Home Assistant respondendo e token aceito"
                ConnectionState.HA_DOWN -> if (prefs.directMode) "O endereço respondeu, mas não parece ser o Home Assistant"
                    else "Orquestrador OK, mas ele não alcança o Home Assistant (confira HA_URL e HA_TOKEN)"
                ConnectionState.UNAUTHORIZED -> "Recusado pelo Home Assistant"
                ConnectionState.NOT_CONFIGURED -> "Preencha o endereço e o token"
                else -> "Sem resposta de " + prefs.serverUrl + ". Confira o IP e se o celular está no mesmo Wi-Fi"
            }
            val detail = if (prefs.directMode) HaDirectBackend.lastDetail else ""
            handler.post { connResult.text = if (detail.isNotEmpty()) msg + "\n" + detail else msg }
        }.start()
    }

    private fun openBatterySettings() {
        val pm = getSystemService(PowerManager::class.java)
        try {
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                return
            }
        } catch (e: Exception) {
            JLog.w("Diálogo de bateria indisponível", e)
        }
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun showCommands() {
        val text = br.com.jarvishome.domain.CommandHistory.dump().ifEmpty { "(nenhum comando desde que o app abriu)" }
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextIsSelectable(true)
        }
        val sv = ScrollView(this)
        sv.addView(tv)
        AlertDialog.Builder(this)
            .setTitle("Últimos comandos")
            .setView(sv)
            .setPositiveButton("Fechar", null)
            .setNeutralButton("Copiar") { _, _ ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("jarvis-comandos", text))
                Toast.makeText(this, "Comandos copiados", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showLogs() {
        val text = JLog.dump().ifEmpty { "(sem eventos ainda)" }
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextIsSelectable(true)
        }
        val sv = ScrollView(this)
        sv.addView(tv)
        AlertDialog.Builder(this)
            .setTitle("Logs técnicos")
            .setView(sv)
            .setPositiveButton("Fechar", null)
            .setNeutralButton("Copiar") { _, _ ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("jarvis-logs", text))
                Toast.makeText(this, "Logs copiados", Toast.LENGTH_SHORT).show()
            }
            .show()
        sv.post { sv.fullScroll(View.FOCUS_DOWN) }
    }

    private val diagLoop = object : Runnable {
        override fun run() {
            diag.text = "Serviço: " + (if (StateBus.serviceRunning) "rodando" else "parado") +
                "\nEstado: " + StateBus.state.label +
                "\nConexão: " + StateBus.connection.label +
                "\nWake word: " + StateBus.wakeWordStatus +
                "\nÚltima ativação: " + StateBus.lastWakeWord +
                (if (StateBus.lastScore.isNaN()) "" else "\nÚltima fala: distância " +
                    String.format(Locale.US, "%.3f", StateBus.lastScore) +
                    (if (StateBus.lastScore <= personalThreshold) " (acionaria)" else " (não acionaria)"))
            micBar.progress = (StateBus.micLevel * 100).toInt()
            handler.postDelayed(this, 150)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(diagLoop)
    }

    override fun onPause() {
        handler.removeCallbacks(diagLoop)
        super.onPause()
    }

    override fun onDestroy() {
        player?.release()
        super.onDestroy()
    }
}
