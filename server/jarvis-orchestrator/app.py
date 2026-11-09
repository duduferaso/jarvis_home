"""
Jarvis Home - Orquestrador local (protocolo v1)

Ponte entre o terminal Android (Redmi 10) e a pipeline Assist do Home Assistant.

    Redmi --HTTP (PCM 16 kHz)--> orquestrador --WebSocket--> Home Assistant
                                                  |-> Whisper (STT, via Wyoming)
                                                  |-> Assist e/ou IA local (agente)
                                                  |-> Piper (TTS, via Wyoming)

Por que existe:
  * O token do Home Assistant fica SOMENTE no servidor (o celular usa um token
    próprio de terminal, revogável).
  * O app não depende do modelo de IA: troca-se o agente na pipeline do HA.
  * Eventos da pipeline viram um contrato simples e versionado para o app.

Endpoints (todos exigem "Authorization: Bearer <token do terminal>"):
  GET  /v1/health              estado do orquestrador e do Home Assistant
  POST /v1/voice?rate=16000    corpo: PCM s16le mono em streaming (chunked)
                               resposta: NDJSON (um evento JSON por linha)
  POST /v1/text                corpo: {"text": "..."}  (testes / entrada digitada)
  GET  /v1/audio/{id}          áudio da resposta gerado pelo Piper
"""

import asyncio
import hmac
import json
import logging
import os
import time
import uuid
from urllib.parse import urlsplit

import aiohttp
from aiohttp import web

PROTOCOL_VERSION = 1

HA_URL = os.environ.get("HA_URL", "http://127.0.0.1:8123").rstrip("/")
HA_TOKEN = os.environ.get("HA_TOKEN", "")
HA_PIPELINE_ID = os.environ.get("HA_PIPELINE_ID") or None
LISTEN_HOST = os.environ.get("JARVIS_HOST", "0.0.0.0")
LISTEN_PORT = int(os.environ.get("JARVIS_PORT", "8765"))
CONVERSATION_TTL = int(os.environ.get("JARVIS_CONVERSATION_TTL", "300"))
AUDIO_TTL = int(os.environ.get("JARVIS_AUDIO_TTL", "120"))
MAX_UTTERANCE_S = int(os.environ.get("JARVIS_MAX_UTTERANCE_SECONDS", "15"))
RUN_TIMEOUT_S = int(os.environ.get("JARVIS_RUN_TIMEOUT_SECONDS", "60"))
LOG_TRANSCRIPTS = os.environ.get("JARVIS_LOG_TRANSCRIPTS", "false").lower() == "true"

logging.basicConfig(
    level=os.environ.get("JARVIS_LOG_LEVEL", "INFO"),
    format="%(asctime)s %(levelname)s %(message)s",
)
log = logging.getLogger("jarvis")


def _parse_terminals(raw: str) -> dict:
    """JARVIS_TERMINALS="redmi10:TOKEN,cozinha:OUTRO_TOKEN"."""
    terminals = {}
    for item in filter(None, (p.strip() for p in raw.split(","))):
        name, _, token = item.partition(":")
        if name and len(token) >= 16:
            terminals[name.strip()] = token.strip()
        else:
            log.warning("Terminal ignorado (token ausente ou com menos de 16 caracteres): %s", name)
    return terminals


TERMINALS = _parse_terminals(os.environ.get("JARVIS_TERMINALS", ""))

# Estado em memória (nunca guarda áudio do usuário; só o áudio de resposta, por pouco tempo)
_conversations: dict = {}   # terminal -> (conversation_id, expira_em)
_audio_cache: dict = {}     # audio_id -> {"future": Future, "expires": ts}
_health_cache = {"ts": 0.0, "value": None}


class HAError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


# --------------------------------------------------------------------------- auth

