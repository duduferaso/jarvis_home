# Jarvis Home: instalação

O app fala **direto com o Home Assistant** em `http://192.168.18.108:8123`.
Não é preciso instalar nada no Debian se o Home Assistant já tiver faster-whisper e piper.

```
JarvisHome/
├── JarvisHome-1.5.0.apk           app para instalar no Redmi
├── android/                        código-fonte do app e build.sh
├── tools/calibrar_wakeword.py      ajusta o "Jarvis" para a sua voz
├── server/                         OPCIONAL: Whisper/Piper em Docker e orquestrador
├── tests/                          testes do app e do orquestrador com HA simulado
└── docs/Jarvis_Home_Planejamento.md   planejamento v1.1
```

## 1. Home Assistant

1. **Assistente de voz** (Configurações > Assistentes de voz): use o assistente que já existe, com estes campos:
   - Idioma do topo: escolha **Português** na lista (se aparecer "Item desconhecido", troque).
   - Agente: Home Assistant, português (Brasil).
   - Fala para texto: faster-whisper, português.
   - Texto para fala: piper, pt_BR, voz faber.
   - Marque como **preferido**.
   - Teste digitando "que horas são" no Assist (ícone de conversa no topo).
2. **Usuário do Jarvis** (Configurações > Pessoas > Usuários > Adicionar): nome `jarvis`, **sem** administrador.
3. **Token:** entre como `jarvis`, vá em Perfil > Segurança > Tokens de acesso de longa duração > Criar token. Copie (só aparece uma vez) e mande para o celular (nota ou mensagem para você mesmo).
4. **Expor entidades** (Assistentes de voz > aba Expor): deixe só o que o Jarvis pode controlar, com apelidos em português ("luz da sala"). Fechaduras e alarmes ficam de fora.

## 2. Redmi 10

1. Copie o `JarvisHome-1.5.0.apk` para o celular e abra. Permita "instalar apps desconhecidos" se pedir.
2. Abra o Jarvis Home e permita **microfone** e **notificações**.
3. Toque na engrenagem ⚙. O endereço já vem preenchido (`http://192.168.18.108:8123`). Cole o **token** do passo 1.3, toque em **Testar conexão** (deve aparecer "✓ Home Assistant respondendo") e em **Salvar**.
4. **Toque no rosto** e diga "que horas são".
5. Para não parar de ouvir:
   - ⚙ > **Desativar otimização de bateria**;
   - Android > Apps > Jarvis Home: **Início automático** ligado e **Economia de bateria: Sem restrições**;
   - nos apps recentes, segure o Jarvis e toque no **cadeado**;
   - Redmi fixo na parede: ⚙ > **Modo terminal**, depois botão Home > escolher Jarvis Home como padrão.

Tocar no rosto: falar (ou interromper a resposta). Segurar: repouso, com o microfone desligado.

## 3. Chamada por voz: "Hey Tony" (detector pessoal)

Os detectores prontos (o do app em inglês, o openWakeWord e o do app oficial do Home Assistant) foram treinados com vozes em inglês e falham com a pronúncia brasileira: na gravação real de vocês pela casa, o melhor deles reconheceu **2 de 13** chamadas.

A partir da versão 1.4.0 o app usa um **detector pessoal**: compara o som de cada fala com exemplos gravados por quem mora aqui. Não depende de idioma nem de sotaque. Na mesma gravação: **13 de 13**, nas duas vozes e de longe (-51 dB), e **nenhum disparo** em quase 3 minutos de fala comum.

