#!/bin/sh
# Mise en place de Vault pour AEGER (à lancer DANS le conteneur vault, sur VM-SEC-01).
#   aeger-vault-bootstrap.sh init                 initialise, crée la PKI, les secrets, les politiques et les AppRole
#   aeger-vault-bootstrap.sh approle <vm>         affiche role_id et un nouveau secret_id pour le Vault Agent de <vm>
#   aeger-vault-bootstrap.sh realm-key <url>      enregistre la clé publique du realm Keycloak (lue sur <url>)
#   aeger-vault-bootstrap.sh bracelets <nhr> <nstd>  délivre les certificats des bracelets -> /vault/out/bracelets.tar.gz
set -e
export VAULT_ADDR=http://127.0.0.1:8201
INIT=/vault/init/init.json

login() { export VAULT_TOKEN=$(jq -r .root_token "$INIT"); }

# service:vm:ip  (CN du certificat : le service ; SAN : le nom et l'IP de la VM)
SERVICES="nginx:vm-edge-01:10.0.10.11 vault:vm-sec-01:10.0.20.11 keycloak:vm-sec-01:10.0.20.11
mosquitto:vm-hot-01:10.0.30.11 ingestion:vm-hot-01:10.0.30.11 alerting:vm-hot-02:10.0.30.12
notification:vm-hot-02:10.0.30.12 vitals:vm-hot-03:10.0.30.13 kafka:vm-kafka-01:10.0.40.11
postgres:vm-db-01:10.0.40.12 airflow:vm-cold-01:10.0.50.11 seaweedfs:vm-cold-02:10.0.50.12
spark:vm-cold-03:10.0.50.13 grafana:vm-cold-04:10.0.50.14"

# vm:ip(s):certificats:secrets KV  (moindre privilège : chaque VM ne lit que ce dont elle a besoin)
# VM-COLD-01 route entre les VLAN : elle joint Vault directement depuis son adresse du VLAN 20 (10.0.20.1)
VMS="vm-edge-01:10.0.10.11:nginx:
vm-sec-01:10.0.20.11:keycloak:keycloak
vm-hot-01:10.0.30.11:mosquitto,ingestion:
vm-hot-02:10.0.30.12:alerting,notification:realm
vm-hot-03:10.0.30.13:vitals:vitals,realm
vm-kafka-01:10.0.40.11:kafka:
vm-db-01:10.0.40.12:postgres:vitals,kpi,grafana,keycloak
vm-cold-01:10.0.50.11+10.0.20.1:airflow:spark
vm-cold-02:10.0.50.12:seaweedfs:s3
vm-cold-03:10.0.50.13:spark:spark,kpi,s3
vm-cold-04:10.0.50.14:grafana:grafana"

rnd() { openssl rand -hex 16; }

