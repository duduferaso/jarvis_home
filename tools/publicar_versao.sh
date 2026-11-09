#!/usr/bin/env bash
# Publica uma versão no GitHub Releases para o botão "Procurar atualização" do app.
# O APK publicado NÃO leva o token do Home Assistant (o celular já tem o token guardado).
#   VERSION_NAME=1.7.1 VERSION_CODE=13 NOTAS="o que mudou" ANDROID_JAR=... KOTLINC=... tools/publicar_versao.sh
set -euo pipefail
REPO="${REPO:-duduferaso/jarvis_home}"
: "${VERSION_NAME:?}" "${VERSION_CODE:?}" "${NOTAS:?}"
cd "$(dirname "$0")/../android"
unset PROVISION_TOKEN PROVISION_URL
rm -rf build
VERSION_NAME="$VERSION_NAME" VERSION_CODE="$VERSION_CODE" ./build.sh
APK="build/JarvisHome-$VERSION_NAME.apk"
if unzip -l "$APK" | grep -q "assets/provision.json"; then echo "ERRO: o APK contém provision.json (token)"; exit 1; fi
if unzip -p "$APK" | grep -aq "eyJhbGci"; then echo "ERRO: o APK parece conter um token"; exit 1; fi
OUT="build/JarvisHome-$VERSION_NAME-$VERSION_CODE.apk"
cp "$APK" "$OUT"
gh release create "v$VERSION_NAME" "$OUT" --repo "$REPO" --title "Jarvis Home $VERSION_NAME" --notes "$NOTAS"
echo "Publicado: https://github.com/$REPO/releases/tag/v$VERSION_NAME"
