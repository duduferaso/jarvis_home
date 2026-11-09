# Jarvis Home — Documento de Planejamento

**Versão:** 1.1 (revisada após a primeira implementação)
**Status:** app 1.5.0: detector pessoal "Hey Tony", agenda por voz, tela informativa (luzes, clima, alertas, avisos) e modo noite
**Plataforma cliente:** Android — Redmi 10 (arm64)
**Servidor:** Debian 13 headless, Docker, Home Assistant
**Idioma principal:** Português (Brasil)
**Princípio central:** funcionamento local, sem dependência de serviços externos ou internet.

### O que mudou da versão 1.0

| Tema | v1.0 | v1.1 |
|---|---|---|
| Infraestrutura | Genérica ("Debian com HA") | Descrita com o ambiente real (seção 10) |
| Wake word | "sherpa-onnx ou equivalente" | sherpa-onnx escolhido e testado; risco do sotaque **medido** e mitigado (seção 5.1) |
| Contrato da API | Exemplos ilustrativos | Protocolo v1 definido, implementado e testado (seção 8) |
| Ferramentas da IA | Camada própria a construir | Usa a exposição de entidades do Home Assistant como lista de ferramentas autorizadas (seção 5.3) |
| Orquestrador | Responsável por STT, IA, TTS | **Opcional.** O app fala direto com a pipeline Assist do HA, que já tem faster-whisper e piper (seção 4.2) |
| Ativação | Só voz | Voz **e** toque no rosto (contingência e acessibilidade) |
| Inicialização | Boot receiver | Boot receiver + **modo terminal** (Jarvis como tela inicial), por causa de restrições do Android 11+ ao microfone |
| Zigbee | Citado nas decisões | Removido: o projeto usa ESPHome via Wi-Fi (decisão tomada no planejamento elétrico). Irrelevante para o Jarvis, que só fala com o HA |

---

## 1. Visão do projeto

O Jarvis Home é um assistente residencial com personalidade visual, interação por voz e integração com o Home Assistant.

O Redmi 10 funciona como terminal dedicado: fica fixo na casa, exibe um rosto animado e aguarda a palavra **"Jarvis"** (ou um toque no rosto). Após a ativação, o áudio do pedido vai pela rede Wi-Fi local ao servidor, que transcreve, interpreta, executa e devolve a resposta falada. O celular reproduz a resposta e anima o rosto conforme o estado real da conversa.

Nada depende de nuvem. A comunicação acontece apenas na rede local.

## 2. Objetivos

- Transformar o Redmi 10 em terminal de voz residencial.
- Detectar "Jarvis" localmente no celular.
- Reconhecer comandos em português no servidor local.
- Usar a IA local do servidor, quando integrada, sem acoplar o app a um modelo.
- Controlar dispositivos do Home Assistant com confirmação real do resultado.
- Responder por voz com síntese local.
- Exibir personagem com expressões vetoriais.
- Manter a escuta estável com a tela apagada, dentro dos limites do Android.

## 3. Escopo e princípios

### 3.1 Dentro do escopo
App Android nativo em tela cheia; wake word local; captura e envio de áudio pela LAN; integração com a pipeline Assist do HA; IA local via agente de conversação do HA; respostas faladas; estados visuais; configurações, diagnóstico e recuperação de falhas.

### 3.2 Fora do escopo inicial
Alexa/Google/nuvem; envio de áudio a APIs públicas; controle direto de relés pelo app; treinar LLM no celular; vários terminais simultâneos (o protocolo já identifica terminais, então a expansão é barata).

### 3.3 Princípios técnicos
1. **Local-first.**
2. **Privacidade:** nenhum áudio sai do celular antes da ativação. Transcrições não vão para o log por padrão.
3. **Separação:** o celular cuida da interação; o servidor, da inteligência e da automação.
4. **Segurança:** o token do HA nunca sai do servidor; o celular tem token próprio e revogável.
5. **Resiliência:** falha de rede não derruba a escuta; o Jarvis avisa por voz local.
6. **Evolução:** trocar Whisper, Piper ou a IA não exige mudar o app.

---

## 4. Arquitetura

