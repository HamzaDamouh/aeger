"""Génère la configuration du Vault Agent de chaque VM : Services/deploy/<vm>/vault-agent.hcl
Lancer : python gen_configs.py   (depuis Services/securite/vault-agent)
Une seule table, VMS, dit quels certificats et quels secrets chaque VM reçoit (moindre privilège :
elle correspond aux politiques créées par vault/bootstrap.sh).
"""
import ipaddress
import os
import pathlib

# vm : (ip, [certificats], [secrets KV -> <nom>.env], realm_key)
VMS = {
    "vm-edge-01": ("10.0.10.11", ["nginx"], [], False),
    "vm-sec-01": ("10.0.20.11", ["keycloak"], ["keycloak"], False),
    "vm-hot-01": ("10.0.30.11", ["mosquitto", "ingestion"], [], False),
    "vm-hot-02": ("10.0.30.12", ["alerting", "notification"], [], True),
    "vm-hot-03": ("10.0.30.13", ["vitals"], ["vitals"], True),
    "vm-kafka-01": ("10.0.40.11", ["kafka"], [], False),
    "vm-db-01": ("10.0.40.12", ["postgres"], ["vitals", "kpi", "grafana", "keycloak"], False),
    "vm-cold-01": ("10.0.50.11", ["airflow"], ["spark"], False),
    "vm-cold-02": ("10.0.50.12", ["seaweedfs"], ["s3"], False),
    "vm-cold-03": ("10.0.50.13", ["spark"], ["spark", "kpi", "s3"], False),
    "vm-cold-04": ("10.0.50.14", ["grafana"], ["grafana"], False),
}
# nginx et mosquitto sont joints depuis l'extérieur par l'adresse publique d'AEGER (TLS MQTT de bout en bout).
# AEGER_PUBLIC_HOST : IP ou nom de VM-EDGE-01 vu du Tier 1 (192.168.56.10 = la maquette VirtualBox).
PUBLIC = os.environ.get("AEGER_PUBLIC_HOST", "192.168.56.10")
try:
    ipaddress.ip_address(PUBLIC)
    _pub = (["aeger.local"], [PUBLIC])
except ValueError:
    _pub = (["aeger.local", PUBLIC], [])
EXTRA_SAN = {"nginx": _pub, "mosquitto": _pub}
S = "/opt/aeger/secrets"

HEAD = """# Vault Agent de {vm} (généré par Services/securite/vault-agent/gen_configs.py)
pid_file = "/tmp/vault-agent.pid"

vault {{
  address = "https://vm-sec-01:8200"
  ca_cert = "/opt/aeger/vault-agent/ca.pem"
}}

auto_auth {{
  method "approle" {{
    config = {{
      role_id_file_path                   = "/opt/aeger/vault-agent/role_id"
      secret_id_file_path                 = "/opt/aeger/vault-agent/secret_id"
      remove_secret_id_file_after_reading = false
    }}
  }}
}}

template_config {{
  static_secret_render_interval = "2m"
  exit_on_retry_failure         = false
}}

# Certificat de l'autorité AEGER
template {{
  destination = "{S}/ca.pem"
  perms       = "0644"
  contents    = "{{{{ with secret \\"pki/cert/ca\\" }}}}{{{{ .Data.certificate }}}}{{{{ end }}}}\\n"
}}
"""

CERT = """
# Certificat mTLS « {svc} » (30 jours, renouvelé par l'agent) :
#   {svc}.pem = clé + certificat + CA ; {svc}-key.pem et {svc}-cert.pem séparés pour NGINX, mosquitto, PostgreSQL…
template {{
  destination = "{S}/{svc}.pem"
  perms       = "0640"
  contents    = <<EOT
{{{{- with pkiCert "pki/issue/{svc}" "common_name={svc}" "alt_names={alt}" "ip_sans={ips}" "private_key_format=pkcs8" -}}}}
{{{{ .Key }}}}
{{{{ .Cert }}}}
{{{{ .CA }}}}
{{{{ .Key | writeToFile "{S}/{svc}-key.pem" "" "" "0640" }}}}
{{{{ printf "%s\\n%s\\n" .Cert .CA | writeToFile "{S}/{svc}-cert.pem" "" "" "0640" }}}}
{{{{- end }}}}
EOT
}}
"""

KV = """
# Secrets « {k} » -> {k}.env (CLE=valeur)
template {{
  destination = "{S}/{k}.env"
  perms       = "0640"
  contents    = <<EOT
{{{{ with secret "kv/data/aeger/{k}" }}}}{{{{ range $key, $v := .Data.data }}}}{{{{ $key }}}}={{{{ $v }}}}
{{{{ end }}}}{{{{ end }}}}
EOT
}}
"""

REALM = """
# Clé publique du realm Keycloak : les services vérifient les jetons OIDC hors ligne
template {{
  destination = "{S}/keycloak-realm.pem"
  perms       = "0644"
  contents    = "{{{{ with secret \\"kv/data/aeger/realm\\" }}}}{{{{ .Data.data.public_key }}}}{{{{ end }}}}"
}}
"""


def main():
    root = pathlib.Path(__file__).resolve().parents[2] / "deploy"   # Services/deploy
    for vm, (ip, certs, kvs, realm) in VMS.items():
        txt = HEAD.format(vm=vm, S=S)
        for svc in certs:
            dns, extra_ip = EXTRA_SAN.get(svc, ([], []))
            alt = ",".join([vm, "localhost"] + dns)
            ips = ",".join([ip, "127.0.0.1"] + extra_ip)
            txt += CERT.format(svc=svc, alt=alt, ips=ips, S=S)
        for k in kvs:
            txt += KV.format(k=k, S=S)
        if realm:
            txt += REALM.format(S=S)
        d = root / vm
        d.mkdir(parents=True, exist_ok=True)
        (d / "vault-agent.hcl").write_text(txt, encoding="utf-8", newline="\n")
        print(f"{vm}: {len(certs)} certificat(s), {len(kvs)} secret(s){', clé realm' if realm else ''}")


if __name__ == "__main__":
    main()