def authenticate(request: web.Request) -> str:
    header = request.headers.get("Authorization", "")
    token = header[7:].strip() if header.lower().startswith("bearer ") else ""
    claimed = request.headers.get("X-Terminal-Id", "")
    for name, expected in TERMINALS.items():
        if hmac.compare_digest(token.encode(), expected.encode()):
            if claimed and claimed != name:
                log.warning("Terminal declarou id '%s' mas o token pertence a '%s'", claimed, name)
            return name
    raise web.HTTPUnauthorized(
        text=json.dumps({"type": "error", "code": "unauthorized", "message": "Token de terminal inválido"}),
        content_type="application/json",
    )


# --------------------------------------------------------------------------- utilidades

def _ws_url() -> str:
    if HA_URL.startswith("https://"):
        return "wss://" + HA_URL[len("https://"):] + "/api/websocket"
    if HA_URL.startswith("http://"):
        return "ws://" + HA_URL[len("http://"):] + "/api/websocket"
    return HA_URL + "/api/websocket"


def _cleanup():
    now = time.time()
    for k in [k for k, (_, exp) in _conversations.items() if exp < now]:
        _conversations.pop(k, None)
    for k in [k for k, v in _audio_cache.items() if v["expires"] < now]:
        entry = _audio_cache.pop(k, None)
        if entry and not entry["future"].done():
            entry["future"].cancel()


def _speech_from_intent(intent_output: dict) -> str:
    try:
        return intent_output["response"]["speech"]["plain"]["speech"] or ""
    except (KeyError, TypeError):
        return ""


async def _fetch_tts(session: aiohttp.ClientSession, url: str) -> tuple:
    # O HA pode devolver URL absoluta com internal_url/hostname interno; usa sempre HA_URL.
    if url.startswith("http"):
        parts = urlsplit(url)
        url = parts.path + (("?" + parts.query) if parts.query else "")
    full = HA_URL + url
    async with session.get(full, headers={"Authorization": f"Bearer {HA_TOKEN}"},
                           timeout=aiohttp.ClientTimeout(total=30)) as resp:
        if resp.status != 200:
            raise HAError("tts-fetch-failed", f"HTTP {resp.status} ao buscar áudio da resposta")
        return await resp.read(), resp.headers.get("Content-Type", "audio/mpeg")


# --------------------------------------------------------------------------- pipeline

