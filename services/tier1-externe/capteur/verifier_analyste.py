"""Vérification du parcours analyste : Grafana via NGINX, connexion Keycloak (OIDC), lecture des KPI.

  python verifier_analyste.py
"""
import html
import http.cookiejar
import json
import os
import re
import ssl
import sys
import urllib.parse
import urllib.request
from pathlib import Path

HOST = "https://" + os.environ.get("AEGER_HOST", "192.168.56.10")   # adresse publique d'AEGER
CTX = ssl.create_default_context(cafile=str(Path(__file__).resolve().parent / "certs" / "ca.pem"))


def session(user, password):
    """Connexion comme un navigateur : Grafana -> Keycloak (formulaire) -> retour dans Grafana."""
    jar = http.cookiejar.CookieJar()
    op = urllib.request.build_opener(urllib.request.HTTPSHandler(context=CTX), urllib.request.HTTPCookieProcessor(jar))
    page = op.open(f"{HOST}/grafana/login/generic_oauth").read().decode()
    action = html.unescape(re.search(r'<form[^>]+id="kc-form-login"[^>]+action="([^"]+)"', page).group(1))
    data = urllib.parse.urlencode({"username": user, "password": password, "credentialId": ""}).encode()
    try:
        r = op.open(action, data=data)
        return op, r.status
    except urllib.error.HTTPError as e:
        return op, e.code


def main():
    ok = []

    def check(name, cond, detail=""):
        ok.append(cond)
        print(f"  [{'OK' if cond else 'ÉCHEC'}] {name}{' - ' + detail if detail else ''}")

    print("Parcours analyste (Grafana)")
    op, status = session("analyste1", "analyste1")
    user = json.load(op.open(f"{HOST}/grafana/api/user"))
    orgs = json.load(op.open(f"{HOST}/grafana/api/user/orgs"))
    check("connexion de analyste1 via Keycloak", user.get("login") == "analyste1",
          f"rôle Grafana : {orgs[0]['role'] if orgs else '?'}")

    q = {"queries": [{"refId": "A", "datasource": {"uid": "aeger-kpi"}, "format": "table", "rawSql":
         "SELECT day::text, nb_measures, nb_patients, nb_alerts, round(pct_alert::numeric, 2) AS pct "
         "FROM kpi.daily_summary ORDER BY day DESC LIMIT 3"}], "from": "now-7d", "to": "now"}
    req = urllib.request.Request(f"{HOST}/grafana/api/ds/query", data=json.dumps(q).encode(),
                                 headers={"Content-Type": "application/json"})
    res = json.load(op.open(req))
    vals = res["results"]["A"]["frames"][0]["data"]["values"]
    rows = list(zip(*vals))
    check("KPI lus dans PostgreSQL (rôle grafana_ro)", len(rows) > 0,
          " ; ".join(f"{d} : {m} mesures, {p} patients, {a} alertes ({pc} %)" for d, m, p, a, pc in rows))
    dash = json.load(op.open(f"{HOST}/grafana/api/dashboards/uid/aeger-kpi"))
    check("tableau de bord « AEGER · Indicateurs quotidiens » présent", dash["dashboard"]["title"].startswith("AEGER"),
          f"{len(dash['dashboard']['panels'])} panneaux")

    print("Contrôle d'accès")
    op2, status2 = session("medecin1", "medecin1")
    try:
        u2 = json.load(op2.open(f"{HOST}/grafana/api/user"))
        refused = u2.get("login") != "medecin1"
    except urllib.error.HTTPError:
        refused = True
    check("un médecin (sans rôle analyste) n'entre pas dans Grafana", refused)

    print(f"\nBilan : {sum(ok)}/{len(ok)} vérifications réussies")
    return 0 if all(ok) else 1


if __name__ == "__main__":
    sys.exit(main())
