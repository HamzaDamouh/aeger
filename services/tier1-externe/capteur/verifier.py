"""Vérification de bout en bout d'AEGER, depuis le PC (Tier 1).

1. Connexion d'un médecin (Keycloak, client de test aeger-tests) via NGINX.
2. Ouverture du WebSocket des alertes, comme l'application web.
3. Envoi d'une mesure critique par un bracelet haut risque (MQTT) puis par un bracelet standard (HTTPS) :
   mesure du délai entre l'envoi et l'arrivée de l'alerte dans le « navigateur » (objectif < 5 s).
4. Historique patient (vitals) et alertes récentes (notification).
5. Contrôles de sécurité : sans jeton, mauvais rôle, mauvais certificat.

  python verifier.py
"""
import asyncio
import json
import os
import ssl
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

import websockets

import simulateur as sim

HOST = os.environ.get("AEGER_HOST", "192.168.56.10")   # adresse publique d'AEGER
HERE = Path(__file__).resolve().parent
CERTS = HERE / "certs"
CTX = ssl.create_default_context(cafile=str(CERTS / "ca.pem"))
results = []


def check(name, ok, detail=""):
    results.append(ok)
    print(f"  [{'OK' if ok else 'ÉCHEC'}] {name}{' - ' + detail if detail else ''}", flush=True)


def token(user, password):
    data = urllib.parse.urlencode({"grant_type": "password", "client_id": "aeger-tests", "username": user,
                                   "password": password, "scope": "openid"}).encode()
    req = urllib.request.Request(f"https://{HOST}/auth/realms/aeger/protocol/openid-connect/token", data=data)
    return json.load(urllib.request.urlopen(req, context=CTX))["access_token"]


def get(path, tok=None):
    req = urllib.request.Request(f"https://{HOST}{path}", headers={"Authorization": f"Bearer {tok}"} if tok else {})
    try:
        with urllib.request.urlopen(req, context=CTX, timeout=15) as r:
            return r.status, json.load(r)
    except urllib.error.HTTPError as e:
        return e.code, None


class Args:  # paramètres attendus par les fonctions d'envoi du simulateur
    host, port_mqtt, port_https, certs = HOST, 8883, 8443, CERTS


def send_critical(patient):
    """Envoie une mesure critique ; renvoie (patient_id, instant d'envoi)."""
    n = int(patient[1:])
    bracelet = f"hr-{n:05d}" if n <= 1000 else f"std-{n - 1000:05d}"
    p = sim.Patient(bracelet, __import__("random").Random())
    m = p.measure(0, force="critical")
    if p.type == "standard":
        m["anomaly"] = True
    stats = sim.Stats()
    t0 = time.time()
    if p.type == "high_risk":
        import threading
        done = threading.Event()
        c = sim.make_mqtt_client(Args, bracelet, stats)
        c.on_publish = lambda *a: done.set()
        c.connect(HOST, 8883, keepalive=30)
        c.loop_start()
        c.publish(f"aeger/vitals/{bracelet}", json.dumps(m), qos=1)
        done.wait(10)
        c.disconnect()
        c.loop_stop()
    else:
        sim.post_measures(Args, bracelet, [m], stats, anomaly=True)
    return m, t0


async def wait_alert(ws, patient, t0, timeout=15):
    end = time.time() + timeout
    while time.time() < end:
        try:
            msg = await asyncio.wait_for(ws.recv(), timeout=end - time.time())
        except asyncio.TimeoutError:
            break
        a = json.loads(msg)
        if a.get("patient_id") == patient:
            return a, time.time() - t0
    return None, None


async def main():
    print("1. Connexion du médecin (Keycloak via NGINX)")
    tok = token("medecin1", "medecin1")
    check("jeton OIDC de medecin1 obtenu", bool(tok))

    print("2. WebSocket des alertes (wss://…/ws/alerts)")
    async with websockets.connect(f"wss://{HOST}/ws/alerts?access_token={tok}", ssl=CTX, open_timeout=10) as ws:
        check("WebSocket ouvert", True)
        await asyncio.sleep(1)

        print("3. Mesures critiques -> alerte à l'écran")
        for patient, canal in (("P00042", "bracelet haut risque, MQTT"), ("P01042", "bracelet standard, HTTPS")):
            m, t0 = await asyncio.to_thread(send_critical, patient)
            a, delay = await wait_alert(ws, patient, t0)
            ok = a is not None and delay < 5
            check(f"alerte {patient} ({canal})", ok,
                  f"{a['severity']} en {delay:.2f} s, raisons {[r['parameter'] for r in a['reasons']]}" if a else "aucune alerte reçue")

    print("4. API pour l'application web")
    st, recent = get("/api/alerts/recent", tok)
    check("GET /api/alerts/recent", st == 200 and any(x.get("patient_id") == "P00042" for x in (recent or [])),
          f"HTTP {st}, {len(recent or [])} alertes")
    await asyncio.sleep(2)
    st, patients = get("/api/vitals/patients?q=P00042&limit=5", tok)
    check("GET /api/vitals/patients?q=P00042", st == 200 and bool(patients), f"HTTP {st}, {len(patients or [])} patient(s)")
    st, hist = get("/api/vitals/patients/P00042/history?hours=1", tok)
    check("GET /api/vitals/patients/P00042/history", st == 200 and len((hist or {}).get("points", [])) > 0,
          f"HTTP {st}, {len((hist or {}).get('points', []))} point(s)")

    print("5. Sécurité")
    st, _ = get("/api/vitals/patients?q=P00042")
    check("vitals sans jeton -> 401", st == 401, f"HTTP {st}")
    tok_an = token("analyste1", "analyste1")
    st, _ = get("/api/alerts/recent", tok_an)
    check("notification avec un analyste (mauvais rôle) -> 403", st == 403, f"HTTP {st}")
    stats = sim.Stats()
    m = sim.Patient("hr-00001", __import__("random").Random()).measure(0)
    sim.post_measures(Args, "hr-00001", [m], stats)   # un bracelet haut risque n'a pas accès au port 8443
    check("bracelet haut risque refusé sur l'API des bracelets standards", stats.snapshot()["erreurs"] == 1)

    print(f"\nBilan : {sum(results)}/{len(results)} vérifications réussies")
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