async def run_pipeline(app, terminal, start_stage, pipeline_input, emit, audio_source=None):
    """Executa assist_pipeline/run no HA e traduz os eventos para o contrato do app."""
    session: aiohttp.ClientSession = app["http"]
    conv = _conversations.get(terminal)
    conversation_id = conv[0] if conv and conv[1] > time.time() else None
    stop_audio = asyncio.Event()
    sender = None

    async def send_audio(ws, handler_id: int):
        prefix = bytes([handler_id])
        sent = 0
        limit = MAX_UTTERANCE_S * 16000 * 2
        try:
            async for chunk in audio_source:
                if stop_audio.is_set() or sent >= limit:
                    break
                if chunk:
                    await ws.send_bytes(prefix + chunk)
                    sent += len(chunk)
        finally:
            if not ws.closed:
                await ws.send_bytes(prefix)  # fim do áudio
            log.debug("[%s] áudio enviado ao HA: %.1f s", terminal, sent / 32000)

    async with session.ws_connect(_ws_url(), heartbeat=30, max_msg_size=0) as ws:
        hello = await ws.receive_json()
        if hello.get("type") != "auth_required":
            raise HAError("ha-protocol", "Resposta inesperada do Home Assistant")
        await ws.send_json({"type": "auth", "access_token": HA_TOKEN})
        auth = await ws.receive_json()
        if auth.get("type") != "auth_ok":
            raise HAError("ha-auth", "Token do Home Assistant recusado")

        req = {"id": 1, "type": "assist_pipeline/run", "start_stage": start_stage,
               "end_stage": "tts", "input": pipeline_input}
        if HA_PIPELINE_ID:
            req["pipeline"] = HA_PIPELINE_ID
        if conversation_id:
            req["conversation_id"] = conversation_id
        await ws.send_json(req)

        try:
            async for msg in ws:
                if msg.type != aiohttp.WSMsgType.TEXT:
                    if msg.type in (aiohttp.WSMsgType.CLOSED, aiohttp.WSMsgType.ERROR):
                        raise HAError("ha-disconnected", "Conexão com o Home Assistant caiu")
                    continue
                data = json.loads(msg.data)
                if data.get("type") == "result":
                    if not data.get("success", False):
                        err = data.get("error") or {}
                        raise HAError(err.get("code", "ha-error"), err.get("message", "Falha na pipeline"))
                    continue
                if data.get("type") != "event":
                    continue

                event = data.get("event") or {}
                etype = event.get("type")
                edata = event.get("data") or {}

                if etype == "run-start":
                    handler_id = (edata.get("runner_data") or {}).get("stt_binary_handler_id")
                    if audio_source is not None and handler_id is not None:
                        sender = asyncio.create_task(send_audio(ws, handler_id))
                    await emit({"type": "ready"})
                elif etype == "stt-vad-end":
                    stop_audio.set()
                    await emit({"type": "state", "state": "thinking"})
                elif etype == "stt-end":
                    stop_audio.set()
                    text = ((edata.get("stt_output") or {}).get("text") or "").strip()
                    if LOG_TRANSCRIPTS:
                        log.info("[%s] transcrição: %s", terminal, text)
                    await emit({"type": "transcription", "text": text})
                    await emit({"type": "state", "state": "thinking"})
                elif etype == "intent-end":
                    out = edata.get("intent_output") or {}
                    rtype = (out.get("response") or {}).get("response_type", "")
                    if out.get("conversation_id"):
                        _conversations[terminal] = (out["conversation_id"], time.time() + CONVERSATION_TTL)
                    speech = _speech_from_intent(out)
                    if LOG_TRANSCRIPTS:
                        log.info("[%s] resposta (%s): %s", terminal, rtype, speech)
                    await emit({"type": "response", "text": speech, "response_type": rtype,
                                "continue_conversation": bool(out.get("continue_conversation", False))})
                    if rtype == "action_done":
                        await emit({"type": "action_result", "success": True, "description": speech})
                elif etype == "tts-end":
                    tts = edata.get("tts_output") or {}
                    url = tts.get("url")
                    if url:
                        audio_id = uuid.uuid4().hex
                        fut = asyncio.ensure_future(_fetch_tts(session, url))
                        _audio_cache[audio_id] = {"future": fut, "expires": time.time() + AUDIO_TTL}
                        await emit({"type": "audio", "url": f"/v1/audio/{audio_id}",
                                    "mime": tts.get("mime_type", "audio/mpeg")})
                elif etype == "error":
                    stop_audio.set()
                    await emit({"type": "error", "code": edata.get("code", "pipeline-error"),
                                "message": edata.get("message", "Erro na pipeline")})
                    break
                elif etype == "run-end":
                    break
        finally:
            stop_audio.set()
            if sender:
                try:
                    await asyncio.wait_for(sender, 2)
                except (asyncio.TimeoutError, Exception):  # noqa: BLE001
                    sender.cancel()


async def _stream_run(request, terminal, start_stage, pipeline_input, audio_source=None):
    resp = web.StreamResponse(status=200, headers={
        "Content-Type": "application/x-ndjson; charset=utf-8",
        "Cache-Control": "no-cache",
        "X-Jarvis-Protocol": str(PROTOCOL_VERSION),
    })
    await resp.prepare(request)
    session_id = uuid.uuid4().hex[:12]

    async def emit(obj):
        obj.setdefault("session", session_id)
        obj.setdefault("v", PROTOCOL_VERSION)
        await resp.write((json.dumps(obj, ensure_ascii=False) + "\n").encode("utf-8"))

    started = time.monotonic()
    try:
        await asyncio.wait_for(
            run_pipeline(request.app, terminal, start_stage, pipeline_input, emit, audio_source),
            RUN_TIMEOUT_S)
    except HAError as e:
        log.warning("[%s] erro HA: %s %s", terminal, e.code, e.message)
        await emit({"type": "error", "code": e.code, "message": e.message})
    except asyncio.TimeoutError:
        await emit({"type": "error", "code": "timeout", "message": "A casa demorou demais para responder"})
    except aiohttp.ClientError as e:
        log.warning("[%s] HA inacessível: %s", terminal, e)
        await emit({"type": "error", "code": "ha-unreachable", "message": "Home Assistant indisponível"})
    finally:
        await emit({"type": "done", "elapsed_ms": int((time.monotonic() - started) * 1000)})
        await resp.write_eof()
    return resp