```text
┌───────────────────────── REDMI 10 ─────────────────────────┐
│ MainActivity (rosto vetorial)   SettingsActivity (diagnóst.)│
│ JarvisForegroundService (tipo microfone, wake lock)         │
│   ├─ AudioRecord 16 kHz ─► sherpa-onnx KWS ("Jarvis")       │
│   ├─ VAD por energia (fim da fala)                          │
│   ├─ JarvisApi: HTTP em streaming + eventos NDJSON          │
│   └─ AudioPlayer: Piper (do servidor) ou voz local do       │
│      Android quando a casa não responde                     │
└──────────────────────────┬──────────────────────────────────┘
                           │ LAN, porta 8123, token do usuário "jarvis"
┌──────────────────────────▼──── SERVIDOR DEBIAN 13 ──────────┐
│ Home Assistant 192.168.18.108:8123 (container existente)    │
│   WebSocket /api/websocket: assist_pipeline/run             │
│   Assistente preferido:                                     │
│     STT  ── faster-whisper (já instalado)                   │
│     Agente ─ Assist (comandos) + IA local (conversa)        │
│     TTS  ── piper, pt_BR faber (já instalado)               │
│   Entidades expostas = ferramentas autorizadas              │
└──────────────────────────┬──────────────────────────────────┘
                           ▼
                Dispositivos ESPHome (Wi-Fi local)
```

**Decisão principal:** o app usa a mesma API dos satélites de voz oficiais (WebSocket + `assist_pipeline/run`). O HA continua sendo o único lugar onde se configura modelo, voz e agente, e qualquer satélite futuro (ESP32-S3, por exemplo) usa a mesma pipeline.

### 4.1 Fluxo de uma interação

1. `IDLE`: o serviço lê o microfone em quadros de 100 ms e alimenta o detector local. Nada é enviado.
2. "Jarvis" detectado (ou toque no rosto) → `LISTENING`, bipe opcional.
3. O app abre o WebSocket do HA, inicia `assist_pipeline/run` e envia o áudio em quadros binários, incluindo 100 ms anteriores à ativação.
4. O VAD local detecta o fim da fala (silêncio configurável) → `THINKING`.
5. A pipeline do HA transcreve, interpreta, executa e sintetiza. Se o VAD do HA detectar o fim da fala antes, o app para de enviar.
6. Eventos voltam linha a linha: transcrição, resposta, resultado da ação, URL do áudio.
7. `SPEAKING`: o app toca o áudio do Piper e anima a boca pela energia real do som.
8. Ação confirmada → `HAPPY`; erro → `CONFUSED` ou `ERROR`; depois `IDLE`.
9. Se o agente fizer uma pergunta de volta (`continue_conversation`), o microfone reabre sem exigir "Jarvis".

### 4.2 Modo de conexão (decisão da v1.1)

A v1.0 pedia que o token do Home Assistant nunca fosse ao celular, o que exigia o orquestrador. Na v1.1 a escolha passou a ser **conexão direta**, por simplicidade:

| | Direto (padrão) | Orquestrador (opcional) |
|---|---|---|
| No celular | Token do usuário `jarvis`, **sem administrador** | Token próprio, inútil fora do Jarvis |
| No servidor | Nada novo | Container `jarvis-orchestrator` |
| Revogar | Apagar o token no perfil do `jarvis` | Remover de `JARVIS_TERMINALS` |
| Mudança na API do HA | Exige APK novo | Só o orquestrador muda |

O risco aceito: quem tiver o celular desbloqueado controla as entidades expostas ao usuário `jarvis`. Isso é mitigado por usar um usuário sem administrador, pelo token cifrado no Keystore e pela revogação imediata no HA. O orquestrador continua no projeto e é selecionável no app ("Conectar direto" desligado).

---

## 5. Componentes

| Responsabilidade | Escolha | Onde | Situação |
|---|---|---|---|
| App | Kotlin nativo, Views + Canvas | Redmi | Pronto |
| Interface | Canvas customizado (sem Compose: APK menor e animação direta) | Redmi | Pronto |
| Wake word | sherpa-onnx 1.12.9, KWS zipformer gigaspeech 3,3M (int8) | Redmi | Pronto, **calibração com voz real pendente** |
| Captura | AudioRecord, fonte VOICE_RECOGNITION, 16 kHz mono | Redmi | Pronto |
| Fim de fala | VAD por energia com piso de ruído adaptativo | Redmi (+ VAD do HA) | Pronto |
| Transporte | WebSocket do HA (cliente próprio, sem dependências) | Ambos | Pronto e testado |
| STT | faster-whisper | HA | **Já instalado** |
| Interpretação | Assist + agente de IA local | HA | Configurar |
| TTS | piper `pt_BR` faber medium | HA | **Já instalado** |
| Reprodução | MediaPlayer + Visualizer (boca) | Redmi | Pronto |

