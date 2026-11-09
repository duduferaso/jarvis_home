#!/usr/bin/env bash
# Build do Jarvis Home sem Gradle (o mesmo processo usado para gerar o APK entregue).
#
# Requisitos (Debian/Ubuntu):
#   apt install openjdk-21-jdk-headless aapt dalvik-exchange zipalign apksigner
#   Kotlin 1.9.25 (github.com/JetBrains/kotlin/releases): o 2.x quebra o app (ver passo 2/6)
#   Kotlin 2.1.21: https://github.com/JetBrains/kotlin/releases/tag/v2.1.21 (kotlin-compiler-2.1.21.zip)
#   android.jar API 33: https://github.com/Sable/android-platforms (android-33/android.jar)
#
# Uso:  ANDROID_JAR=~/sdk/android-33.jar KOTLINC=~/kotlinc/bin/kotlinc ./build.sh
# Guarde o jarvis-release.jks: atualizações do APK precisam da MESMA chave.
set -euo pipefail
cd "$(dirname "$0")"

ANDROID_JAR="${ANDROID_JAR:?defina ANDROID_JAR}"
KOTLINC="${KOTLINC:-kotlinc}"
KOTLIN_HOME="$(cd "$(dirname "$(readlink -f "$(command -v "$KOTLINC")")")/.." && pwd)"
SHERPA_AAR="app/libs/sherpa-onnx-static-link-onnxruntime-1.12.9.aar"
KEYSTORE="${KEYSTORE:-jarvis-release.jks}"
KS_PASS="${KS_PASS:-jarvishome}"
VERSION_CODE="${VERSION_CODE:-1}"
VERSION_NAME="${VERSION_NAME:-1.0.0}"
MAIN=app/src/main
OUT=build
rm -rf "$OUT" && mkdir -p "$OUT"/{sherpa,stage/lib/arm64-v8a}

echo "== 1/6 dependências"
unzip -q -o "$SHERPA_AAR" classes.jar -d "$OUT/sherpa"
cp "$KOTLIN_HOME/lib/kotlin-stdlib.jar" "$OUT/kotlin-stdlib.jar"
zip -q -d "$OUT/kotlin-stdlib.jar" 'META-INF/versions/*' || true

echo "== 2/6 Kotlin"
# IMPORTANTE: use o Kotlin 1.9.x. A biblioteca padrão do Kotlin 2.x usa invokedynamic
# (LambdaMetafactory), que o dalvik-exchange não converte e o Android não tem: o app quebra
# em tempo de execução (NoSuchMethodError metafactory). A checagem abaixo impede isso.
"$KOTLINC" -J-Xmx3g $(find "$MAIN/java" -name '*.kt') \
  -jvm-target 1.8 -no-reflect -no-stdlib \
  -cp "$ANDROID_JAR:$OUT/sherpa/classes.jar:$OUT/kotlin-stdlib.jar" \
  -Xlambdas=class -Xsam-conversions=class -Xstring-concat=inline \
  -d "$OUT/app.jar" 2>&1 | grep -v '^warning' || true
test -f "$OUT/app.jar"
python3 - "$OUT/app.jar" "$OUT/kotlin-stdlib.jar" "$OUT/sherpa/classes.jar" <<'PY'
import sys, zipfile
OK_STDLIB = {"kotlin/comparisons/ComparisonsKt__ComparisonsKt.class", "kotlin/streams/jdk8/StreamsKt.class"}
bad = []
for jar in sys.argv[1:]:
    z = zipfile.ZipFile(jar)
    for n in z.namelist():
        if not n.endswith(".class"): continue
        d = z.read(n)
        if b"LambdaMetafactory" in d or b"StringConcatFactory" in d:
            if not ("stdlib" in jar and n in OK_STDLIB): bad.append(jar.split("/")[-1] + ": " + n)
if bad:
    print("ERRO: invokedynamic não suportado no Android:\n  " + "\n  ".join(bad[:20]))
    sys.exit(1)
print("   sem invokedynamic (ok para o Android)")
PY
# Os únicos métodos da stdlib 1.9 com invokedynamic: o app não pode chamá-los.
if javap -c -classpath "$OUT/app.jar" $(unzip -Z1 "$OUT/app.jar" | grep '\.class$' | sed 's/\.class$//; s#/#.#g') 2>/dev/null \
   | grep -E 'kotlin/comparisons/ComparisonsKt\.(compareBy:\(\[|then:|thenDescending:|nullsFirst:|nullsLast:)'; then
  echo "ERRO: o app chama um comparador da stdlib que usa invokedynamic (troque por sortedBy/compareValues)"; exit 1
fi

echo "== 3/6 DEX"
dalvik-exchange --dex --min-sdk-version=26 --output="$OUT/stage/classes.dex" \
  "$OUT/app.jar" "$OUT/kotlin-stdlib.jar" "$OUT/sherpa/classes.jar"

echo "== 4/6 recursos + manifesto"
# Configuração de fábrica opcional (não fica no código-fonte):
#   PROVISION_URL=http://192.168.18.108:8123 PROVISION_TOKEN=eyJ... ./build.sh
cp -r "$MAIN/assets" "$OUT/assets"
if [ -n "${PROVISION_TOKEN:-}" ]; then
  python3 - "$OUT/assets/provision.json" <<'PY'
import json, os, sys, hashlib
tok = os.environ["PROVISION_TOKEN"].strip()
url = os.environ.get("PROVISION_URL", "")
pid = hashlib.sha256((url + tok).encode()).hexdigest()[:12]
json.dump({"id": pid, "direct": True, "url": url, "token": tok}, open(sys.argv[1], "w"))
print("   configuração de fábrica incluída (id " + pid + ")")
PY
fi
aapt package -f -M "$MAIN/AndroidManifest.xml" -S "$MAIN/res" -A "$OUT/assets" -I "$ANDROID_JAR" \
  --min-sdk-version 26 --target-sdk-version 33 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  -0 onnx -F "$OUT/unsigned.apk"

echo "== 5/6 código + biblioteca nativa"
cp "$MAIN/jniLibs/arm64-v8a/libsherpa-onnx-jni.so" "$OUT/stage/lib/arm64-v8a/"
( cd "$OUT/stage" && aapt add -k ../unsigned.apk classes.dex >/dev/null \
  && aapt add ../unsigned.apk lib/arm64-v8a/libsherpa-onnx-jni.so >/dev/null )

echo "== 6/6 alinhar e assinar"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -alias jarvis -keyalg RSA -keysize 3072 -validity 10000 \
    -dname "CN=Jarvis Home, O=Casa, C=BR" >/dev/null 2>&1
fi
zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
apksigner sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --ks-key-alias jarvis \
  --out "$OUT/JarvisHome-$VERSION_NAME.apk" "$OUT/aligned.apk"
apksigner verify "$OUT/JarvisHome-$VERSION_NAME.apk"
ls -lh "$OUT/JarvisHome-$VERSION_NAME.apk"
