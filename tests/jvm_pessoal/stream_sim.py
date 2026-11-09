# Réplica fiel do PersonalWakeWord.kt: Silero VAD (mesma config), anel de 6 s, 0,15 s antes, recorte de 2,2 s,
# DTW de prefixo contra os exemplos, corte do comando. Exemplos = recortes reais; cada chamada é avaliada SEM o próprio exemplo.
import json, wave, glob, subprocess, numpy as np, sherpa_onnx, sys
exec(open("dtw2.py").read().split("seg=[s for s")[0])   # feats_idx, prefix_dtw, mel etc.
W=sys.argv[1]
w=wave.open("real16k.wav"); x=(np.frombuffer(w.readframes(w.getnframes()),np.int16)/32768).astype(np.float32)
segs=[s for s in json.load(open("segs.json")) if s["dur"]>=0.7]
crops=[x[int(max(0,s["start"]-0.15)*16000):int((s["start"]+min(s["dur"],1.6)+0.1)*16000)] for s in segs]
T=[feats_idx(c) for c in crops]; THR=0.205
def feats_with_idx(a):
    a2=np.append(a[0],a[1:]-0.97*a[:-1]); n=1+max(0,(len(a2)-400)//160)
    fr=np.stack([a2[i*160:i*160+400] if i*160+400<=len(a2) else np.pad(a2[i*160:],(0,400-len(a2[i*160:]))) for i in range(n)])*WIN
    f=np.log((np.abs(np.fft.rfft(fr,512))**2)@FB.T+1e-6); e=f.mean(1); keep=np.where(e>e.max()-6.0)[0]
    if len(keep)<=10: keep=np.arange(n)
    f=f[keep]; f=f-f.mean(0); return f/(np.linalg.norm(f,axis=1,keepdims=True)+1e-6), keep
def prefix_end(t,s):
    D=1-t@s.T; n,m=D.shape; C=np.full((n+1,m+1),np.inf); C[0,0]=0
    for i in range(1,n+1):
        for j in range(1,m+1): C[i,j]=D[i-1,j-1]+min(C[i-1,j],C[i,j-1],C[i-1,j-1])
    best=(1e9,m)
    for j in range(max(1,n//2),min(m,2*n)+1):
        d=C[n,j]/(n+j)
        if d<best[0]: best=(d,j)
    return best
def run_stream(audio, exclude_fn=lambda t:None, label=""):
    cfg=sherpa_onnx.VadModelConfig(); cfg.silero_vad.model=f"{W}/wh/silero_vad.onnx"; cfg.silero_vad.threshold=0.4
    cfg.silero_vad.min_silence_duration=0.3; cfg.silero_vad.min_speech_duration=0.25; cfg.silero_vad.window_size=512
    cfg.silero_vad.max_speech_duration=8; cfg.sample_rate=16000
    vad=sherpa_onnx.VoiceActivityDetector(cfg, buffer_size_in_seconds=30)
    hist=np.zeros(0,np.float32); out=[]; scores=[]
    for i in range(0,len(audio),1600):
        fr=audio[i:i+1600]; hist=np.concatenate([hist,fr]); vad.accept_waveform(fr)
        while not vad.empty():
            seg=vad.front; vad.pop(); s=np.array(seg.samples,np.float32); st=seg.start
            if len(s)<5600: continue
            pre=hist[max(0,st-2400):st]; crop=np.concatenate([pre,s[:35200]])
            f,keep=feats_with_idx(crop); ex=exclude_fn(st/16000)
            best=(1e9,0)
            for k,t in enumerate(T):
                if k==ex: continue
                d,j=prefix_end(t,f)
                if d<best[0]: best=(d,j)
            scores.append((round(st/16000,2),round(best[0],3)))
            if best[0]<=THR:
                endf=keep[best[1]-1]; wake_end=max(0,endf*160+400-len(pre))
                cmd=s[wake_end:]; out.append({"t":round(st/16000,2),"d":round(best[0],3),"cmd_s":round(len(cmd)/16000,2),"one":len(cmd)>8000,"cmd":cmd})
    return out,scores
mode=sys.argv[2]
if mode=="real":
    def excl(t):
        for k,s in enumerate(segs):
            if s["start"]-0.5<=t<=s["start"]+s["dur"]: return k
    trig,sc=run_stream(x,excl)
    print(f"gravação real em tempo real: {len(trig)} ativações para {len(segs)} chamadas (cada uma sem o próprio exemplo)")
    print("  falas avaliadas (início s, distância):", sc)
    hit={k for tr in trig for k,s in enumerate(segs) if s["start"]-0.5<=tr["t"]<=s["start"]+s["dur"]}
    print(f"  chamadas reconhecidas: {len(hit)}/{len(segs)}; ativações fora de chamadas: {sum(1 for tr in trig if not any(s['start']-0.5<=tr['t']<=s['start']+s['dur'] for s in segs))}")
elif mode=="emendado":
    negf=sorted(glob.glob(f"{W}/ww/neg/n*_p.wav"))
    bg=x[int(31*16000):int(34*16000)]  # 3 s de ruído da casa entre chamadas
    stream=[]; truth=[]
    pos=0
    for k,c in enumerate(crops):
        r=subprocess.run(["sox",negf[k],"-r","16000","-c","1","-b","16","-t","raw","-"],capture_output=True).stdout
        cmd=(np.frombuffer(r,np.int16)/32768).astype(np.float32); cmd=cmd*(np.abs(c).max()/max(1e-6,np.abs(cmd).max()))
        piece=np.concatenate([bg, c, np.zeros(1200,np.float32)+bg[:1200], cmd, bg])
        truth.append((pos+len(bg))/16000); stream.append(piece); pos+=len(piece)
    audio=np.concatenate(stream)
    def excl(t):
        return min(range(len(truth)),key=lambda k:abs(truth[k]-t)) if min(abs(tt-t) for tt in truth)<1.0 else None
    trig,sc=run_stream(audio,excl)
    print(f"comando emendado: {len(trig)} ativações em {len(crops)} sequências; {sum(t['one'] for t in trig)} tratadas como frase única")
    import os; os.makedirs("cmds",exist_ok=True)
    for i,tr in enumerate(trig):
        tr["cmd"].astype(np.float32).tofile(f"cmds/{i:02d}.f32")
        print(f"   t={tr['t']:6.2f}s dist {tr['d']:.3f} frase única={tr['one']} comando {tr['cmd_s']}s")
    full=[]
    for k in range(len(crops)):
        r=subprocess.run(["sox",negf[k],"-r","16000","-c","1","-b","16","-t","raw","-"],capture_output=True).stdout
        full.append(round(len(r)/2/16000,2))
    print("   duração real de cada comando falado:", full)
else:
    negf=sorted(glob.glob(f"{W}/ww/neg/*.wav")); parts=[]
    for f in negf:
        r=subprocess.run(["sox",f,"-r","16000","-c","1","-b","16","-t","raw","-"],capture_output=True).stdout
        parts += [(np.frombuffer(r,np.int16)/32768).astype(np.float32), np.zeros(8000,np.float32)]
    audio=np.concatenate(parts); trig,sc=run_stream(audio)
    print(f"{len(audio)/16000:.0f} s de fala sem chamada: {len(trig)} ativações falsas; menor distância {min(d for _,d in sc):.3f} em {len(sc)} falas")