### 5.1 Wake word: o que os testes mostraram

O modelo de palavra-chave roda inteiro no Redmi (≈5,8 MB de modelo, 1 thread). A palavra é definida em tempo de execução por tokens BPE, sem treinar nada, e pode ser trocada nas configurações do app.

O risco previsto na v1.0 foi medido. O modelo foi treinado em inglês, e testes com áudio sintético mostraram:

| Amostra | Detectou "Jarvis"? |
|---|---|
| Voz sintética em inglês | Sim, em todas as sensibilidades |
| Voz sintética pt-BR (espeak), "Jarvis" isolado | Não |
| Voz neural pt-BR (Piper), 10 frases com "Jarvis" | 3 de 10, só com variações como JAVIS e JOVIS e sensibilidade máxima |
| 6 frases sem "Jarvis" (pt e en) | Nenhum disparo falso |

Para comparação, o modelo pré-treinado "hey jarvis" do openWakeWord teve o mesmo comportamento (0,96 em inglês, cerca de 0 em todas as amostras brasileiras). O problema é a pronúncia brasileira (J como "zh", R aspirado), não o motor escolhido.

**Ranking de palavras (v1.2):** 22 palavras testadas no mesmo detector, com duas vozes brasileiras sintéticas (4 frases cada) e 40 frases de conversa normal:

| Palavra | Detecções | Disparos falsos | Observação |
|---|---|---|---|
| Alexa | 5/8 (3/4 na voz mais natural) | 1/40 ("ela disse") | Melhor resultado medido |
| Tony | 3/8 | 0/40 na sensibilidade 8 | Alternativa sem falsos disparos |
| Max, Lúcia | 5/8 (1/4 na voz natural) | 0/40 | Fracas na voz mais natural |
| Jarvis | 1/8 | 0/40 | Com voz real o usuário relatou ativações parciais |
| Pandora, Aurora, Atena, Marvin, Friday, Computador, Ok Casa, Orion, Ultron… | 0/8 | — | Descartadas |

Também testados e **descartados**: modelo streaming em espanhol (Kroko, 155 MB; não reconheceu "Jarvis"), ganho automático (sem ganho consistente; o detector já normaliza o volume) e reforço 3,0 das palavras-chave (piorou).

**Calibração v1.3.1:** testar todas as variações juntas fazia elas competirem no detector (Alexa: 7/8 com cada uma sozinha contra 3/8 todas juntas). A calibração passou a testar cada variação sozinha, dar a cada uma a sensibilidade mais conservadora sem disparo na conversa, e manter no máximo 3, conferidas em conjunto. Com vozes sintéticas: Alexa 3→4/8, Tony 3→4/8, Jarvis 0→1/8, sem falsos disparos. A variação "ALEX" foi corretamente descartada por disparar na conversa em todas as sensibilidades.

**Mitigação adotada:**
1. **Calibração no próprio app** (⚙ > Calibrar minha voz): 8 gravações da palavra + 15 s de fala normal; testa dezenas de grafias em 4 sensibilidades, exclui as que disparam na fala normal e salva a combinação que mais reconhece a voz do usuário.
2. **Escolha da palavra** entre Jarvis, Alexa, Tony e Max, cada uma com suas variações de calibração. Se a calibração ficar fraca, o app sugere trocar.
3. Tocar no rosto ativa sempre.

