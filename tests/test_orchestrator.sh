#!/usr/bin/env bash
# Teste ponta a ponta do orquestrador contra o HA falso. Requer: pip install aiohttp
set -e
cd "$(dirname "$0")"
python3 mock_ha.py & M=$!
HA_URL=http://127.0.0.1:18123 HA_TOKEN=HA_FAKE JARVIS_TERMINALS="redmi10:segredo-de-teste-1234567" \
  JARVIS_LOG_TRANSCRIPTS=true python3 ../server/jarvis-orchestrator/app.py & O=$!
trap 'kill $M $O 2>/dev/null' EXIT
sleep 2
H='Authorization: Bearer segredo-de-teste-1234567'
echo "--- health";  curl -sf -H "$H" localhost:8765/v1/health; echo
echo "--- sem token: $(curl -s -o /dev/null -w '%{http_code}' localhost:8765/v1/health) (esperado 401)"
echo "--- voz (2 s de PCM)"
head -c 64000 /dev/urandom | curl -s -N -H "$H" -H "Transfer-Encoding: chunked" --data-binary @- \
  "localhost:8765/v1/voice?rate=16000" | tee /tmp/jarvis_ev.txt
U=$(python3 -c "import json;print([json.loads(l) for l in open('/tmp/jarvis_ev.txt') if '\"audio\"' in l][0]['url'])")
echo "--- áudio: $(curl -s -H "$H" localhost:8765$U -o /dev/null -w '%{http_code} %{size_download} bytes')"
echo "--- texto"; curl -s -N -H "$H" -H "Content-Type: application/json" -d '{"text":"apague a luz"}' localhost:8765/v1/text
