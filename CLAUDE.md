# Jarvis Home — guia para o Claude Code

Assistente de voz da casa, em português, rodando num **Redmi 10 fixo na parede** (Android 11, arm64).
Ele fala **direto com o Home Assistant** do homelab, sem servidor próprio no meio.
O app é este repositório. A versão atual é a **1.15.6**, com versionCode 47.

> O `README.md` é da v1.5 e está desatualizado. Vale este arquivo.

---

## 1. Visão geral

```
"Hey Tony" ──► detector pessoal no celular (Silero VAD + DTW contra exemplos de voz da casa)
                 │  bipe; o VAD de energia do app decide o fim da fala
                 ▼
      áudio 16 kHz ──WebSocket──► Home Assistant  assist_pipeline/run  stt→stt  (faster-whisper)
                 ▼  texto
      o APP decide:  desfazer · timers/alarmes · treino intervalado · listas (todo.*) ·
                     previsão (weather.*) · agenda (calendar.*) · correções aprendidas ·
                     luzes (light.* / switch.*)  ──REST──► homeassistant.turn_on/turn_off
                 │  o que sobrar
                 ▼
      Home Assistant  assist_pipeline/run  intent→tts  (agente de conversa do HA)
                 ▼
      resposta falada SEMPRE com o TTS do HA (assist_pipeline tts→tts; voz Android só como reserva)
```

- **Palavra de ativação:** só "Hey Tony", pelo detector pessoal. O modelo em inglês (sherpa KWS) foi removido na 1.14.0.
- **Tela:**
  - um rosto animado;
  - uma planta 3D isométrica da casa, em que tocar no cômodo acende ou apaga a luz;
  - botões de luz, clima, agenda, relógio, timers e o treino.
- **Atualização:** o app se atualiza sozinho pelo GitHub (seção 6).

## 2. O que o app espera do Home Assistant

O endereço é `http://192.168.18.108:8123`. O app usa o usuário **`jarvis`, sem administrador**, com um token de longa duração digitado no celular.

| Recurso no HA | Para quê | Detalhes |
|---|---|---|
| **Assistente (pipeline)** | Transcrição, intenção e voz | Usa o preferido, ou o id configurado no app. Hoje: STT faster-whisper (pt), TTS piper pt_BR (voz faber), agente Home Assistant pt-BR. A voz das respostas é a do TTS desse pipeline. |
| `light.*` e `switch.*` | Luzes | `light.*` sempre conta como luz. `switch.*` só conta se o nome tiver "luz", "luzes", "lâmpada" ou "ilumina" (regra única em `LightCommands.isLight`). |
| `weather.*` | Clima na tela e previsão por voz | Usa o primeiro disponível. A previsão vem de `weather.get_forecasts` (type `daily`, com `?return_response`). Hoje o HA não tem integração de clima: falta adicionar a **Met.no**. |
| `todo.*` | Lista de compras e de tarefas | `todo.get_items` / `add_item` / `update_item` (status `completed`) / `remove_item`. A lista é escolhida pelo nome ("compras", "tarefas") ou nas configurações. Precisa da integração **Lista de compras** e/ou **Tarefas locais**. |
| `calendar.*` | Agenda por voz e na tela | Lê com `GET /api/calendars/<id>?start&end`. Cria com `calendar.create_event`. Apaga pelo WebSocket com `calendar/event/delete`. |
| `input_text.jarvis_aviso` | Avisos falados vindos do HA | Uma automação escreve um texto ali; o app mostra, fala e limpa o campo (`input_text.set_value ""`). O tamanho máximo do auxiliar deve ser 255. |
| Scripts e automações com nome `Voz: frase` | Vocabulário | O prefixo `voz:`, `comando:` ou `jarvis:` faz a frase entrar no corretor de texto do app. Útil para comandos personalizados que o agente do HA executa. |
| `config/area_registry/list` (WS) | Nomes de cômodos | Também vão para o vocabulário do corretor. |

