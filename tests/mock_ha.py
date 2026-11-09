"""Home Assistant falso: só o suficiente para testar o app (modo direto) e o orquestrador.
Uso: python3 mock_ha.py  (porta 18123, token HA_FAKE)

Simula:
  - /api/, /api/config e /api/states exigindo o token;
  - WebSocket com auth, várias mensagens na mesma conexão e pings frequentes;
  - config/area_registry/list (cômodos);
  - assist_pipeline/run em duas etapas (stt→stt, depois intent→tts), como o app faz,
    e em uma etapa (stt→tts), como o orquestrador faz;
  - Whisper errando: ouve "Jarvis, ligar luz do zoquart";
  - VAD do HA encerrando a fala depois de 1,5 s de áudio;
  - com conversation_id: resposta de mais de 64 KB;
  - pipeline "quebrada" (erro) e "so_jarvis" (ouve só a palavra de ativação);
  - URL do TTS absoluta com hostname interno;
  - casa: luzes (light e switch), clima, sensores do servidor, input_text.jarvis_aviso,
    agendas (calendar.casa aceita criar/apagar; calendar.feriados só leitura);
  - serviços: homeassistant.toggle, input_text.set_value, calendar.create_event;
  - GET /api/calendars/<id>, WebSocket calendar/event/delete e pipeline só de voz (tts→tts);
  - controle dos testes: POST /test/stt, /test/aviso, /test/nocal, /test/reset; GET /test/state.
"""
import datetime
import uuid

from aiohttp import web

TZ = datetime.timezone(datetime.timedelta(hours=-3))


def fresh():
    return {"lights": {"light.luz_do_quarto": "off", "light.luz_da_sala": "on", "light.luz_da_cozinha": "off",
                       "switch.luz_do_jantar": "on"},
            "aviso": "", "next_stt": [], "calls": [], "events": [], "nocal": False}


S = fresh()
NAMES = {"light.luz_do_quarto": "Luz do Quarto", "light.luz_da_sala": "Luz da Sala",
         "light.luz_da_cozinha": "Luz da Cozinha", "switch.luz_do_jantar": "Luz do Jantar"}


def iso_now():
    return datetime.datetime.now(TZ).isoformat()


def authorized(request):
    return request.headers.get("Authorization") == "Bearer HA_FAKE"


async def api_root(request):
    if not authorized(request):
        return web.json_response({"message": "Unauthorized"}, status=401)
    return web.json_response({"message": "API running."})


async def cfg(request):
    if not authorized(request):
        return web.json_response({"message": "Unauthorized"}, status=401)
    return web.json_response({"version": "2026.11.0"})


async def states(request):
    if not authorized(request):
        return web.json_response({"message": "Unauthorized"}, status=401)
    return web.json_response(all_states())


def all_states():
    ents = [("sensor.temperatura_servidor", "Temperatura do Servidor"),
            ("person.eduardo", "Eduardo"), ("sun.sun", "Sun"),
            ("automation.voz_amortizado", "Voz: consultar quanto foi amortizado"),
            ("automation.voz_gastos", "Voz: consultar gastos (mês atual e próximos)"),
            ("automation.wakeup", "WakeUp")]
    out = [{"entity_id": e, "state": "off", "attributes": {"friendly_name": n}} for e, n in ents]
    out += [{"entity_id": e, "state": st, "attributes": {"friendly_name": NAMES[e]}} for e, st in S["lights"].items()]
    out.append({"entity_id": "weather.casa", "state": "partlycloudy", "last_updated": iso_now(),
                "attributes": {"friendly_name": "Casa", "temperature": 24, "temperature_unit": "°C"}})
    out.append({"entity_id": "sensor.temperatura_do_processador", "state": "78",
                "attributes": {"friendly_name": "Temperatura do processador", "device_class": "temperature",
                               "unit_of_measurement": "°C"}})
    out.append({"entity_id": "sensor.uso_de_disco", "state": "63",
                "attributes": {"friendly_name": "Uso de disco /mnt/dados", "unit_of_measurement": "%"}})
    out.append({"entity_id": "input_text.jarvis_aviso", "state": S["aviso"],
                "attributes": {"friendly_name": "Jarvis aviso", "max": 255}})
    if not S["nocal"]:
        out.append({"entity_id": "calendar.casa", "state": "off",
                    "attributes": {"friendly_name": "Casa", "supported_features": 7}})
    out.append({"entity_id": "calendar.feriados", "state": "off",
                "attributes": {"friendly_name": "Feriados", "supported_features": 0}})
    return out