**Decisão v1.4 — detector pessoal.** Com a gravação real da casa (2 vozes, 13 chamadas de "Hey Tony", de -34 a -51 dBFS), o modelo em inglês ficou em 0/13 na configuração calibrada e 2/13 no melhor caso entre 36 grafias e 6 sensibilidades; o "Hey Jarvis" do app oficial do HA também falhava no uso diário. Adotado um detector pessoal: Silero VAD recorta cada fala; os primeiros 2,2 s viram log-mel de 40 bandas (média do trecho subtraída, quadros normalizados) e são comparados por DTW de prefixo com exemplos de voz da casa. Resultado em simulação fiel do app em tempo real, cada chamada avaliada sem o próprio exemplo: 13/13, 0 disparos em 171 s de fala; comando emendado: 12/13 e o corte do comando idêntico ao comando isolado. Custo: ~12 ms por fala com 13 exemplos. Limiar de fábrica 0,205 (pior chamada ~0,19; fala mais parecida 0,223); o treino no app recalcula com a conversa real. Pendente: validar com conversa real dos moradores (os negativos até aqui são fala sintética e ruído da casa).

**Plano B, se nenhuma palavra passar de 6/8 com a voz real:** treinar um modelo próprio (openWakeWord/microWakeWord) com gravações reais da casa e vozes pt-BR variadas. A interface `WakeWordEngine` já permite trocar o motor.

### 5.2 Reconhecimento de fala
Whisper `small-int8` com `--language pt` e `--beam-size 1`, como equilíbrio para CPU sem GPU. Se a resposta passar de 3 s, testar `base-int8`; se sobrar CPU, `medium-int8`. Medir no hardware real antes de fixar (Fase 0).

### 5.3 Interpretação e inteligência

- **Comandos residenciais:** o Assist do HA resolve "acenda a luz da cozinha" sem LLM, rápido e determinístico.
- **Conversa:** um agente de IA local configurado no HA (por exemplo, a integração Ollama), com a opção **"Preferir o processamento local de comandos"** ligada. Assim comandos simples não passam pelo LLM e a latência cai.
- **Ferramentas autorizadas:** a IA só enxerga e controla as entidades marcadas em *Configurações > Voice assistants > Expor*. Esta é a camada de ferramentas pedida na v1.0, sem código novo. Fechaduras, alarmes e similares ficam fora da lista ou passam por scripts que pedem confirmação.
- **Honestidade:** o app só mostra o rosto feliz quando o HA devolve `action_done`. Sem confirmação, o Jarvis não diz que fez.

### 5.4 Síntese de voz
Piper `pt_BR-faber-medium` no servidor. O app recebe texto e áudio. Se o áudio falhar, o app usa a voz do próprio Android (offline) para não ficar mudo, e mostra o texto na tela.

---

## 6. Identidade visual

Minimalista e dark: fundo preto, ciano `#22E5FF`, alerta âmbar `#FFB020`. O rosto é desenhado em Canvas. Cada parte (olhos, sobrancelhas, boca, anéis, pontos) é um parâmetro que interpola até o alvo do estado, então as transições são suaves e não há imagem por expressão.

| Estado | Comportamento implementado |
|---|---|
| `IDLE` | Piscadas em intervalos aleatórios (às vezes dupla), olhar que passeia devagar |
| `LISTENING` | Olhos maiores, anéis pulsando com o volume do microfone |
| `THINKING` | Olhos estreitos girando, três pontos orbitando, sem piscar |
| `SPEAKING` | Boca abre conforme a energia real do áudio (Visualizer) |
| `HAPPY` | Olhos "^ ^" e sorriso |
| `CONFUSED` | Um olho apertado, sobrancelha erguida, boca torta |
| `ERROR` | Tudo em âmbar, sobrancelhas preocupadas, boca para baixo |
| `SLEEPING` | Olhos fechados, "z" subindo, microfone desligado |

Layout: nome e indicador de conexão no topo, relógio opcional, rosto ao centro, estado, transcrição e dica embaixo, engrenagem discreta no canto. Tocar no rosto fala, segurar coloca em repouso.

Economia: 25 quadros/s em interação, 15 em espera e cerca de 9 com "reduzir animações". A animação para quando a tela some.

---

## 7. Aplicativo Android

### 7.1 Estrutura real

```text
android/app/src/main/java/br/com/jarvishome/
├── domain/      AssistantState, StateBus (fonte única de estado), JLog
├── settings/    Prefs, SecureStore (Keystore AES-GCM)
├── audio/       AudioPlayer, VoiceActivityDetector
├── wakeword/    WakeWordEngine (interface), SherpaWakeWord, ModelInstaller
├── network/     JarvisApi, VoiceSession
├── service/     JarvisForegroundService, BootReceiver
└── ui/          MainActivity, CharacterView, SettingsActivity
```