**Como o app chama o HA:**
- **REST:** `GET /api/states`, `GET /api/states/<id>`, `POST /api/services/<domínio>/<serviço>` e `/api/calendars/...`.
- **WebSocket:** `/api/websocket`, com `auth`, `assist_pipeline/run`, `config/area_registry/list` e `calendar/event/delete`. Os ids das mensagens têm de ser crescentes em cada conexão.
- **Permissões:** como o usuário `jarvis` não é admin, use só chamadas que um usuário comum pode fazer.

### O que dá para melhorar só no homelab, sem mexer no app

1. **Modelo do Whisper:** trocar de `tiny` para `base` ou `small` no add-on faster-whisper é o que mais melhora o entendimento. Se a versão do add-on aceitar prompt inicial, use algo como "Hey Tony, liga a luz da sala, desliga a luz do quarto de visita".
2. **Integração Met.no:** sem ela, a previsão do tempo responde "Não tem nenhuma entidade de clima".
3. **Lista de compras / Tarefas locais:** ativam os comandos de lista.
4. **`input_text.jarvis_aviso`:** cria o canal de avisos falados (lembretes, alarmes, "a máquina terminou").
5. **Nomes das luzes:** nomes em português ("Luz da Sala") ajudam. A voz também entende os cômodos da planta (seção 4) pelos relés escolhidos no app.
6. **Mi Box (Android TV):** a integração **Android TV Remote** criaria `media_player`/`remote` para abrir YouTube ou Prime Music. É um pedido do usuário e ainda não está implementado no app.

## 3. Fluxo de um comando (detalhes que importam)

- **`JarvisForegroundService`** mantém o microfone (AudioRecord 16 kHz mono, quadros de 100 ms) e a máquina de estados: IDLE → LISTENING → BUSY → RESPONDING → IDLE.
- **`PersonalWakeWord`** decide se a chamada veio junto com o comando ou com pausa:
  - **"frase única"** ("Hey Tony, liga a luz"): pelo menos 0,7 s de fala com volume perto do da chamada. O áudio da chamada vai junto para o Whisper.
  - **"abre o microfone"**: toca o bipe e só o que vier depois vai para o Whisper.
  - Nunca mande a chamada + pausa + comando juntos: o Whisper devolve só a chamada. Isso causou o bug do "bipe duplo", corrigido na 1.15.2.
- **Chamada no fim da fala:** se alguém ou a TV falava logo antes (menos de 0,3 s de pausa), o Silero junta tudo num trecho e o "Hey Tony" não fica no começo. Por isso, trechos maiores que 1,9 s também são comparados pelo fim (`SegmentEnd`, exemplos de trás para frente, limite ×0,95). Nas gravações da casa isso passou de 0/13 para 12/13, sem disparos falsos (`tests/jvm_pessoal/ChamadaEmendadaTest.kt`, com as gravações fora do repositório).
- **Treino de voz** (`VoiceTrainingActivity`): cada gravação de 2,4 s é recortada pelo Silero como ao vivo (`TakeTrim`: 0,15 s antes + o maior trecho de fala, até 1,9 s). Sem isso, treinar longe ou com TV gerava exemplos cheios de fundo. Treinar no lugar e no barulho em que a pessoa chama é o que mais melhora a chamada difícil: na simulação de 3 m com TV, a distância caiu de ~0,20–0,25 para ~0,08–0,19.
- **`VoiceActivityDetector`** é um VAD de energia:
  - ignora os primeiros 300 ms quando toca o bipe;
  - exige 300 ms de fala para começar;
  - termina depois de `silenceMs` (padrão 900 ms) de silêncio;
  - uma palavra curta ainda é enviada no fim do prazo de 5 s.
- **`HaDirectBackend`** roda a sessão:
  - STT;
  - `TextCorrector.stripWakeWord`, que tira "Hey Tony", "Ei Tony" e formas juntadas como "Reitone" e "Heitori";
  - descarte de alucinações do Whisper ("Obrigado.", "Tchau", "Amara.org"), que reabre o microfone uma vez;
  - depois a ordem: desfazer → timers → treino → listas → previsão → agenda → correções aprendidas → corretor de nomes → luzes → agente do HA (com uma segunda tentativa corrigida).