cmd_init() {
  mkdir -p /vault/init /vault/tls /vault/out
  if ! vault status >/dev/null 2>&1 && [ ! -f "$INIT" ]; then
    echo "== initialisation (1 clé de déverrouillage : simplification de TP)"
    vault operator init -key-shares=1 -key-threshold=1 -format=json > "$INIT"
    chmod 600 "$INIT"
  fi
  vault operator unseal "$(jq -r '.unseal_keys_b64[0]' "$INIT")" >/dev/null || true
  login

  # ---- PKI : autorité racine AEGER (clé EC P-256), certificats de service de 30 jours
  if ! vault secrets list -format=json | jq -e '."pki/"' >/dev/null; then
    vault secrets enable pki
    vault secrets tune -max-lease-ttl=87600h pki
    vault write -field=certificate pki/root/generate/internal common_name="AEGER Root CA" \
      key_type=ec key_bits=256 ttl=87600h issuer_name=aeger-root > /vault/init/ca.pem
    vault write pki/config/urls issuing_certificates="https://vm-sec-01:8200/v1/pki/ca" \
      crl_distribution_points="https://vm-sec-01:8200/v1/pki/crl"
  fi
  for s in $SERVICES; do
    svc=${s%%:*}; rest=${s#*:}; vm=${rest%%:*}
    # nginx et mosquitto sont joints depuis l'extérieur via 192.168.56.10 / aeger.local
    extra=""; case "$svc" in nginx|mosquitto) extra=",aeger.local${AEGER_PUBLIC_HOST:+,$AEGER_PUBLIC_HOST}" ;; esac
    vault write pki/roles/$svc allowed_domains="$svc,$vm,localhost$extra" allow_bare_domains=true \
      allow_subdomains=false allow_localhost=true allow_ip_sans=true key_type=ec key_bits=256 \
      ttl=720h max_ttl=720h server_flag=true client_flag=true >/dev/null
  done
  # bracelets : certificats clients uniquement, 1 an
  vault write pki/roles/bracelet-hr allowed_domains="hr-*" allow_glob_domains=true allow_bare_domains=true \
    key_type=ec key_bits=256 ttl=8760h max_ttl=8760h server_flag=false client_flag=true >/dev/null
  vault write pki/roles/bracelet-std allowed_domains="std-*" allow_glob_domains=true allow_bare_domains=true \
    key_type=ec key_bits=256 ttl=8760h max_ttl=8760h server_flag=false client_flag=true >/dev/null

  # ---- certificat de Vault lui-même (écoute TLS 8200)
  if [ ! -f /vault/tls/vault.pem ]; then
    vault write -format=json pki/issue/vault common_name=vault alt_names=vm-sec-01,localhost \
      ip_sans=10.0.20.11,127.0.0.1 private_key_format=pkcs8 \
      | jq -r '.data.private_key, .data.certificate, .data.issuing_ca' > /vault/tls/vault.pem
    chmod 644 /vault/tls/vault.pem
    echo "== certificat de Vault créé : redémarrer le conteneur pour activer l'écoute TLS 8200"
  fi

  # ---- secrets (générés une seule fois)
  if ! vault secrets list -format=json | jq -e '."kv/"' >/dev/null; then
    vault secrets enable -path=kv kv-v2
  fi
  if ! vault kv get kv/aeger/vitals >/dev/null 2>&1; then
    GRAF_CLIENT=$(rnd)
    vault kv put kv/aeger/vitals DB_WRITER_USER=vitals_writer DB_WRITER_PASSWORD=$(rnd) \
      DB_READER_USER=vitals_reader DB_READER_PASSWORD=$(rnd) >/dev/null
    vault kv put kv/aeger/kpi KPI_DB_USER=kpi_writer KPI_DB_PASSWORD=$(rnd) >/dev/null
    vault kv put kv/aeger/grafana GRAFANA_DB_USER=grafana_ro GRAFANA_DB_PASSWORD=$(rnd) \
      GF_SECURITY_ADMIN_PASSWORD=$(rnd) GF_AUTH_GENERIC_OAUTH_CLIENT_SECRET=$GRAF_CLIENT >/dev/null
    vault kv put kv/aeger/keycloak KC_DB_USERNAME=keycloak KC_DB_PASSWORD=$(rnd) \
      KC_BOOTSTRAP_ADMIN_USERNAME=admin KC_BOOTSTRAP_ADMIN_PASSWORD=$(rnd) GRAFANA_CLIENT_SECRET=$GRAF_CLIENT >/dev/null
    vault kv put kv/aeger/spark SPARK_AUTH_SECRET=$(rnd) >/dev/null
    vault kv put kv/aeger/s3 S3_ACCESS_KEY=aeger$(openssl rand -hex 6) S3_SECRET_KEY=$(rnd) >/dev/null
    echo "== secrets générés dans kv/aeger/*"
  fi

  # ---- AppRole : une identité par VM, utilisable seulement depuis l'IP de cette VM
  vault auth list -format=json | jq -e '."approle/"' >/dev/null || vault auth enable approle
  echo "$VMS" | while IFS=: read -r vm ip certs kvs; do
    [ -z "$vm" ] && continue
    pol="path \"pki/cert/ca\" { capabilities = [\"read\"] }"
    for c in $(echo "$certs" | tr ',' ' '); do
      pol="$pol
path \"pki/issue/$c\" { capabilities = [\"update\"] }"
    done
    for k in $(echo "$kvs" | tr ',' ' '); do
      pol="$pol
path \"kv/data/aeger/$k\" { capabilities = [\"read\"] }"
    done
    echo "$pol" | vault policy write "$vm" - >/dev/null
    cidrs="$(echo "$ip" | sed 's#+#/32,#g')/32,127.0.0.1/32"     # "a+b" -> "a/32,b/32,127.0.0.1/32"
    vault write auth/approle/role/$vm token_policies="$vm" token_ttl=1h token_max_ttl=24h \
      secret_id_bound_cidrs="$cidrs" token_bound_cidrs="$cidrs" >/dev/null
  done
  echo "== Vault prêt : PKI, secrets, $(echo "$VMS" | grep -c vm-) AppRole"
}

