exec(open("dtw.py").read().split("calls=[]")[0])
import subprocess
def feats_idx(a):
    a=np.append(a[0],a[1:]-0.97*a[:-1]); n=1+max(0,(len(a)-400)//160)
    fr=np.stack([a[i*160:i*160+400] if i*160+400<=len(a) else np.pad(a[i*160:],(0,400-len(a[i*160:]))) for i in range(n)])*WIN
    f=np.log((np.abs(np.fft.rfft(fr,512))**2)@FB.T+1e-6); e=f.mean(1); keep=e>e.max()-6.0
    f=f[keep] if keep.sum()>10 else f
    f=f-f.mean(0); return f/(np.linalg.norm(f,axis=1,keepdims=True)+1e-6)
def prefix_dtw(t,s):
    D=1-t@s.T; n,m=D.shape; C=np.full((n+1,m+1),np.inf); C[0,0]=0
    for i in range(1,n+1):
        for j in range(1,m+1): C[i,j]=D[i-1,j-1]+min(C[i-1,j],C[i,j-1],C[i-1,j-1])
    js=range(max(1,n//2),min(m,2*n)+1)
    return min(C[n,j]/(n+j) for j in js)
seg=[s for s in json.load(open("segs.json")) if s["dur"]>=0.7]
raw_calls=[x[int(max(0,s["start"]-0.15)*16000):int((s["start"]+min(s["dur"],1.6)+0.1)*16000)] for s in seg]
T=[feats_idx(c) for c in raw_calls]
# comando emendado: chamada + 0,15 s + frase em português (voz sintética), recortado nos primeiros 2,2 s
cmd=[]
for f in sorted(glob.glob(f"{__import__('sys').argv[1]}/ww/neg/n*_p.wav"))[:13]:
    r=subprocess.run(["sox",f,"-r","16000","-c","1","-b","16","-t","raw","-"],capture_output=True).stdout
    cmd.append((np.frombuffer(r,np.int16)/32768).astype(np.float32))
print("modo  | dist. chamadas (deixa-uma-de-fora)")
for name,fn,crop in (("pausa: só a chamada, DTW inteira", lambda c,t: dtw(c,t), None),
                     ("emendada, DTW inteira nos 2,2 s", lambda c,t: dtw(c,t), 2.2),
                     ("emendada, DTW de prefixo nos 2,2 s", lambda c,t: prefix_dtw(t,c), 2.2)):
    sc=[]
    for i,rc in enumerate(raw_calls):
        a=rc if crop is None else np.concatenate([rc,np.zeros(2400,np.float32),cmd[i]*(np.abs(rc).max()/max(1e-6,np.abs(cmd[i]).max()))])[:int(crop*16000)]
        f=feats_idx(a); sc.append(min(fn(f,T[j]) for j in range(len(T)) if j!=i))
    sc=np.array(sc); print(f"{name:38s} máx {sc.max():.3f} | reconhece {int((sc<=0.22).sum())}/13 no limiar 0,22")
negs=[]
for f in sorted(glob.glob(f"{__import__('sys').argv[1]}/ww/neg/*.wav")):
    r=subprocess.run(["sox",f,"-r","16000","-c","1","-b","16","-t","raw","-"],capture_output=True).stdout
    a=(np.frombuffer(r,np.int16)/32768).astype(np.float32)
    for st in range(0,max(1,len(a)-35200),12000): negs.append(feats_idx(a[st:st+35200]))
ns=np.array([min(prefix_dtw(t,n) for t in T) for n in negs])
print(f"negativos com DTW de prefixo (2,2 s): menor {ns.min():.3f} | disparos no limiar 0,22: {int((ns<=0.22).sum())}/{len(ns)}")