- Já vem com **13 exemplos da gravação de vocês dois**: funciona logo ao instalar.
- **Pode chamar de dois jeitos:** "Hey Tony" (pausa curta) "liga a luz do quarto", ou tudo de uma vez: "Hey Tony, liga a luz do quarto".
- **Treine no Redmi** (⚙ > Treinar a minha voz), **uma vez cada pessoa**: 6 chamadas + 15 s de conversa, cerca de 1 minuto. Os exemplos se somam (máximo de 40) e o limiar é recalculado com a conversa real da casa. É o que mais melhora o resultado, porque usa o microfone e a posição do próprio Redmi.
- **Ajuste fino:** ⚙ > "Exigência do detector pessoal". O diagnóstico mostra a "distância" de cada fala; a chamada aciona quando fica abaixo do valor escolhido. Não atende de primeira? Suba 0,01. Ativa sozinho? Desça 0,01.
- ⚙ > "Voltar aos exemplos de fábrica" desfaz os treinos.

O modelo em inglês e a calibração antiga continuam disponíveis com o detector pessoal desligado.

## 4. Entender melhor os comandos

O app corrige o texto antes de mandar para o Home Assistant:
1. pede ao Home Assistant só a transcrição;
2. remove "Jarvis" do começo e compara o texto com os **nomes da sua casa**, lidos automaticamente: cômodos e apelidos, dispositivos e automações no formato "Voz: frase";
3. corrige palavras deformadas ("luz do **zoquart**" → "luz do **quarto**") e manda o comando corrigido.

Ele só troca uma palavra **desconhecida** que pareça com um nome da casa; o resto nunca é alterado ("mercado", "farmácia" e valores do financeiro passam intactos; 36 casos testados em `tests/jvm_corretor`). Em ⚙ > "Entender melhor os comandos" dá para desligar a correção e acrescentar palavras próprias. No **Depurar** do Home Assistant aparecem as duas etapas: a transcrição original e o comando corrigido.

**Comandos de luz no app (1.6):** "liga/desliga/acende/apaga a luz do …" é resolvido pelo próprio app (tolera "deliga", "de liga", "luz do quatro", duas luzes com "e", "todas as luzes") e aciona `homeassistant.turn_on/turn_off` direto. Perguntas, porcentagem e cores seguem para o Home Assistant. Em ⚙ > **Treinar comandos de luz**, cada pessoa grava os comandos; o app guarda como o Whisper transcreveu a voz dela e passa a reconhecer exatamente aquilo. A frase inteira (com o "Hey Tony") agora vai para a transcrição, para não cortar o "des" de "desliga". Testes em `tests/jvm_comandos/LightCommandsTest.kt`.

**Segunda tentativa (1.5.1):** se o Home Assistant responder que não entendeu, o app procura o comando conhecido mais parecido (o verbo falado é mantido, nunca troca "liga" por "desliga") e tenta de novo, sem falar o "não entendi" da primeira vez. Em ⚙ > **Últimos comandos** aparece, para cada comando, o que foi ouvido, o que foi enviado, a 2ª tentativa e o resultado. Testes em `tests/jvm_comandos`.

Quando houver SSH, melhorar o próprio Whisper continua valendo: `--model small-int8 --language pt --beam-size 5`.

## 5. Agenda, avisos e tela (versão 1.5)

### Agenda
1. **Crie uma agenda no Home Assistant** (se ainda não tiver): Configurações > Dispositivos e serviços > **Adicionar integração** > **Calendário local** (Local Calendar), nome **Casa**. Funciona **sem internet**.
   - Também dá para usar o **Google Agenda** (integração "Google Calendar"): sincroniza com o celular, mas precisa de internet. O app lê todas as agendas e marca na que você escolher em ⚙ > Agenda.
2. Pronto. Por voz:
   - "Hey Tony, **marca dentista amanhã às 15h**" · "**me lembra de** tirar o lixo amanhã de manhã" · "**coloca na agenda** aniversário da Ana dia 20"
   - "Hey Tony, **o que eu tenho amanhã?**" · "qual o meu **próximo compromisso**?" · "como está a agenda da semana?"
   - "Hey Tony, **desmarca o dentista** de amanhã" · "**desmarca o último compromisso**"
   - Se faltar o dia ("marca dentista"), ele pergunta "Para quando?" e ouve a resposta sem precisar chamar de novo.