async def one_state(request):
    if not authorized(request):
        return web.json_response({"message": "Unauthorized"}, status=401)
    eid = request.match_info["eid"]
    for st in all_states():
        if st["entity_id"] == eid:
            return web.json_response(st)
    return web.json_response({"message": "Entity not found."}, status=404)


async def service(request):
    if not authorized(request):
        return web.json_response({"message": "Unauthorized"}, status=401)
    domain, svc = request.match_info["domain"], request.match_info["service"]
    data = await request.json()
    S["calls"].append({"service": f"{domain}.{svc}", "data": data})
    print(f"MOCK HA serviço {domain}.{svc} {data}", flush=True)
    if (domain, svc) in (("homeassistant", "turn_on"), ("homeassistant", "turn_off")):
        ids = data["entity_id"] if isinstance(data["entity_id"], list) else [data["entity_id"]]
        for e in ids:
            if e not in S["lights"]:
                return web.json_response({"message": "Entity not found"}, status=400)
            S["lights"][e] = "on" if svc == "turn_on" else "off"
    elif (domain, svc) == ("homeassistant", "toggle"):
        e = data["entity_id"]
        S["lights"][e] = "off" if S["lights"][e] == "on" else "on"
    elif (domain, svc) == ("input_text", "set_value"):
        if data["entity_id"] == "input_text.jarvis_aviso":
            S["aviso"] = data["value"]
    elif (domain, svc) == ("calendar", "create_event"):
        if data["entity_id"] != "calendar.casa" or S["nocal"]:
            return web.json_response({"message": "Entity does not support creating events"}, status=400)
        ev = {"cal": data["entity_id"], "uid": uuid.uuid4().hex, "summary": data["summary"]}
        if "start_date_time" in data:
            ev.update(start={"dateTime": data["start_date_time"]}, end={"dateTime": data["end_date_time"]})
        else:
            ev.update(start={"date": data["start_date"]}, end={"date": data["end_date"]})
        S["events"].append(ev)
    return web.json_response([])


def ev_bounds(ev):
    def p(o):
        if "dateTime" in o:
            return datetime.datetime.fromisoformat(o["dateTime"])
        return datetime.datetime.fromisoformat(o["date"]).replace(tzinfo=TZ)
    return p(ev["start"]), p(ev["end"])


async def calendar_events(request):
    if not authorized(request):
        return web.json_response({"message": "Unauthorized"}, status=401)
    cal = request.match_info["cal"]
    start = datetime.datetime.fromisoformat(request.query["start"])
    end = datetime.datetime.fromisoformat(request.query["end"])
    out = []
    for ev in S["events"]:
        if ev["cal"] != cal:
            continue
        a, b = ev_bounds(ev)
        if b > start and a < end:
            out.append({"summary": ev["summary"], "start": ev["start"], "end": ev["end"], "uid": ev["uid"],
                        "recurrence_id": None, "rrule": None, "description": None, "location": None})
    return web.json_response(out)


async def t_stt(request):
    S["next_stt"].append((await request.json())["text"])
    return web.json_response({"ok": True})


async def t_aviso(request):
    S["aviso"] = (await request.json())["value"]
    return web.json_response({"ok": True})


async def t_nocal(request):
    S["nocal"] = (await request.json())["on"]
    return web.json_response({"ok": True})


async def t_reset(_):
    S.clear(); S.update(fresh())
    return web.json_response({"ok": True})


async def t_state(_):
    return web.json_response({k: v for k, v in S.items() if k != "next_stt"})