- **Só a chamada** (`only-wake-word`): o app reabre o microfone, no máximo uma vez seguida.
- **Tela otimista:** luzes acionadas por toque ou voz mudam na tela na hora (`HomeModel.setLightOptimistic`) e voltam ao estado real se o HA não confirmar em 6 s.

## 4. Mapa do código

Os arquivos ficam em `android/app/src/main/java/br/com/jarvishome/`:

| Pacote | Arquivos e papel |
|---|---|
| `service/` | `JarvisForegroundService` (microfone, estados, sessões, falas, timers, aprendizado), `WorkoutCoach` (treino por voz), `AlarmBeeper`, `BootReceiver` |
| `wakeword/` | `PersonalWakeWord` (detector + `PersonalStore`: exemplos em `files/personal/vozes.tpl`, aprendidos em `aprendidos.tpl`), `PersonalDetector` (log-mel + DTW), `WakeWords` (só "Hey Tony") |
| `audio/` | `VoiceActivityDetector`, `AudioPlayer` (MediaPlayer na voz do HA, bipe, TTS Android de reserva) |
| `network/` | `HaDirectBackend` (sessão de voz), `MiniWebSocket`, `TextCorrector` (normalização, tirar a chamada, correção por vocabulário), `CommandMatcher`, `Backend`/`Backends` (fábrica a partir das Prefs), `JarvisApi` (orquestrador opcional) |
| `commands/` | Lógica pura, testável na JVM: `LightCommands` (luzes, treino, cômodos da planta via `Room`), `Timers`, `Workout`, `Lists`, `Learning` (correções aprendidas, desfazer, filtro de alucinação) |
| `home/` | `HomeApi` (REST), `HomeParser` (estados → tela), `HomeModel` (estado da tela + `Night`), `HomeMonitor` (leitura periódica, avisos, checagem de atualização), `FloorPlan` (planta, cômodos, nomes falados, mapeamento de relés), `Forecast`, `LightControl` (único caminho de liga/desliga da tela) |
| `calendar/` | `CalendarParser` (frases → evento), `CalendarAssistant`, `CalendarModel` |
| `settings/` | `Prefs` (SharedPreferences "jarvis"), `Backup` (exportar e importar treino de voz e configurações), `Provisioning` (`assets/provision.json` só em builds locais), `SecureStore` |
| `ui/` | `MainActivity`, `SettingsActivity` (abas Conexão/Voz/Comandos/Agenda/Tela/Sistema), `FloorPlanView` (isométrica em Canvas), `CharacterView`, `VoiceTrainingActivity`, `CommandTrainingActivity`, `AgendaActivity`, `WorkoutActivity` |
| `update/` | `Updater`, `UpdateFlow`, `ApkInstaller` (PackageInstaller) |
| `domain/` | `StateBus`, `JLog`, `CommandHistory` ("Últimos comandos"), `CrashLog` |

Fora do app:
- `server/jarvis-orchestrator/`: orquestrador opcional (Python aiohttp, porta 8765). Não está em uso, porque o app fala direto com o HA.
- `docs/Jarvis_Home_Planejamento.md`: planejamento original.

## 5. Build (sem Gradle)

```bash
# Debian/Ubuntu: apt install openjdk-21-jdk-headless aapt dalvik-exchange zipalign apksigner
# Kotlin 1.9.25 (NÃO 2.x) e android.jar da API 33
cd android
ANDROID_JAR=~/sdk/android-33.jar KOTLINC=~/kotlinc-1.9.25/bin/kotlinc \
VERSION_NAME=1.15.4 VERSION_CODE=45 ./build.sh        # → build/JarvisHome-1.15.4.apk
```

Regras obrigatórias:
- **Kotlin 1.9.x.** A stdlib do 2.x usa `invokedynamic` (LambdaMetafactory), que o dalvik-exchange não converte, e o app quebra no aparelho com `NoSuchMethodError metafactory`. O `build.sh` barra isso e também barra `compareBy(vararg)`, `thenBy` e `nullsFirst/Last`. Use `sortedBy`/`sortedByDescending` simples.
- O compilador roda com `-Xlambdas=class -Xsam-conversions=class -Xstring-concat=inline`.
- **Assinatura:** `KEYSTORE=jarvis-release.jks` (senha em `KS_PASS`). Sem o `.jks` **original**, o build gera uma chave nova e o celular **recusa a atualização**, porque a assinatura é diferente. O `.jks` nunca vai para o Git (está no `.gitignore`). Peça o arquivo ao usuário.
- **Token:** `PROVISION_URL` + `PROVISION_TOKEN` embutem o token no APK. Use **só** para instalar à mão, nunca em versão publicada.