minSdk 26, targetSdk 33, apenas arm64-v8a, cerca de 14 MB. O build sem Gradle está em `android/build.sh`.

### 7.2 Serviço contínuo
Serviço em primeiro plano do tipo microfone, com notificação persistente (ação "Repousar/Ativar") e wake lock parcial. Recupera sozinho falhas do microfone com espera crescente. Não depende da Activity aberta.

**Limite do Android 11+:** um serviço iniciado no boot pode receber só silêncio do microfone até o app aparecer na tela. O app detecta isso ("Microfone mudo" no diagnóstico). A solução é o **modo terminal**: ativado nas configurações, transforma o Jarvis na tela inicial do aparelho, que o sistema abre sozinho após reiniciar.

MIUI/HyperOS: liberar *Início automático*, *Economia de bateria: Sem restrições* e travar o app nos recentes. O app tem atalho para a exceção de otimização de bateria.

### 7.3 Permissões
Microfone, notificações (Android 13+), rede, boot, wake lock e exceção de bateria. Todas explicadas na primeira abertura.

---

## 8. Comunicação

**Modo direto (padrão):** WebSocket `ws://192.168.18.108:8123/api/websocket`, autenticação com o token do usuário `jarvis`, `assist_pipeline/run` de `stt` a `tts`, áudio PCM 16 kHz em quadros binários prefixados pelo `stt_binary_handler_id`, e o `conversation_id` mantido por 5 minutos. A saúde da conexão é verificada em `GET /api/`. A URL do áudio de resposta é sempre reescrita para o endereço configurado, porque o HA pode devolvê-la com um hostname interno. Testado em `tests/test_direto.sh`: autenticação, VAD do servidor, mensagem acima de 64 KB, token errado, cancelamento e erro de pipeline.

### 8.1 Protocolo do orquestrador (modo opcional)

Rede local, HTTP simples, porta 8765. Toda requisição leva `Authorization: Bearer <token do terminal>` e `X-Terminal-Id`.

| Método | Caminho | Corpo | Resposta |
|---|---|---|---|
| GET | `/v1/health` | — | `{"ok", "protocol", "terminal", "home_assistant", "ha_version"}` |
| POST | `/v1/voice?rate=16000` | PCM s16le mono 16 kHz, chunked | Stream NDJSON de eventos |
| POST | `/v1/text` | `{"text": "..."}` | Stream NDJSON (testes sem voz) |
| GET | `/v1/audio/{id}` | — | Áudio da resposta (expira em 120 s) |

Eventos (todos levam `session` e `v`):

```json
{"type": "ready"}
{"type": "transcription", "text": "acenda a luz da cozinha"}
{"type": "state", "state": "thinking"}
{"type": "response", "text": "Luz da cozinha ligada", "response_type": "action_done", "continue_conversation": false}
{"type": "action_result", "success": true, "description": "Luz da cozinha ligada"}
{"type": "audio", "url": "/v1/audio/3f9c…", "mime": "audio/mpeg"}
{"type": "error", "code": "stt-no-text-recognized", "message": "…"}
{"type": "done", "elapsed_ms": 1840}
```

Códigos de erro tratados pelo app: `network`, `unauthorized`, `ha-*`, `timeout`, `stt-no-text-recognized` e outros da pipeline (mostrados como texto).

**Segurança:** tokens de terminal com no mínimo 16 caracteres, comparação em tempo constante, revogação removendo do `.env`. O token do HA pertence a um usuário dedicado sem administrador. O token do terminal fica cifrado no Keystore do Android. Nenhum áudio é guardado; transcrições não vão para o log, a menos que `JARVIS_LOG_TRANSCRIPTS=true`. Não expor a porta 8765 na internet.

**Testado:** `tests/test_orchestrator.sh` sobe um Home Assistant simulado e valida autenticação, recusa sem token, upload de 2 s de áudio, todos os eventos, download do áudio e entrada por texto.

---

## 9. Integração com Home Assistant

Criar no HA:
1. Usuário **jarvis** sem administrador, com token de longa duração (vai só para o `.env` do servidor).
2. Integrações Wyoming apontando para `IP:10300` (Whisper) e `IP:10200` (Piper).
3. Pipeline **Jarvis**: idioma Português (Brasil), STT Whisper, TTS Piper (faber), agente Assist ou IA local com processamento local de comandos preferido.
4. Entidades expostas ao assistente: só o que o Jarvis pode controlar, com aliases em português ("luz da sala", "ventilador").

