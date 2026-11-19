"""Simulateur des bracelets AEGER (Tier 1, tourne sur le PC).

- Bracelets haut risque (hr-00001 ... hr-01000) : 1 mesure toutes les 5 s, en MQTT sur TLS,
  connexion permanente vers 192.168.56.10:8883, avec le certificat X.509 propre au bracelet.
- Bracelets standards (std-00001 ... std-09000) : 1 mesure toutes les 5 min, envoyées par lot
  chaque heure en HTTPS (mTLS) vers https://192.168.56.10:8443/api/measures ; une mesure anormale
  part immédiatement, sans attendre le lot.

Exemples :
  python simulateur.py run                          # charge complète : 1 000 + 9 000 bracelets
  python simulateur.py run --hr 50 --std 200 --lot 60 --periode-std 10   # petite démo, lots chaque minute
  python simulateur.py alerte --patient P00042      # envoie tout de suite une mesure critique
"""
import argparse
import heapq
import http.client
import json
import os
import random
import ssl
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path

import paho.mqtt.client as mqtt

HERE = Path(__file__).resolve().parent


# ----------------------------------------------------------------------------- mesures
def now_iso():
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


class Patient:
    """Constantes de base d'un patient + petite dérive aléatoire à chaque mesure."""

    def __init__(self, bracelet_id, rng):
        self.bracelet_id = bracelet_id
        self.type = "high_risk" if bracelet_id.startswith("hr-") else "standard"
        n = int(bracelet_id.split("-")[1])
        self.patient_id = f"P{n if self.type == 'high_risk' else n + 1000:05d}"
        self.seq = int(time.time())          # croissant même après un redémarrage
        self.hr = rng.gauss(75, 8)
        self.spo2 = rng.gauss(97, 1)
        self.temp = rng.gauss(36.8, 0.25)
        self.sys = rng.gauss(122, 10)
        self.dia = rng.gauss(79, 6)
        self.rng = rng

    def measure(self, anomaly_rate, force=None):
        r = self.rng
        self.seq += 1
        m = {
            "bracelet_id": self.bracelet_id, "patient_id": self.patient_id, "type": self.type,
            "seq": self.seq, "ts": now_iso(),
            "heart_rate": round(self.hr + r.gauss(0, 3)),
            "spo2": min(100, round(self.spo2 + r.gauss(0, 0.6))),
            "temperature": round(self.temp + r.gauss(0, 0.08), 1),
            "systolic": round(self.sys + r.gauss(0, 4)),
            "diastolic": round(self.dia + r.gauss(0, 3)),
            "anomaly": False,
        }
        kind = force or (r.choice(["spo2", "hr", "temp", "bp"]) if r.random() < anomaly_rate else None)
        if kind:
            if kind == "critical":
                m.update(spo2=r.randint(80, 84), heart_rate=r.randint(152, 165))
            elif kind == "spo2":
                m["spo2"] = r.randint(84, 89)
            elif kind == "hr":
                m["heart_rate"] = r.choice([r.randint(132, 160), r.randint(32, 38)])
            elif kind == "temp":
                m["temperature"] = round(r.uniform(39.6, 40.8), 1)
            else:
                m["systolic"] = r.randint(182, 205)
            m["anomaly"] = self.type == "standard"   # un bracelet standard signale lui-même l'anomalie
        return m


def tls_context(certs, bracelet_id):
    ctx = ssl.create_default_context(ssl.Purpose.SERVER_AUTH, cafile=str(certs / "ca.pem"))
    folder = "hr" if bracelet_id.startswith("hr-") else "std"
    ctx.load_cert_chain(str(certs / folder / f"{bracelet_id}.pem"))
    return ctx


# ----------------------------------------------------------------------------- statistiques
class Stats:
    def __init__(self):
        self.lock = threading.Lock()
        self.c = {"mqtt_envoyees": 0, "mqtt_connectes": 0, "lots_https": 0, "mesures_https": 0,
                  "anomalies_immediates": 0, "erreurs": 0}

    def add(self, key, n=1):
        with self.lock:
            self.c[key] += n

    def snapshot(self):
        with self.lock:
            return dict(self.c)


