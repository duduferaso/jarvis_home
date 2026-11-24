# Testes do detector pessoal

- `PersonalDetectorTest.kt`: roda o código Kotlin do app (VoiceFeatures, Dtw, TemplateSet, PersonalTrainer)
  na JVM sobre recortes de gravações reais e confere que reproduz o protótipo em Python
  (validação "deixa um de fora", limiar automático, salvar/carregar exemplos).
- `dtw.py` / `dtw2.py`: protótipo em Python (log-mel + DTW; DTW de prefixo para comando emendado).
- `stream_sim.py`: réplica fiel do pipeline em tempo real do app (Silero VAD com a mesma configuração,
  anel de áudio, 0,15 s antes da fala, recorte de 2,2 s, corte do comando).

As gravações de voz da casa não fazem parte do projeto (privacidade). Para repetir os testes, grave
"Hey Tony" pela casa, converta para WAV 16 kHz mono (`ffmpeg -i gravacao.m4a -ac 1 -ar 16000 real16k.wav`)
e gere os segmentos com o Silero VAD (ver início de `stream_sim.py`).

Resultados com a gravação de 4/11/2026 (2 vozes, 13 chamadas, -34 a -51 dBFS):
| Método | Chamadas | Disparos falsos |
|---|---|---|
| Modelo em inglês (melhor de 36 grafias × 6 sensibilidades) | 2/13 | 0 |
| Detector pessoal, tempo real, cada chamada sem o próprio exemplo | 13/13 | 0 em 171 s de fala |
| Detector pessoal, comando emendado | 12/13 | 0 |

`ConversaTest.kt` (bug de "ativar sozinho" conversando, 1.15.10): roda a decisão real do app (`WakeMatch`:
começo do trecho, exemplos aprendidos e fim do trecho) com limiar 0,20 sobre os 105 trechos de conversa,
207 trechos de conversa contínua (2 a 3 falas emendadas), limiar 10% acima (folga), 10 exemplos aprendidos
de chamadas reais, e confere que as 13 chamadas continuam reconhecidas. Resultado: 0 disparos, menor
distância da conversa 0,231. Também mostra o mecanismo do bug: um trecho de conversa aprendido como
chamada dispara de novo, por isso o reforço só aprende com ação confirmada.

`alarme_bancada.py` + `AlarmeBancada.kt` (1.15.11): "Hey Tony" por cima do toque do alarme do timer.
Com o bipe 20 dB acima da voz, só 15% das chamadas paravam o alarme (pausa de 1,1 s entre os bipes).
Com pausa de 2,6 s: 49%. Com a pausa + o toque calado por 3,5 s ao ouvir fala (`AlarmHold`): 71%.