---

## 10. Servidor (ambiente conhecido)

| Item | Situação |
|---|---|
| Sistema | Debian 13, sem interface gráfica, acesso por SSH |
| Discos | SSD 120 GB (sistema e containers), HD 500 GB em `/mnt/dados` (mídia) |
| Containers existentes | Home Assistant, Jellyfin, Immich (Docker Compose) |
| Automação | ESPHome via Wi-Fi |
| CPU/RAM | **A confirmar:** define o modelo do Whisper e da IA |
| IA local | **A confirmar:** Ollama ou outra |

Serviços novos (arquivo separado, sem mexer nos existentes): `jarvis-whisper`, `jarvis-piper`, `jarvis-orchestrator`.

Espaço: Whisper small e voz Piper ocupam menos de 1 GB. Modelos de LLM ocupam vários GB cada; se usar Ollama, guardar os modelos em `/mnt/dados` para poupar o SSD.

Diagnóstico a rodar antes de instalar (só leitura):

```bash
lscpu | grep -E 'Model name|^CPU\(s\)'; free -h; df -h / /mnt/dados
docker ps --format '{{.Names}}\t{{.Image}}\t{{.Ports}}'
docker inspect homeassistant --format '{{.HostConfig.NetworkMode}}' 2>/dev/null
curl -s localhost:11434/api/tags | head -c 300   # Ollama, se existir
```

### 10.1 Operação offline
Depois do primeiro `docker compose up`, os modelos ficam em `server/data/`. Fixar as versões das imagens (trocar `latest` pelo digest atual). Testar com a WAN desligada (critério 10). O app não usa nenhum serviço do Google: a voz de contingência é o TTS instalado no aparelho.

---

## 11. Configurações do app (implementadas)

Endereço, terminal, token (cifrado), teste de conexão; assistente ligado/desligado; wake word ligado/desligado; sensibilidade 1–10; variações da palavra; silêncio para encerrar; volume; bipe; tela sempre ligada; brilho mínimo; reduzir animações; relógio; iniciar com o aparelho; modo terminal; medidor de microfone em tempo real; última ativação; simular ativação; testar alto-falante; exceção de bateria; logs técnicos copiáveis.

## 12. Erros e respostas

| Situação | O que o usuário vê e ouve |
|---|---|
| Sem rede ou servidor | ERRO, "Não consegui conectar à casa" (voz local) |
| HA fora | ERRO, "A casa não está respondendo" |
| Demora > 60 s | ERRO, "A casa demorou demais para responder" |
| Nada dito após ativar | NÃO ENTENDI, "Não ouvi nada" |
| Whisper sem texto | NÃO ENTENDI, "Desculpe, não entendi" |
| Token recusado | ERRO na tela, ponto vermelho no topo |
| Sem permissão de microfone | ERRO com instrução de onde liberar |
| Wake word falhou ao carregar | Toque no rosto continua funcionando; motivo no diagnóstico |

A escuta nunca para por falha de rede. O indicador de conexão é verificado a cada 6 s quando há problema e a cada 20 s quando está tudo bem.

---

## 13. Plano de desenvolvimento (atualizado)

### Fase 0 — Diagnóstico e prova de conceito
- [x] Escolher o motor de wake word (sherpa-onnx) e embutir no app
- [x] Medir o risco de pronúncia com áudio sintético (seção 5.1)
- [x] Ferramenta de calibração com voz real
- [ ] Rodar o diagnóstico do servidor (seção 10) e confirmar a IA local
- [ ] Instalar o APK, gravar 10 "Jarvis" e calibrar (meta: 8 de 10, nenhum disparo falso em 1 h de TV)
- [ ] Medir CPU e bateria do Redmi em espera por 1 h

