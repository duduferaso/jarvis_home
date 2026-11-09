#!/usr/bin/env bash
# Roda o cliente direto do app (MiniWebSocket + HaDirectBackend, código real) na JVM contra o HA falso.
# Requer: kotlinc (KOTLINC=...), java, python3 com aiohttp.
set -e
cd "$(dirname "$0")"
KOTLINC="${KOTLINC:-kotlinc}"
KHOME="$(cd "$(dirname "$(readlink -f "$(command -v "$KOTLINC")")")/.." && pwd)"
A=../android/app/src/main/java/br/com/jarvishome
T=$(mktemp -d)
sed '/^object Backends/,$d; /import br.com.jarvishome.settings.Prefs/d; /import br.com.jarvishome.wakeword/d' $A/network/Backend.kt > $T/Backend.kt
cp $A/network/HaDirectBackend.kt $A/network/MiniWebSocket.kt $A/network/TextCorrector.kt $A/network/CommandMatcher.kt $A/commands/LightCommands.kt $A/domain/AssistantState.kt \
   $A/calendar/*.kt $A/home/HomeApi.kt $A/home/HomeParser.kt jvm/*.kt $T/
"$KOTLINC" $T/*.kt -d $T/test.jar 2>&1 | grep error || true
python3 mock_ha.py & M=$!
trap 'kill $M 2>/dev/null; rm -rf $T' EXIT
sleep 1.5
java -Dstdout.encoding=UTF-8 -cp "$T/test.jar:$KHOME/lib/kotlin-stdlib.jar" MainKt
