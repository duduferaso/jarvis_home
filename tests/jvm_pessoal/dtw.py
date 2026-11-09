# Protótipo do detector pessoal: log-mel + normalização + DTW de subsequência, validação "deixa um de fora".
import json, wave, glob, subprocess, numpy as np
w=wave.open("real16k.wav"); x=(np.frombuffer(w.readframes(w.getnframes()),np.int16)/32768).astype(np.float32)
segs=[s for s in json.load(open("segs.json")) if s["dur"]>=0.7]
def mel_fb(n_mels=40,n_fft=512,sr=16000,fmin=60,fmax=7600):
    m=lambda f:2595*np.log10(1+f/700); im=lambda q:700*(10**(q/2595)-1)
    pts=im(np.linspace(m(fmin),m(fmax),n_mels+2)); bins=np.floor((n_fft+1)*pts/sr).astype(int)
    fb=np.zeros((n_mels,n_fft//2+1))
    for i in range(1,n_mels+1):
        l,c,r=bins[i-1],bins[i],bins[i+1]
        for k in range(l,c): fb[i-1,k]=(k-l)/max(1,c-l)
        for k in range(c,r): fb[i-1,k]=(r-k)/max(1,r-c)
    return fb
FB=mel_fb(); WIN=np.hamming(400)
def feats(a):
    a=np.append(a[0],a[1:]-0.97*a[:-1])
    n=1+max(0,(len(a)-400)//160)
    fr=np.stack([a[i*160:i*160+400] if i*160+400<=len(a) else np.pad(a[i*160:],(0,400-len(a[i*160:]))) for i in range(n)])*WIN
    p=np.abs(np.fft.rfft(fr,512))**2
    f=np.log(p@FB.T+1e-6)
    # só os quadros com voz (energia acima do piso do trecho)
    e=f.mean(1); keep=e>e.max()-6.0
    f=f[keep] if keep.sum()>10 else f
    f=f-f.mean(0)                     # normalização por trecho (canal/volume)
    f=f/ (np.linalg.norm(f,axis=1,keepdims=True)+1e-6)
    return f
def dtw(a,b):
    # distância cosseno, DTW clássica normalizada pelo caminho
    D=1-a@b.T; n,m=D.shape; C=np.full((n+1,m+1),np.inf); C[0,0]=0
    for i in range(1,n+1):
        ci=C[i]; cp=C[i-1]; di=D[i-1]
        for j in range(1,m+1):
            ci[j]=di[j-1]+min(cp[j],ci[j-1],cp[j-1])
    return C[n,m]/(n+m)
calls=[]
for s in segs:
    a=x[int(max(0,s["start"]-0.15)*16000):int((s["start"]+min(s["dur"],1.6)+0.1)*16000)]
    calls.append(feats(a))
# negativos: fala em português (vozes sintéticas, 40 frases) recortada em janelas de 1,4 s + trechos de silêncio/ruído da gravação
negs=[]
for f in sorted(glob.glob(f"{__import__('sys').argv[1]}/ww/neg/*.wav")):
    raw=subprocess.run(["sox",f,"-r","16000","-c","1","-b","16","-t","raw","-"],capture_output=True).stdout
    a=(np.frombuffer(raw,np.int16)/32768).astype(np.float32)
    for st in range(0,max(1,len(a)-22400),12000): negs.append(feats(a[st:st+22400]))
speech=np.zeros(len(x),bool)
for s in json.load(open("segs.json")): speech[int(max(0,s["start"]-0.5)*16000):int((s["start"]+s["dur"]+0.5)*16000)]=True
for st in range(0,len(x)-22400,16000):
    if not speech[st:st+22400].any(): negs.append(feats(x[st:st+22400]))
print(f"{len(calls)} chamadas reais, {len(negs)} trechos negativos (fala pt sintética + ruído da casa)")
pos_scores=[]
for i,c in enumerate(calls):
    others=[calls[j] for j in range(len(calls)) if j!=i]
    pos_scores.append(min(dtw(c,t) for t in others))
neg_scores=[min(dtw(n,t) for t in calls) for n in negs]
pos_scores=np.array(pos_scores); neg_scores=np.array(neg_scores)
print("distância das chamadas (deixa-uma-de-fora):", np.round(np.sort(pos_scores),3))
print("menores distâncias dos negativos:          ", np.round(np.sort(neg_scores)[:8],3))
for thr in np.round(np.linspace(pos_scores.min(), np.sort(neg_scores)[0], 6),3):
    print(f"limiar {thr:.3f}: reconhece {int((pos_scores<=thr).sum())}/{len(calls)} chamadas, dispara em {int((neg_scores<=thr).sum())}/{len(negs)} negativos")