## 6. Publicar uma versão (o celular se atualiza sozinho)

```bash
VERSION_NAME=1.15.4 VERSION_CODE=45 NOTAS="o que mudou" \
ANDROID_JAR=... KOTLINC=... tools/publicar_versao.sh
```

- O script compila **sem token**, gera `versao.json` com `versionCode`, `versionName`, `notes`, `apk`, `size` e `sha256`, e faz force-push na **branch órfã `atualizacoes`** de `github.com/duduferaso/jarvis_home` (repositório público).
- O app confere a cada 12 h, ou em Engrenagem → Sistema → Procurar atualização. Ele lê pela API de contents do GitHub e tem como reserva o raw e os Releases.
- O `versionCode` tem de subir sempre. Depois, faça commit e push do código na `main`.

## 7. Testes (JVM, sem Android)

- **Integração:** `tests/test_casa.sh` e `tests/test_direto.sh` sobem `tests/mock_ha.py`, um HA falso (aiohttp, com REST + WebSocket + `todo` + `get_forecasts`), e rodam `tests/jvm_casa` e `tests/jvm/Main.kt`, que é a sessão de voz completa com o backend real.
- **Unitários:** `tests/jvm_comandos` (luzes, frases reais do log, cômodos), `jvm_timers`, `jvm_treino`, `jvm_listas`, `jvm_tempo`, `jvm_aprendizado`, `jvm_corretor`, `jvm_planta`, `jvm_vad`, `jvm_agenda` e `jvm_pessoal`. Compile com `kotlinc` 1.9 junto com `tests/jvm/OrgJson.kt` e `JLogStub.kt`, que são stubs de `org.json` e do log.
- Todos imprimem `PASSOU`/`FALHOU` e, no fim, `TODOS OS TESTES PASSARAM`.

## 8. Convenções e cuidados

- **Idioma:** textos, falas e comentários em português do Brasil. As falas são curtas e naturais.
- **Voz:** toda fala usa a voz do HA (`speakHa`/`voiceFile`). A voz do Android é só reserva, no mesmo volume. Não crie caminhos de fala paralelos.
- **Threads:** threads em segundo plano capturam `Throwable`, não só `Exception`, porque um `Error` derrubava o app.
- **Luzes:**
  - A tela usa sempre `LightControl` e a voz usa `HomeApi.setPower`.
  - O comando é sempre `turn_on`/`turn_off` explícito, nunca `toggle`.
  - A regra do que é luz fica só em `LightCommands.isLight`, e a normalização de texto só em `TextCorrector.normalize`.
- **Modo noite:** `Night.isNight`. Respostas e bipe usam `Night.volume(prefs)`. Timers e alarmes pedidos falam sempre.
- **Segredos:** nada de token, `.jks`, `provision.json` ou `.env` no Git. O e-mail do usuário é privado.
- **Lógica nova:** de preferência em `commands/` (pura e testável), com um teste junto.

## 9. Pendências e ideias

- **Mi Box:** abrir YouTube ou Prime Music e controlar play, pausa e volume via Android TV Remote. Falta o nome da entidade no HA.
- **Clima:** adicionar a Met.no no HA.
- **Entendimento:** subir o modelo do Whisper no HA e acompanhar "Últimos comandos" (Engrenagem → Sistema).
- **Ideias oferecidas e ainda não feitas:**
  - rotinas "bom dia" e "boa noite" (com conferência da casa antes de dormir);
  - reconhecer quem está falando (o detector já sabe de qual pessoa é o exemplo);
  - perguntas gerais via agente de IA no HA.
- **Documentação de uso** (configurações e comandos de voz): https://claude.ai/artifact/Rmkm3Jj6MtwPUhyChhESV3
