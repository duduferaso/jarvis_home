# Bancada do alarme: "Hey Tony" por cima do toque do timer, em vários pontos do ciclo de bipes.
# Uso: python3 alarme_bancada.py <pasta com calls/> <silero_vad.onnx> <saída>; depois AlarmeBancada.kt pontua.
# Resultado (1.15.11): bipe 20 dB acima da voz → 15% (pausa 1,1 s) · 49% (pausa 2,6 s) · 71% (+ toque calado ao ouvir fala).
import numpy as np, sherpa_onnx, os, sys, glob, shutil
D=os.path.join(sys.argv[1],"calls")
calls=[np.fromfile(f,'<f4') for f in sorted(glob.glob(D+"/*.f32"))]
SR=16000
def pattern(pause):
    out=[]
    def tone(ms,hz):
        n=SR*ms//1000; i=np.arange(n); env=np.minimum(1,np.minimum(i,n-i)/(SR*0.01)); out.append(np.sin(2*np.pi*hz*i/SR)*env)
    for _ in range(3): tone(160,1046.5); out.append(np.zeros(SR*110//1000))
    out.append(np.zeros(int(SR*pause))); return np.concatenate(out).astype(np.float32)
def vadnew():
    c=sherpa_onnx.VadModelConfig(); c.silero_vad.model=sys.argv[2]
    c.silero_vad.threshold=0.4; c.silero_vad.min_silence_duration=0.3; c.silero_vad.min_speech_duration=0.25
    c.silero_vad.window_size=512; c.silero_vad.max_speech_duration=8; c.sample_rate=SR
    return sherpa_onnx.VoiceActivityDetector(c, buffer_size_in_seconds=30)
def rms(x): return np.sqrt(np.mean(x**2))
out=sys.argv[3]; shutil.rmtree(out,ignore_errors=True)
scen={"atual (pausa 1,1 s)":(1.1,False),"pausa 2,6 s":(2.6,False),"pausa 2,6 s + pausa o toque ao ouvir fala":(2.6,True)}
gains={"toque 10 dB acima da voz":10,"toque 20 dB acima":20}
for si,(sn,(pause,hold)) in enumerate(scen.items()):
  pat=pattern(pause); cyc=len(pat)
  for gi,(gn,gdb) in enumerate(gains.items()):
    for ci,c in enumerate(calls):
      for oi,off in enumerate(np.arange(0,cyc/SR,0.2)):
        start=int(SR*4.0+off*SR); L=start+len(c)+SR*2
        voice=np.zeros(L,np.float32); voice[start:start+len(c)]=c
        bg=np.random.RandomState(oi).randn(L).astype(np.float32)*rms(c)*0.03  # piso de ruído leve
        bgain=rms(c)*10**(gdb/20)/0.7071*0.8
        vad=vadnew(); held_until=-1; ph=0; mic=np.zeros(L,np.float32); d=f"{out}/s{si}_g{gi}/c{ci:02d}_o{oi:02d}"; os.makedirs(d)
        n=0; recent_hold=-10**9
        for i in range(0,L,1600):
            m=voice[i:i+1600]+bg[i:i+1600]
            for k in range(len(m)):
                if not (hold and i+k<held_until): m[k]+=pat[(ph)%cyc]*bgain
                ph+=1  # o toque continua avançando (pausar = silenciar o trecho)
            mic[i:i+len(m)]=m
            vad.accept_waveform(m)
            if hold and vad.is_speech_detected() and i>=held_until and i-recent_hold>SR*1.0:
                held_until=i+int(SR*3.5); recent_hold=held_until
            while not vad.empty():
                s=np.array(vad.front.samples,np.float32); st=vad.front.start; vad.pop()
                pre=mic[max(0,st-2400):st]
                if len(pre)<2400: pre=np.concatenate([np.zeros(2400-len(pre),np.float32),pre])
                np.concatenate([pre,s]).astype('<f4').tofile(f"{d}/{n:02d}.f32"); n+=1
  print(sn, "ok", flush=True)
for si,sn in enumerate(scen): open(f"{out}/s{si}.name","w").write(sn)
for gi,gn in enumerate(gains): open(f"{out}/g{gi}.name","w").write(gn)