def reporter(stats, every=10):
    last = stats.snapshot()
    while True:
        time.sleep(every)
        cur = stats.snapshot()
        rate = (cur["mqtt_envoyees"] - last["mqtt_envoyees"]) / every
        print(f"[{datetime.now():%H:%M:%S}] MQTT : {cur['mqtt_connectes']} bracelets connectés, {rate:.0f} msg/s "
              f"(total {cur['mqtt_envoyees']}) | HTTPS : {cur['lots_https']} lots, {cur['mesures_https']} mesures, "
              f"{cur['anomalies_immediates']} anomalies immédiates | erreurs : {cur['erreurs']}", flush=True)
        last = cur


# ----------------------------------------------------------------------------- envois
def make_mqtt_client(args, bracelet_id, stats):
    c = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=bracelet_id, protocol=mqtt.MQTTv311)
    c.tls_set_context(tls_context(args.certs, bracelet_id))
    c.max_inflight_messages_set(50)
    c.reconnect_delay_set(1, 30)

    def on_connect(client, userdata, flags, reason, props):
        if reason == 0:
            stats.add("mqtt_connectes")
        else:
            stats.add("erreurs")
            print(f"  {bracelet_id} : connexion MQTT refusée ({reason})", file=sys.stderr)

    def on_disconnect(client, userdata, flags, reason, props):
        stats.add("mqtt_connectes", -1)

    c.on_connect = on_connect
    c.on_disconnect = on_disconnect
    return c


def post_measures(args, bracelet_id, measures, stats, anomaly=False):
    body = json.dumps(measures)
    try:
        conn = http.client.HTTPSConnection(args.host, args.port_https, context=tls_context(args.certs, bracelet_id),
                                           timeout=10)
        conn.request("POST", "/api/measures", body=body, headers={"Content-Type": "application/json"})
        resp = conn.getresponse()
        resp.read()
        conn.close()
        if resp.status != 202:
            stats.add("erreurs")
            print(f"  {bracelet_id} : HTTPS {resp.status}", file=sys.stderr)
            return
        if anomaly:
            stats.add("anomalies_immediates")
        else:
            stats.add("lots_https")
        stats.add("mesures_https", len(measures))
    except Exception as e:  # noqa: BLE001
        stats.add("erreurs")
        print(f"  {bracelet_id} : erreur HTTPS {e}", file=sys.stderr)


def cmd_run(args):
    rng = random.Random(args.seed)
    stats = Stats()
    threading.Thread(target=reporter, args=(stats,), daemon=True).start()
    pool = ThreadPoolExecutor(max_workers=8)

    hr = [Patient(f"hr-{i:05d}", random.Random(rng.random())) for i in range(1, args.hr + 1)]
    std = [Patient(f"std-{i:05d}", random.Random(rng.random())) for i in range(1, args.std + 1)]
    buffers = {p.bracelet_id: [] for p in std}

    print(f"Démarrage : {len(hr)} bracelets haut risque (MQTT, 1 mesure / {args.periode_hr} s), "
          f"{len(std)} bracelets standards (HTTPS, 1 mesure / {args.periode_std} s, lot / {args.lot} s)")

    clients = {}
    for i, p in enumerate(hr):                        # connexions étalées sur --rampe secondes
        c = make_mqtt_client(args, p.bracelet_id, stats)
        c.connect_async(args.host, args.port_mqtt, keepalive=60)
        c.loop_start()
        clients[p.bracelet_id] = c
        if args.rampe and len(hr) > 1:
            time.sleep(args.rampe / len(hr))

    # file d'événements (instant, ordre, type, patient)
    t0 = time.time()
    events = []
    for k, p in enumerate(hr):
        heapq.heappush(events, (t0 + rng.uniform(0, args.periode_hr), k, "hr", p))
    for k, p in enumerate(std):
        heapq.heappush(events, (t0 + rng.uniform(0, args.periode_std), len(hr) + k, "std", p))
        heapq.heappush(events, (t0 + rng.uniform(0, args.lot), len(hr) + len(std) + k, "lot", p))
    end = t0 + args.duree if args.duree else None

    try:
        while events:
            when, k, kind, p = heapq.heappop(events)
            if end and when > end:
                break
            delay = when - time.time()
            if delay > 0:
                time.sleep(delay)
            if kind == "hr":
                m = p.measure(args.taux_anomalie)
                info = clients[p.bracelet_id].publish(f"aeger/vitals/{p.bracelet_id}", json.dumps(m), qos=1)
                if info.rc == mqtt.MQTT_ERR_SUCCESS:
                    stats.add("mqtt_envoyees")
                heapq.heappush(events, (when + args.periode_hr, k, kind, p))
            elif kind == "std":
                m = p.measure(args.taux_anomalie)
                if m["anomaly"]:
                    pool.submit(post_measures, args, p.bracelet_id, [m], stats, True)   # tout de suite
                else:
                    buffers[p.bracelet_id].append(m)
                heapq.heappush(events, (when + args.periode_std, k, kind, p))
            else:  # lot horaire
                batch, buffers[p.bracelet_id] = buffers[p.bracelet_id], []
                if batch:
                    pool.submit(post_measures, args, p.bracelet_id, batch, stats)
                heapq.heappush(events, (when + args.lot, k, kind, p))
    except KeyboardInterrupt:
        print("Arrêt demandé")
    finally:
        for c in clients.values():
            c.disconnect()
            c.loop_stop()
        pool.shutdown(wait=True)
        print("Bilan :", stats.snapshot())