cmd_approle() {
  login
  vm=$1
  rid=$(vault read -field=role_id auth/approle/role/$vm/role-id)
  sid=$(vault write -f -field=secret_id auth/approle/role/$vm/secret-id)
  printf '{"role_id":"%s","secret_id":"%s"}\n' "$rid" "$sid"
}

cmd_realm_key() {
  login
  key=$(curl -s --cacert /vault/init/ca.pem "$1" | jq -r .public_key)
  [ -n "$key" ] && [ "$key" != null ] || { echo "clé publique introuvable sur $1" >&2; exit 1; }
  pem=$(printf -- "-----BEGIN PUBLIC KEY-----\n%s\n-----END PUBLIC KEY-----\n" "$(echo "$key" | fold -w 64)")
  vault kv put kv/aeger/realm public_key="$pem" >/dev/null
  echo "== clé publique du realm enregistrée dans kv/aeger/realm"
}

cmd_bracelets() {
  login
  NHR=${1:-1000}; NSTD=${2:-9000}
  python3 - "$NHR" "$NSTD" <<'PY'
import json, os, sys, tarfile, io, urllib.request
nhr, nstd = int(sys.argv[1]), int(sys.argv[2])
token = os.environ["VAULT_TOKEN"]
def issue(role, cn):
    req = urllib.request.Request(f"http://127.0.0.1:8201/v1/pki/issue/{role}",
        data=json.dumps({"common_name": cn, "private_key_format": "pkcs8"}).encode(),
        headers={"X-Vault-Token": token}, method="POST")
    d = json.load(urllib.request.urlopen(req))["data"]
    return (d["private_key"] + "\n" + d["certificate"] + "\n").encode(), d["issuing_ca"]
out = "/vault/out/bracelets.tar.gz"
with tarfile.open(out, "w:gz") as tar:
    def add(name, data):
        ti = tarfile.TarInfo(name); ti.size = len(data); tar.addfile(ti, io.BytesIO(data))
    ca = None
    for i in range(1, nhr + 1):
        pem, ca = issue("bracelet-hr", f"hr-{i:05d}"); add(f"hr/hr-{i:05d}.pem", pem)
        if i % 500 == 0: print(f"  {i} bracelets haut risque", flush=True)
    for i in range(1, nstd + 1):
        pem, ca = issue("bracelet-std", f"std-{i:05d}"); add(f"std/std-{i:05d}.pem", pem)
        if i % 1000 == 0: print(f"  {i} bracelets standards", flush=True)
    add("ca.pem", (ca + "\n").encode())
print(f"== {nhr + nstd} certificats de bracelets -> {out}")
PY
}

case "${1:-init}" in
  init) cmd_init ;;
  approle) cmd_approle "$2" ;;
  realm-key) cmd_realm_key "$2" ;;
  bracelets) cmd_bracelets "$2" "$3" ;;
  *) echo "usage : $0 init|approle <vm>|realm-key <url>|bracelets <nhr> <nstd>" >&2; exit 1 ;;
esac