3. Na tela: os 3 próximos compromissos. Tocar no quadro abre a agenda completa (14 dias), com **+ Novo** e toque longo para desmarcar.
4. **Lembretes:** na hora de um compromisso com horário, o Jarvis fala "Lembrete: …" (⚙ > Agenda: "na hora" ou até 60 min antes).

Entende "às 3 da tarde", "15h30", "às dez e meia", "meio-dia", "hoje à noite", "sexta que vem", "dia 12 de dezembro", "15/11", "daqui a 2 horas", "das 14 às 16"… Sem dia informado, a hora já passada vai para amanhã. "Às 3" sem "da manhã" vira 15h, e o Jarvis sempre repete a hora na resposta.

### Avisos falados do Home Assistant
1. Crie o auxiliar: Configurações > Dispositivos e serviços > **Auxiliares** > **Criar auxiliar** > **Texto**, nome **Jarvis aviso** (fica `input_text.jarvis_aviso`), **comprimento máximo 255**.
2. Em qualquer automação, use a ação **Texto: definir valor** nesse auxiliar com a frase. Em até 5 s o Jarvis mostra na tela e fala (e limpa o campo). Exemplo:

```yaml
alias: Avisar máquina de lavar
triggers:
  - trigger: numeric_state
    entity_id: sensor.tomada_maquina_potencia
    below: 3
    for: "00:02:00"
actions:
  - action: input_text.set_value
    target:
      entity_id: input_text.jarvis_aviso
    data:
      value: "A máquina de lavar terminou."
```

3. Teste em ⚙ > Avisos do Home Assistant > **Testar aviso**.

Durante uma conversa, o aviso espera ela terminar. No **modo noite** e em **repouso**, o aviso só aparece na tela (dá para liberar a fala à noite em ⚙ > Modo noite).

### Tela
- **Topo:** relógio, data, clima e avisos. Sem internet, ou com a previsão velha há mais de 3 h, o clima some sozinho.
- **Embaixo** (só em espera): alerta do servidor (temperatura acima de 75 °C ou disco acima de 90%, sensores detectados sozinhos), agenda e **luzes**: tocar liga e desliga (o nome fica ciano quando acesa).
- **Modo noite** (23h às 6h, ajustável): tela no mínimo, só relógio e rosto. O primeiro toque só acende a tela; chamar "Hey Tony" acende também.
- **Sem rede:** relógio, rosto e chamada continuam; luzes e alertas somem (para não mostrar informação errada); a agenda mostra o que já tinha carregado, marcada "sem conexão".

## Problemas comuns

| Mensagem no app | O que verificar |
|---|---|
| Sem conexão com a casa | Celular em outra rede Wi-Fi, ou IP do Home Assistant mudou (fixe o IP no roteador) |
| Token recusado | Token copiado incompleto, ou foi apagado no perfil do usuário `jarvis` |
| Pipeline de voz incompleta | Faltou escolher fala para texto ou texto para fala no assistente preferido |
| Desculpe, não entendi | Whisper não reconheceu nada: fale mais perto ou aumente "Silêncio para encerrar" |
| Para de ouvir com a tela apagada | Restrições do MIUI (passo 2.5) |
| "Microfone mudo" no diagnóstico | Android bloqueou o microfone após o boot: abra o app ou use o modo terminal |
| "Não encontrei uma agenda que aceite compromissos" | Adicione o Calendário local (o Google Agenda só aceita se a integração tiver permissão de escrita) |
| Aviso não aparece | Confira se o auxiliar se chama exatamente `input_text.jarvis_aviso` (⚙ > Testar aviso) |
| Luzes não aparecem | Só aparecem entidades `light.*` e interruptores cujo nome começa com "Luz" |

Para revogar o acesso do celular: apague o token no perfil do usuário `jarvis` no Home Assistant.

## Opcional: pasta server/