def cmd_alerte(args):
    n = int(args.patient.lstrip("Pp"))
    bracelet = f"hr-{n:05d}" if n <= 1000 else f"std-{n - 1000:05d}"
    p = Patient(bracelet, random.Random())
    m = p.measure(0, force="critical")
    if p.type == "standard":
        m["anomaly"] = True
    print(f"Mesure critique pour {p.patient_id} ({bracelet}) : SpO2 {m['spo2']} %, FC {m['heart_rate']} bpm, ts {m['ts']}")
    stats = Stats()
    if p.type == "high_risk":
        done = threading.Event()
        c = make_mqtt_client(args, bracelet, stats)
        c.on_publish = lambda *a: done.set()
        c.connect(args.host, args.port_mqtt, keepalive=30)
        c.loop_start()
        c.publish(f"aeger/vitals/{bracelet}", json.dumps(m), qos=1)
        ok = done.wait(10)
        c.disconnect()
        c.loop_stop()
        print("Envoyée en MQTT" if ok else "Pas d'accusé de réception MQTT")
    else:
        post_measures(args, bracelet, [m], stats, anomaly=True)
        print("Envoyée en HTTPS" if stats.snapshot()["erreurs"] == 0 else "Échec de l'envoi HTTPS")


def main():
    ap = argparse.ArgumentParser(description="Simulateur des bracelets AEGER")
    ap.add_argument("--host", default=os.environ.get("AEGER_HOST", "192.168.56.10"),
                    help="adresse publique d'AEGER (VM-EDGE-01) ; par défaut la variable AEGER_HOST")
    ap.add_argument("--port-mqtt", type=int, default=8883)
    ap.add_argument("--port-https", type=int, default=8443)
    ap.add_argument("--certs", type=Path, default=HERE / "certs", help="dossier des certificats (hr/, std/, ca.pem)")
    sub = ap.add_subparsers(dest="cmd", required=True)

    r = sub.add_parser("run", help="simule les bracelets en continu")
    r.add_argument("--hr", type=int, default=1000, help="nombre de bracelets haut risque (max 1000)")
    r.add_argument("--std", type=int, default=9000, help="nombre de bracelets standards (max 9000)")
    r.add_argument("--periode-hr", type=float, default=5, help="secondes entre deux mesures haut risque")
    r.add_argument("--periode-std", type=float, default=300, help="secondes entre deux mesures standard")
    r.add_argument("--lot", type=float, default=3600, help="secondes entre deux envois par lot (standard)")
    r.add_argument("--taux-anomalie", type=float, default=0.0005, help="probabilité qu'une mesure soit anormale")
    r.add_argument("--rampe", type=float, default=30, help="secondes pour étaler les connexions MQTT")
    r.add_argument("--duree", type=float, default=0, help="durée de la simulation en secondes (0 = sans fin)")
    r.add_argument("--seed", type=int, default=42)
    r.set_defaults(func=cmd_run)

    a = sub.add_parser("alerte", help="envoie immédiatement une mesure critique pour un patient")
    a.add_argument("--patient", required=True, help="ex. P00042 (haut risque) ou P01042 (standard)")
    a.set_defaults(func=cmd_alerte)

    args = ap.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
