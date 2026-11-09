#!/usr/bin/env bash
# Agenda por voz, luzes, clima, alertas e avisos contra o HA simulado (código real do app, na JVM).
# Requer: kotlinc (KOTLINC=...), java, python3 com aiohttp.
set -e
cd "$(dirname "$0")"
KOTLINC="${KOTLINC:-kotlinc}"
KHOME="$(cd "$(dirname "$(readlink -f "$(command -v "$KOTLINC")")")/.." && pwd)"
A=../android/app/src/main/java/br/com/jarvishome
T=$(mktemp -d)
sed '/^object Backends/,$d; /import br.com.jarvishome.settings.Prefs/d; /import br.com.jarvishome.wakeword/d' $A/network/Backend.kt > $T/Backend.kt
cp $A/network/HaDirectBackend.kt $A/network/MiniWebSocket.kt $A/network/TextCorrector.kt $A/network/CommandMatcher.kt $A/commands/LightCommands.kt $A/domain/AssistantState.kt \
   $A/calendar/*.kt $A/home/HomeApi.kt $A/home/HomeParser.kt jvm/OrgJson.kt jvm/JLogStub.kt jvm_casa/CasaTest.kt $T/
"$KOTLINC" $T/*.kt -d $T/test.jar 2>&1 | grep error || true
python3 mock_ha.py > $T/mock.log 2>&1 & M=$!
trap 'kill $M 2>/dev/null; rm -rf $T' EXIT
sleep 1.5
java -Duser.timezone=America/Sao_Paulo -Dstdout.encoding=UTF-8 -cp "$T/test.jar:$KHOME/lib/kotlin-stdlib.jar" CasaTestKt
