#!/usr/bin/env python3
"""
Calibra o wake word "Jarvis" para a SUA pronúncia.

Por quê: o modelo de detecção que roda no Redmi (sherpa-onnx, zipformer gigaspeech)
foi treinado em inglês. Nos testes com vozes sintéticas em português, "Jarvis"
pronunciado à brasileira quase não é detectado com a grafia inglesa. A saída é
descobrir quais grafias o modelo "ouve" quando VOCÊ fala, usando gravações reais.

Como usar (no servidor Debian ou em qualquer PC):

  1) No Redmi, grave com o gravador de voz, na posição onde o celular vai ficar:
       - 8 a 15 arquivos falando só "Jarvis" ou "Jarvis, acende a luz" (positivos/)
       - 5 a 10 arquivos de conversa normal, TV ligada etc., SEM dizer Jarvis (negativos/)
     Copie para o servidor em duas pastas: positivos/ e negativos/
     (qualquer formato que o ffmpeg ou sox abra: m4a, mp3, wav, ogg).

  2) Instale as dependências (uma vez):
       sudo apt install ffmpeg
       python3 -m venv ~/jarvis-calib && . ~/jarvis-calib/bin/activate
       pip install sherpa-onnx==1.12.26 sentencepiece numpy

  3) Rode, apontando para a pasta kws do projeto (modelo idêntico ao do app):
       python3 calibrar_wakeword.py --modelo ../android/app/src/main/assets/kws \
           --bpe bpe.model --positivos positivos --negativos negativos

  4) Copie as linhas impressas em "COLE NO APP" para
     Configurações > Avançado: variações da palavra, e ajuste a sensibilidade sugerida.

Também gera tokens para qualquer palavra:
       python3 calibrar_wakeword.py --bpe bpe.model --tokens "JARVIS" "SEXTA FEIRA"
"""

import argparse
import glob
import os
import shutil
import subprocess
import sys

import numpy as np

# Grafias "em inglês" que imitam a pronúncia brasileira de Jarvis (J = "zh/sh", R mudo ou aspirado).
CANDIDATOS = [
    "JARVIS", "JAVIS", "JOVIS", "JARVES", "JAR VIS", "SHARVIS", "ZHARVIS", "JAHVIS", "JAVEES",
    "JAHVEES", "ZHAHVIS", "SHAHVIS", "SHAVIS", "ZHAVIS", "GERVIS", "JARVEES", "JARBIS", "CHARVIS",
    "DJARVIS", "JARVICE", "JAVICE", "JAHRVIS", "JARVEZ", "JARVI", "JARVY", "SHARVEES", "JAH VIS",
    "JA VIS", "SHA VIS", "ZHA VIS", "JAH VEES", "JAWVIS", "SHAWVIS", "CHAVIS", "XAVIS", "JARVISH",
    "JAVISH", "JAVIZ", "SHAVIZ", "DJAVIS", "DJAHVIS", "HARVIS", "RAVIS", "HAVIS",
]
# Sensibilidade do app (1..10) -> limiar usado pelo detector; mesma fórmula do Prefs.kt
LIMIARES = {s: round(0.40 - (s - 1) * (0.32 / 9), 3) for s in range(1, 11)}


def carregar(arquivo: str) -> np.ndarray:
    if shutil.which("ffmpeg"):
        cmd = ["ffmpeg", "-v", "quiet", "-i", arquivo, "-ac", "1", "-ar", "16000", "-f", "s16le", "-"]
    else:
        cmd = ["sox", arquivo, "-r", "16000", "-c", "1", "-b", "16", "-t", "raw", "-"]
    raw = subprocess.run(cmd, capture_output=True, check=True).stdout
    pcm = np.frombuffer(raw, np.int16).astype(np.float32) / 32768
    pad = np.zeros(8000, np.float32)
    return np.concatenate([pad, pcm, pad, pad])


def tokens(sp, palavra: str) -> str:
    return " ".join(sp.encode(palavra.upper(), out_type=str))