# --------------------------------------------------------------------------- handlers

async def handle_health(request):
    terminal = authenticate(request)
    now = time.time()
    if now - _health_cache["ts"] > 10:
        ha_ok, version = False, None
        try:
            async with request.app["http"].get(
                    f"{HA_URL}/api/config", headers={"Authorization": f"Bearer {HA_TOKEN}"},
                    timeout=aiohttp.ClientTimeout(total=4)) as r:
                if r.status == 200:
                    ha_ok, version = True, (await r.json()).get("version")
        except Exception:  # noqa: BLE001
            pass
        _health_cache.update(ts=now, value={"home_assistant": ha_ok, "ha_version": version})
    return web.json_response({"ok": True, "protocol": PROTOCOL_VERSION, "terminal": terminal,
                              **_health_cache["value"]})


async def handle_voice(request):
    terminal = authenticate(request)
    _cleanup()
    rate = int(request.query.get("rate", "16000"))
    if rate != 16000:
        raise web.HTTPBadRequest(text="Somente PCM s16le mono 16000 Hz é suportado")
    log.info("[%s] nova solicitação de voz", terminal)

    async def audio_source():
        while True:
            chunk = await request.content.readany()
            if not chunk:
                return
            yield chunk

    resp = await _stream_run(request, terminal, "stt", {"sample_rate": 16000}, audio_source())
    # Se o HA encerrou a fala antes do app, descarta o restante do upload.
    try:
        while await request.content.readany():
            pass
    except Exception:  # noqa: BLE001
        pass
    return resp


async def handle_text(request):
    terminal = authenticate(request)
    _cleanup()
    try:
        body = await request.json()
        text = str(body.get("text", "")).strip()
    except Exception:  # noqa: BLE001
        text = ""
    if not text:
        raise web.HTTPBadRequest(text='Envie {"text": "..."}')
    return await _stream_run(request, terminal, "intent", {"text": text})


async def handle_audio(request):
    authenticate(request)
    entry = _audio_cache.get(request.match_info["audio_id"])
    if not entry:
        raise web.HTTPNotFound(text="Áudio expirado ou inexistente")
    try:
        data, ctype = await asyncio.wait_for(asyncio.shield(entry["future"]), 30)
    except Exception as e:  # noqa: BLE001
        log.warning("Falha ao obter áudio de resposta: %s", e)
        raise web.HTTPBadGateway(text="Falha ao gerar o áudio da resposta")
    return web.Response(body=data, content_type=ctype.split(";")[0])


async def on_startup(app):
    app["http"] = aiohttp.ClientSession()
    if not HA_TOKEN:
        log.error("HA_TOKEN não definido: a pipeline não vai funcionar")
    if not TERMINALS:
        log.error("Nenhum terminal configurado em JARVIS_TERMINALS")
    log.info("Jarvis orquestrador v%d em %s:%d -> %s (terminais: %s)",
             PROTOCOL_VERSION, LISTEN_HOST, LISTEN_PORT, HA_URL, ", ".join(TERMINALS) or "-")


async def on_cleanup(app):
    await app["http"].close()


def build_app() -> web.Application:
    app = web.Application(client_max_size=4 * 1024 * 1024)
    app.router.add_get("/v1/health", handle_health)
    app.router.add_post("/v1/voice", handle_voice)
    app.router.add_post("/v1/text", handle_text)
    app.router.add_get("/v1/audio/{audio_id}", handle_audio)
    app.on_startup.append(on_startup)
    app.on_cleanup.append(on_cleanup)
    return app


if __name__ == "__main__":
    web.run_app(build_app(), host=LISTEN_HOST, port=LISTEN_PORT, access_log=None)