async def ws(request):
    w = web.WebSocketResponse(heartbeat=0.3)  # pings frequentes para testar o pong do cliente
    await w.prepare(request)
    await w.send_json({"type": "auth_required", "ha_version": "2026.11.0"})
    m = await w.receive_json()
    if m.get("access_token") != "HA_FAKE":
        await w.send_json({"type": "auth_invalid", "message": "Invalid access token or password"})
        await w.close()
        return w
    await w.send_json({"type": "auth_ok", "ha_version": "2026.11.0"})

    while True:
        msg = await w.receive()
        if msg.type != web.WSMsgType.TEXT:
            break
        req = msg.json()
        i = req["id"]

        async def ev(t, d=None, i=i):
            await w.send_json({"id": i, "type": "event", "event": {"type": t, "data": d or {}}})

        if req["type"] == "calendar/event/delete":
            before = len(S["events"])
            S["events"] = [e for e in S["events"] if e["uid"] != req["uid"]]
            ok = len(S["events"]) < before
            print(f"MOCK HA apagou evento {req['uid']}: {ok}", flush=True)
            await w.send_json({"id": i, "type": "result", "success": ok,
                               **({} if ok else {"error": {"code": "not_found", "message": "Event not found"}})})
            continue

        if req["type"] == "config/area_registry/list":
            await w.send_json({"id": i, "type": "result", "success": True, "result": [
                {"area_id": "quarto", "name": "Quarto", "aliases": ["dormitório"]},
                {"area_id": "sala", "name": "Sala", "aliases": []},
                {"area_id": "sala_jantar", "name": "Sala de Jantar", "aliases": []}]})
            continue

        await w.send_json({"id": i, "type": "result", "success": True, "result": None})
        if req.get("pipeline") == "quebrada":
            await ev("run-start", {"runner_data": {"stt_binary_handler_id": 1}})
            await ev("error", {"code": "stt-provider-missing", "message": "Speech-to-text engine not found"})
            await ev("run-end")
            continue

        start, end = req["start_stage"], req["end_stage"]
        if start == "tts":
            print(f"MOCK HA voz para: {req['input']['text']!r}", flush=True)
            await ev("run-start", {"runner_data": {}})
            await ev("tts-start", {"tts_input": req["input"]["text"]})
            await ev("tts-end", {"tts_output": {"url": "http://homeassistant.local:8123/api/tts_proxy/abc.mp3",
                                                 "mime_type": "audio/mpeg"}})
            await ev("run-end")
            continue
        if start == "stt":
            await ev("run-start", {"runner_data": {"stt_binary_handler_id": 1}})
            total, vad_sent = 0, False
            while True:
                m2 = await w.receive()
                if m2.type != web.WSMsgType.BINARY:
                    break
                b = m2.data
                assert b[0] == 1, "prefixo do canal binário errado"
                if len(b) == 1:
                    break
                total += len(b) - 1
                if total >= 48000 and not vad_sent:
                    vad_sent = True
                    await ev("stt-vad-end", {"timestamp": 1500})
            print(f"MOCK HA recebeu {total} bytes de áudio ({start}->{end}); "
                  f"conversation_id={req.get('conversation_id')}", flush=True)
            if end == "stt":
                heard = "Jarvis." if req.get("pipeline") == "so_jarvis" else (
                    S["next_stt"].pop(0) if S["next_stt"] else "Jarvis, ligar luz do zoquart.")
                await ev("stt-end", {"stt_output": {"text": heard}})
                await ev("run-end")
                continue
            text = "acenda a luz da cozinha"  # caminho do orquestrador (stt→tts numa etapa só)
            await ev("stt-end", {"stt_output": {"text": text}})
        else:
            text = req["input"]["text"]
            print(f"MOCK HA intent recebeu: {text!r}; conversation_id={req.get('conversation_id')}", flush=True)

        if text.lower().startswith("jarvis") or "zoquart" in text or " da." in text:
            rtype, speech = "error", "Desculpe, não entendi"
        elif "quarto" in text:
            rtype, speech = "action_done", "Luz do quarto ligada"
        else:
            rtype, speech = "action_done", "Luz da cozinha ligada"
        if req.get("conversation_id"):
            speech = "CONTEXTO " + req["conversation_id"] + " " + ("bla " * 18000)
        await ev("intent-start")
        await ev("intent-end", {"intent_output": {
            "response": {"response_type": rtype, "speech": {"plain": {"speech": speech}},
                         "data": {"code": "no_intent_match"} if rtype == "error" else {}},
            "conversation_id": "conv123", "continue_conversation": False}})
        await ev("tts-end", {"tts_output": {"url": "http://homeassistant.local:8123/api/tts_proxy/abc.mp3",
                                             "mime_type": "audio/mpeg"}})
        await ev("run-end")
        if start == "stt" and end == "tts":
            await w.close()  # o orquestrador abre uma conexão por interação
            break
    return w


async def tts(_):
    return web.Response(body=b"ID3FAKEMP3" * 100, content_type="audio/mpeg")


app = web.Application()
app.router.add_get("/api/", api_root)
app.router.add_get("/api/config", cfg)
app.router.add_get("/api/states", states)
app.router.add_get("/api/websocket", ws)
app.router.add_get("/api/tts_proxy/abc.mp3", tts)
app.router.add_get("/api/states/{eid}", one_state)
app.router.add_post("/api/services/{domain}/{service}", service)
app.router.add_get("/api/calendars/{cal}", calendar_events)
app.router.add_post("/test/stt", t_stt)
app.router.add_post("/test/aviso", t_aviso)
app.router.add_post("/test/nocal", t_nocal)
app.router.add_post("/test/reset", t_reset)
app.router.add_get("/test/state", t_state)
web.run_app(app, port=18123, print=None)