- **Sem faster-whisper/piper no Home Assistant:** `docker compose -f server/docker-compose.jarvis.yml up -d` sobe os dois. Depois adicione a integração **Wyoming Protocol** duas vezes (`192.168.18.108:10300` e `:10200`).
- **Orquestrador:** deixa o token do HA só no servidor; o celular passa a usar um token próprio. Detalhes no planejamento (seção 4.2). No app, desligue "Conectar direto" e use a porta 8765.

## Reconstruir o APK e testar

```bash
cd android
VERSION_CODE=5 VERSION_NAME=1.3.0 ANDROID_JAR=/caminho/android-33.jar KOTLINC=/caminho/kotlinc/bin/kotlinc ./build.sh
cd ../tests && KOTLINC=/caminho/kotlinc/bin/kotlinc ./test_direto.sh
```

Requisitos estão no topo do `build.sh`. Use sempre o mesmo `android/jarvis-release.jks`: o Android só atualiza o app se a chave for a mesma. Aumente o `VERSION_CODE` a cada versão.


## Atualizações pelo app (1.7)

⚙ > **Atualizações > Procurar atualização**: o app lê `versao.json` na branch `atualizacoes` de `github.com/duduferaso/jarvis_home` (se não existir, tenta o GitHub Releases), baixa o APK, confere o SHA-256 e o Android pergunta "Instalar?". Na primeira vez o Android pede para permitir instalar a partir do Jarvis. O app também confere sozinho (no máximo a cada 12 h) e avisa por notificação.

- Repositório público: não precisa de chave. Privado: cole em ⚙ uma chave *fine-grained* só com **Contents: Read-only** desse repositório (digite `-` no campo para apagar a chave).
- Para publicar: `VERSION_NAME=1.7.2 VERSION_CODE=14 NOTAS="..." tools/publicar_versao.sh`. O APK publicado sai **sem** o token do Home Assistant (o script confere) e precisa ser assinado com a mesma `jarvis-release.jks`, que **nunca** vai para o repositório (`.gitignore`).
- A branch `atualizacoes` guarda só a última versão (o script a recria a cada publicação). O nome do APK é `JarvisHome-<versão>-<código>.apk`; o código é o que decide se é mais nova.


## Timers e alarmes (1.8)

No próprio app, sem internet: "Hey Tony, timer de 5 minutos" (também "alarme de 5 minutos", "me avisa em 20 minutos"), "timer de 10 minutos para o arroz", "me acorda às 6 e meia" / "alarme para as 7 horas" (sem dizer manhã/tarde, vale o próximo horário), "quanto falta?", "cancela o timer do arroz", "cancela todos os timers". A contagem aparece na tela. Quando acaba, o Jarvis fala e toca "bip-bip-bip" no volume de alarme; para com "Hey Tony" (ou "Hey Tony, para"), com um toque na tela, ou sozinho depois de 3 minutos. A lista sobrevive a um reinício do app. Lógica em `commands/Timers.kt`, testes em `tests/jvm_timers`.


## Treino intervalado (1.9)

Botão **TREINO** na tela inicial: quantidade de exercícios e de séries (cada exercício = uma rodada de execução + descanso, padrão 30 s + 30 s; 5 × 5 = 25 min), nomes opcionais. Por voz: "treino de 5 exercícios e 5 séries", "treino de 30 minutos com 6 exercícios" (calcula as séries completas que cabem), "treino 5 de 5". A tela mostra a fase (EXECUÇÃO verde / DESCANSO azul), a contagem, o exercício atual ("3 de 8", "volta 2"), o próximo, a rodada e o tempo que falta. O serviço avisa com bipes nos 3 últimos segundos, fala o exercício na execução e "Descanso. Próximo: …" no descanso (voz do Android, sem rede). A última rodada termina na execução. Voz: "começa o treino [de 20 minutos]", "pausa", "continua", "próximo", "em que exercício eu estou?", "quanto falta?", "para o treino". Lógica em `commands/Workout.kt`, testes em `tests/jvm_treino`.