def detectar(kws, audio: np.ndarray) -> list:
    s = kws.create_stream()
    achados = []
    for i in range(0, len(audio), 1600):
        s.accept_waveform(16000, audio[i:i + 1600])
        while kws.is_ready(s):
            kws.decode_stream(s)
            r = kws.get_result(s)
            if r:
                achados.append(r)
                kws.reset_stream(s)
    return achados


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--bpe", required=True, help="bpe.model do modelo gigaspeech (pasta tools/)")
    ap.add_argument("--tokens", nargs="+", help="só imprime os tokens destas palavras e sai")
    ap.add_argument("--modelo", help="pasta com encoder.onnx, decoder.onnx, joiner.onnx, tokens.txt")
    ap.add_argument("--positivos", help="pasta com gravações dizendo Jarvis")
    ap.add_argument("--negativos", help="pasta com gravações SEM Jarvis")
    args = ap.parse_args()

    import sentencepiece as spm
    sp = spm.SentencePieceProcessor(model_file=args.bpe)

    if args.tokens:
        for w in args.tokens:
            print(f"{tokens(sp, w)} @{w.upper().replace(' ', '_')}")
        return

    if not (args.modelo and args.positivos and args.negativos):
        ap.error("informe --modelo, --positivos e --negativos (ou use --tokens)")

    import sherpa_onnx

    def arquivos(pasta):
        fs = sorted(f for f in glob.glob(os.path.join(pasta, "*")) if os.path.isfile(f))
        if not fs:
            sys.exit(f"Nenhum arquivo em {pasta}")
        return fs

    pos_f, neg_f = arquivos(args.positivos), arquivos(args.negativos)
    print(f"Carregando {len(pos_f)} positivos e {len(neg_f)} negativos…")
    pos = [carregar(f) for f in pos_f]
    neg = [carregar(f) for f in neg_f]

    kw_file = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".candidatos.txt")
    with open(kw_file, "w", encoding="utf-8") as fh:
        for c in CANDIDATOS:
            fh.write(f"{tokens(sp, c)} @{c.replace(' ', '_')}\n")

    m = args.modelo
    resultados = {}
    for sens in (4, 6, 8, 10):
        thr = LIMIARES[sens]
        kws = sherpa_onnx.KeywordSpotter(
            tokens=f"{m}/tokens.txt", encoder=f"{m}/encoder.onnx", decoder=f"{m}/decoder.onnx",
            joiner=f"{m}/joiner.onnx", keywords_file=kw_file, num_threads=2, provider="cpu",
            keywords_score=1.5, keywords_threshold=thr, max_active_paths=4, num_trailing_blanks=1)
        pos_hits = [set(detectar(kws, a)) for a in pos]
        neg_hits = [set(detectar(kws, a)) for a in neg]
        falsos = set().union(*neg_hits) if neg_hits else set()
        boas = set().union(*pos_hits) - falsos           # grafias que acertam e nunca erram
        cobertura = sum(1 for h in pos_hits if h & boas)
        acertos = {k: sum(1 for h in pos_hits if k in h) for k in boas}
        resultados[sens] = (acertos, falsos, cobertura)
        print(f"  sensibilidade {sens:2d} (limiar {thr:.2f}): {cobertura}/{len(pos)} positivos detectados "
              f"por grafias seguras; grafias com disparo falso: {', '.join(sorted(falsos)) or 'nenhuma'}")

    # Maior cobertura vence; em empate fica a sensibilidade menor (menos risco de falso positivo).
    melhor = None
    for sens in (4, 6, 8, 10):
        acertos, _, cob = resultados[sens]
        if acertos and (melhor is None or cob > resultados[melhor[0]][2]):
            melhor = (sens, acertos)

    print()
    if not melhor:
        print("Nenhuma grafia detectou sua voz. Opções:")
        print("  - grave mais perto do microfone, pronunciando 'Djárvis' (mais próximo do inglês);")
        print("  - use o toque no rosto para falar enquanto treina um modelo próprio (docs/Jarvis_Home_Planejamento.md, seção 5.1).")
        return
    sens, boas = melhor
    total = len(pos)
    print(f"Recomendado: sensibilidade {sens} no app. Grafias que acertaram sem disparo falso:")
    for k, v in sorted(boas.items(), key=lambda kv: -kv[1]):
        print(f"  {k:10s} acertou {v}/{total}")
    print("\n===== COLE NO APP (Configurações > Avançado: variações da palavra) =====")
    escolhidas = [k for k, _ in sorted(boas.items(), key=lambda kv: -kv[1])][:8]
    for k in escolhidas:
        print(f"{tokens(sp, k.replace('_', ' '))} @{k}")
    print("=======================================================================")
    _, falsos, cob = resultados[sens]
    print(f"Cobertura esperada: {cob}/{total} gravações.")
    if falsos:
        print(f"Excluídas por dispararem em gravações sem Jarvis: {', '.join(sorted(falsos))}")


if __name__ == "__main__":
    main()
