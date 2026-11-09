#!/usr/bin/env bash
# Publica uma versão para o botão "Procurar atualização" do app.
# A branch "atualizacoes" do repositório guarda só a última versão: versao.json + o APK.
# O APK publicado NÃO leva o token do Home Assistant (o celular já tem o token guardado).
#   VERSION_NAME=1.7.2 VERSION_CODE=14 NOTAS="o que mudou" ANDROID_JAR=... KOTLINC=... tools/publicar_versao.sh
set -euo pipefail
REPO="${REPO:-duduferaso/jarvis_home}"
BRANCH=atualizacoes
: "${VERSION_NAME:?}" "${VERSION_CODE:?}" "${NOTAS:?}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/android"
unset PROVISION_TOKEN PROVISION_URL
rm -rf build
VERSION_NAME="$VERSION_NAME" VERSION_CODE="$VERSION_CODE" ./build.sh
APK="build/JarvisHome-$VERSION_NAME.apk"
if unzip -l "$APK" | grep -q "assets/provision.json"; then echo "ERRO: o APK contém provision.json (token)"; exit 1; fi
NAME="JarvisHome-$VERSION_NAME-$VERSION_CODE.apk"
W="$(mktemp -d)"
git -C "$W" init -q -b "$BRANCH"
cp "$APK" "$W/$NAME"
python3 - "$W" "$NAME" "$VERSION_NAME" "$VERSION_CODE" "$NOTAS" <<'PY'
import hashlib, json, os, sys
w, name, vn, vc, notes = sys.argv[1:]
p = os.path.join(w, name)
json.dump({"versionCode": int(vc), "versionName": vn, "notes": notes, "apk": name,
           "size": os.path.getsize(p), "sha256": hashlib.sha256(open(p, "rb").read()).hexdigest()},
          open(os.path.join(w, "versao.json"), "w"), ensure_ascii=False, indent=2)
PY
printf '# Atualizações do Jarvis Home\n\nSó a última versão do app. Gerada por `tools/publicar_versao.sh` (sem token).\n' > "$W/README.md"
git -C "$W" add -A
git -C "$W" -c user.name="Claude" -c user.email="noreply@anthropic.com" commit -q -m "Versão $VERSION_NAME ($VERSION_CODE)"
git -C "$W" push -q --force "https://github.com/$REPO" "$BRANCH:$BRANCH"
rm -rf "$W"
echo "Publicado: https://github.com/$REPO/tree/$BRANCH ($NAME)"