### Fase 1 — Protótipo visual: **concluída em código**
### Fase 2 — Wake word e serviço: **concluída em código**, falta validar horas de uso no Redmi e o MIUI
### Fase 3 — Transporte: **concluída e testada** com HA simulado (modo direto e orquestrador)
### Fase 4 — Reconhecimento e inteligência
- [x] faster-whisper e piper já instalados no HA
- [ ] Corrigir o idioma do assistente ("Item desconhecido") e marcar como preferido
- [ ] Criar o usuário `jarvis` e o token
- [ ] Expor entidades com aliases
- [ ] Integrar a IA local como agente
- [ ] Medir a latência de cada etapa (meta: menos de 3 s em comandos simples)

### Fase 5 — Resposta falada: **concluída em código** (Piper, voz local de contingência, boca sincronizada, interrupção por toque)
### Fase 6 — Acabamento
- [ ] 24 h ligado na tomada: aquecimento e estabilidade
- [ ] Reinício do aparelho com modo terminal
- [ ] Teste completo com a WAN desligada
- [ ] Fixar versões das imagens Docker

## 14. Critérios de aceitação
Mantidos da v1.0 (12 critérios), com uma meta mensurável para o primeiro: **detectar "Jarvis" em pelo menos 8 de 10 tentativas a 2 m, com no máximo 1 falso positivo por dia.**

## 15. Riscos

| Risco | Probabilidade | Mitigação |
|---|---|---|
| Wake word não reconhece a pronúncia brasileira | **Alta (medida)** | Calibração com voz real; toque; modelo próprio como plano B |
| MIUI encerra o serviço | Média | Sem restrições de bateria, início automático, modo terminal |
| Microfone mudo após boot (Android 11+) | Média | Modo terminal; detecção e aviso no diagnóstico |
| Whisper lento na CPU | Desconhecida | Medir; trocar para `base-int8` |
| LLM lento para comandos | Média | Processamento local de comandos preferido no HA |
| IA executar ação indevida | Baixa | Apenas entidades expostas; nada crítico exposto |

## 16. Decisões tomadas
Todas as da v1.0, exceto Zigbee (substituído por ESPHome Wi-Fi), mais: sherpa-onnx no app; conexão direta à pipeline Assist (orquestrador opcional); protocolo HTTP + NDJSON; Canvas em vez de Compose; toque como ativação alternativa; modo terminal; entidades expostas do HA como lista de ferramentas da IA.

## 17. Próximo passo
1. Ajustar o assistente no HA e criar o usuário `jarvis` (README, passo 1).
2. Instalar o APK 1.1.0, colar o token e testar pelo toque no rosto.
3. Informar CPU, RAM e IA local (seção 10) para escolher o agente de conversa.
4. Gravar a própria voz e calibrar o wake word.

**Regra mantida:** nenhum serviço existente é alterado; o Jarvis sobe em containers próprios.

---

## 18. Versão 1.5: tela informativa e agenda

**Agenda por voz, local e sem IA.** Pedidos de agenda são reconhecidos no app (parser de datas e horas em português, 62 frases testadas) e executados direto no Home Assistant: `calendar.create_event` para marcar, `GET /api/calendars/<id>` para consultar e `calendar/event/delete` (WebSocket) para desmarcar. A resposta sai na voz do Piper, rodando só a etapa `tts` da pipeline. O título é guardado como foi falado (antes da correção de nomes). Recomendado: integração **Calendário local**, que funciona offline. O Google Agenda também funciona, mas depende de internet.

**Avisos.** Automação escreve em `input_text.jarvis_aviso`, o app lê a cada 5 s, mostra, fala e limpa. Foi escolhido em vez de eventos personalizados porque usuários sem administrador não podem assinar eventos arbitrários no WebSocket.

**Lembretes.** Compromissos com horário são falados na hora (ou até 60 min antes), com janela de 2 min para não repetir lembretes antigos ao religar.

**Tela.** Painéis só em espera e fora do modo noite; o rosto encolhe e sobe um pouco quando os painéis estão visíveis. Modo noite com brilho mínimo, primeiro toque só acende, avisos só na tela. Sem internet, o clima some (estado indisponível ou atualizado há mais de 3 h). Sem rede, as luzes e os alertas somem e a agenda fica com a última leitura.

**Testes.** `tests/test_casa.sh`: HA simulado com luzes, clima, sensores, auxiliar de aviso e duas agendas; 26 verificações (marcar no horário certo, consultar, "Para quando?", desfazer, desmarcar pelo nome, comandos que continuam indo ao HA, falta de agenda, aviso lido e limpo, clima velho escondido).
