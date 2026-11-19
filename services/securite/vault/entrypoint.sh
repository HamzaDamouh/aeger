#!/bin/sh
# Démarre Vault sur VM-SEC-01.
#  - 127.0.0.1:8201 : administration locale, sans TLS (jamais exposée hors de la VM)
#  - 10.0.20.11:8200 : accès des autres VM, en TLS, dès que le certificat de Vault existe
# Données : /vault/data ; certificat : /vault/tls/vault.pem ; clé de déverrouillage : /vault/init/init.json
set -e
TLS=/vault/tls/vault.pem
CONF=/tmp/vault.hcl

cat > "$CONF" <<EOF
ui = false
disable_mlock = true
storage "file" { path = "/vault/data" }
api_addr = "https://vm-sec-01:8200"
listener "tcp" {
  address     = "127.0.0.1:8201"
  tls_disable = true
}
EOF

if [ -f "$TLS" ]; then
  cat >> "$CONF" <<EOF
listener "tcp" {
  address         = "${VAULT_LISTEN:-10.0.20.11:8200}"
  tls_cert_file   = "$TLS"
  tls_key_file    = "$TLS"
  tls_min_version = "tls12"
}
EOF
  echo "[aeger] écoute TLS activée sur ${VAULT_LISTEN:-10.0.20.11:8200}"
else
  echo "[aeger] pas encore de certificat : seule l'écoute locale 127.0.0.1:8201 est active (lancer le bootstrap)"
fi

vault server -config="$CONF" &
PID=$!

# Déverrouillage automatique si Vault est déjà initialisé (clé conservée sur la VM : simplification de TP)
if [ -f /vault/init/init.json ]; then
  for i in $(seq 1 30); do
    if vault status -format=json >/dev/null 2>&1 || [ $? -eq 2 ]; then break; fi
    sleep 1
  done
  KEY=$(jq -r '.unseal_keys_b64[0]' /vault/init/init.json)
  vault operator unseal "$KEY" >/dev/null && echo "[aeger] Vault déverrouillé"
fi

wait $PID
