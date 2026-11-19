#!/bin/bash
# Déploiement AEGER depuis le PC (Git Bash), par le SSH d'administration de chaque VM.
#  - vSphere : le PC se connecte à VM-EDGE-01 (bastion, seule VM sur le switch LAN), qui relaie vers chaque VM ;
#  - maquette VirtualBox : redirection de port vers chaque VM (127.0.0.1:22xx).
# Réglages : deploy/aeger.conf (copier aeger.conf.exemple). Sans ce fichier : maquette VirtualBox.
#
# Les conteneurs sont décrits dans UN fichier : Services/docker-compose.yml (un profil par VM).
# Chaque VM reçoit ce fichier dans /opt/aeger/compose, un .env (COMPOSE_PROFILES=<vm>) et les dossiers
# de configuration montés par ses services ; sur la VM, « docker compose up -d » suffit.
#
#   bash aeger.sh images                 sauvegarde les images construites sur le PC (docker compose --profile build build)
#   bash aeger.sh prep <vm>...           installe Docker, crée le groupe aeger-secrets (2000) et /opt/aeger
#   bash aeger.sh load <vm>...           copie et charge dans chaque VM les images dont elle a besoin
#   bash aeger.sh vault                  démarre Vault sur VM-SEC-01 et le configure (PKI, secrets, AppRole)
#   bash aeger.sh agent <vm>...          installe le Vault Agent de chaque VM et attend ses certificats
#   bash aeger.sh up <vm>...             démarre les conteneurs de chaque VM (docker compose)
#   bash aeger.sh kafka | postgres       installe Kafka / PostgreSQL (systemd) sur leur VM
#   bash aeger.sh realm-key              publie la clé publique du realm Keycloak dans Vault
#   bash aeger.sh bracelets [nhr] [nstd] délivre les certificats des bracelets -> tier1-externe/capteur/certs
#   bash aeger.sh time <vm>...           maquette VirtualBox : horloge précise (chrony) ; sur vSphere, l'heure vient du routeur
#   bash aeger.sh ssh <vm> [commande]    exécute une commande dans une VM
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SERVICES="$(dirname "$HERE")"
IMAGES_DIR="${AEGER_IMAGES:-$HOME/VirtualBox VMs/AEGER/_images}"
SSH=/c/Windows/System32/OpenSSH/ssh.exe
SCP=/c/Windows/System32/OpenSSH/scp.exe
KEY="$(cygpath -m "$HOME/.ssh/aeger_ed25519")"
KH="$(cygpath -m "$HOME/.ssh/aeger_known_hosts")"
OPTS=(-i "$KEY" -o BatchMode=yes -o ConnectTimeout=10 -o StrictHostKeyChecking=no -o "UserKnownHostsFile=$KH" -o LogLevel=ERROR)

# Réglages de la plateforme
AEGER_PLATEFORME=virtualbox            # virtualbox (maquette) ou vsphere
AEGER_PUBLIC_HOST=192.168.56.10        # adresse publique d'AEGER : VM-EDGE-01 vue du Tier 1
AEGER_BASTION=""                       # vSphere : adresse de VM-EDGE-01 sur le LAN (par défaut AEGER_PUBLIC_HOST)
[ -f "$HERE/aeger.conf" ] && . "$HERE/aeger.conf"
export AEGER_PUBLIC_HOST
[ -n "$AEGER_BASTION" ] || AEGER_BASTION=$AEGER_PUBLIC_HOST
# vSphere : chaque VM est jointe à travers le bastion (sa clé est indiquée aussi pour ce premier saut)
PROXY="C:/Windows/System32/OpenSSH/ssh.exe -i $KEY -o BatchMode=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=$KH -o LogLevel=ERROR -W %h:%p aeger@$AEGER_BASTION"
declare -A IP=( [vm-edge-01]=10.0.10.11 [vm-sec-01]=10.0.20.11 [vm-hot-01]=10.0.30.11 [vm-hot-02]=10.0.30.12
  [vm-hot-03]=10.0.30.13 [vm-kafka-01]=10.0.40.11 [vm-db-01]=10.0.40.12 [vm-cold-01]=10.0.50.11 [vm-cold-02]=10.0.50.12
  [vm-cold-03]=10.0.50.13 [vm-cold-04]=10.0.50.14 )

# maquette VirtualBox du PC (ancienne version, avec sa VM routeur) : redirections SSH 127.0.0.1:22xx
declare -A PORT=( [vm-router-01]=2200 [vm-edge-01]=2201 [vm-sec-01]=2202 [vm-hot-01]=2203 [vm-hot-02]=2204
  [vm-hot-03]=2205 [vm-kafka-01]=2206 [vm-db-01]=2207 [vm-cold-01]=2208 [vm-cold-02]=2209 [vm-cold-03]=2210 [vm-cold-04]=2211 )
# images construites sur le PC (les autres sont des images officielles, téléchargées par la VM)
declare -A IMAGES=( [vm-edge-01]="nginx" [vm-sec-01]="vault keycloak" [vm-hot-01]="ingestion"
  [vm-hot-02]="notification alerting" [vm-hot-03]="vitals" [vm-kafka-01]="" [vm-db-01]=""
  [vm-cold-01]="airflow" [vm-cold-02]="" [vm-cold-03]="spark" [vm-cold-04]="" )
ALL_IMAGES="nginx vault keycloak ingestion notification alerting vitals airflow spark"
# dossiers de Services/ montés par docker compose sur chaque VM
declare -A CONFIG=( [vm-edge-01]="tier2-dmz/nginx" [vm-sec-01]="securite/keycloak" [vm-hot-01]="tier3-temps-reel/mosquitto"
  [vm-cold-02]="tier5-analytique/seaweedfs" [vm-cold-04]="tier5-analytique/grafana tier5-analytique/grafana-mtls" )

# cible SSH d'une VM selon la plateforme : options de connexion + adresse
target() {
  local vm=$1
  if [ "$AEGER_PLATEFORME" = vsphere ]; then
    if [ "$vm" = vm-edge-01 ]; then T_OPTS=(); T_HOST="aeger@$AEGER_BASTION"
    else T_OPTS=(-o "ProxyCommand=$PROXY"); T_HOST="aeger@${IP[$vm]}"; fi
    T_PORT=22
  else
    T_OPTS=(); T_HOST=aeger@127.0.0.1; T_PORT=${PORT[$vm]}
  fi
}
s()   { local vm=$1; shift; target "$vm"; "$SSH" "${OPTS[@]}" "${T_OPTS[@]}" -p "$T_PORT" "$T_HOST" "$@"; }
cp_to() { local vm=$1 dst=$2; shift 2; target "$vm"; "$SCP" "${OPTS[@]}" "${T_OPTS[@]}" -q -P "$T_PORT" "$@" "$T_HOST:$dst"; }
log() { printf '\033[1;34m[%s]\033[0m %s\n' "$(date +%H:%M:%S)" "$*"; }
need_vm() { [ -n "${PORT[$1]:-}" ] || { echo "VM inconnue : $1" >&2; exit 1; }; }

cmd_images() {
  mkdir -p "$IMAGES_DIR"
  for i in $ALL_IMAGES; do
    log "sauvegarde aeger/$i:1.0"
    docker save "aeger/$i:1.0" | gzip -1 > "$IMAGES_DIR/$i.tar.gz"
  done
  ls -la "$IMAGES_DIR"
}

cmd_prep() {
  for vm in "$@"; do need_vm "$vm"; log "$vm : Docker + /opt/aeger"
    s "$vm" "sudo bash -s -- $AEGER_PLATEFORME" <<'EOF'
set -e
# sur vSphere, Docker ne gère pas le pare-feu : VM-EDGE-01 (NAT) et VM-COLD-01 (routage) en dépendent ;
# tous les conteneurs AEGER sont en network_mode: host et n'en ont pas besoin
DAEMON='{"log-driver":"json-file","log-opts":{"max-size":"10m","max-file":"2"}}'
[ "$1" = vsphere ] && DAEMON='{"iptables":false,"log-driver":"json-file","log-opts":{"max-size":"10m","max-file":"2"}}'
getent group aeger-secrets >/dev/null || groupadd -g 2000 aeger-secrets
mkdir -p /opt/aeger/secrets /opt/aeger/vault-agent /opt/aeger/compose /opt/aeger/images
chown root:aeger-secrets /opt/aeger/secrets && chmod 0750 /opt/aeger/secrets
chmod 0750 /opt/aeger/vault-agent
if ! command -v docker >/dev/null; then
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq && apt-get install -y -qq docker.io docker-compose-v2 >/dev/null
fi
mkdir -p /etc/docker
if [ "$(cat /etc/docker/daemon.json 2>/dev/null)" != "$DAEMON" ]; then
  echo "$DAEMON" > /etc/docker/daemon.json     # + journaux Docker limités (petites VM)
  systemctl restart docker
fi
usermod -aG docker,aeger-secrets aeger
docker --version
EOF
    # maquette VirtualBox : horloge par chrony ; sur vSphere, l'heure vient du routeur (configurée au premier démarrage)
    if [ "$AEGER_PLATEFORME" = virtualbox ]; then s "$vm" 'sudo bash -s' <<<"$CHRONY"; fi
  done
}

CHRONY='set -e
export DEBIAN_FRONTEND=noninteractive
command -v chronyd >/dev/null || { apt-get update -qq && apt-get install -y -qq chrony >/dev/null; }
cat > /etc/chrony/conf.d/aeger.conf <<C
# AEGER : sous Hyper-V, horloge des VM qui dérive vite -> interrogations fréquentes et correction immédiate
pool 2.ubuntu.pool.ntp.org iburst minpoll 4 maxpoll 6 maxsources 3
makestep 0.1 -1
C
systemctl restart chrony'

cmd_time() {
  for vm in "$@"; do need_vm "$vm"; log "$vm : horloge (chrony)"
    s "$vm" 'sudo bash -s' <<<"$CHRONY"
  done
}

cmd_load() {
  for vm in "$@"; do need_vm "$vm"
    for i in ${IMAGES[$vm]}; do
      log "$vm : image aeger/$i"
      cp_to "$vm" /opt/aeger/images/ "$IMAGES_DIR/$i.tar.gz" 2>/dev/null || {
        s "$vm" "sudo chown aeger /opt/aeger/images"; cp_to "$vm" /opt/aeger/images/ "$IMAGES_DIR/$i.tar.gz"; }
      s "$vm" "gunzip -c /opt/aeger/images/$i.tar.gz | sudo docker load -q && rm /opt/aeger/images/$i.tar.gz"
    done
  done
}

compose_push() {   # docker-compose.yml + .env (profil de la VM) + dossiers de configuration montés
  local vm=$1 c
  # les fichiers sont écrasés sur place (un conteneur lancé garde le même fichier monté) ;
  # seuls les dossiers de l'ancienne organisation (avant le rangement par zone) sont supprimés
  s "$vm" "sudo mkdir -p /opt/aeger/compose && cd /opt/aeger/compose && sudo rm -rf nginx keycloak mosquitto seaweedfs grafana grafana-mtls && sudo chown -R aeger /opt/aeger/compose"
  cp_to "$vm" /opt/aeger/compose/ "$SERVICES/docker-compose.yml"
  s "$vm" "printf 'COMPOSE_PROFILES=%s\nAEGER_PUBLIC_HOST=%s\n' $vm $AEGER_PUBLIC_HOST > /opt/aeger/compose/.env"
  for c in ${CONFIG[$vm]:-}; do
    s "$vm" "mkdir -p /opt/aeger/compose/$(dirname "$c")"
    cp_to "$vm" "/opt/aeger/compose/$(dirname "$c")/" -r "$SERVICES/$c"
    s "$vm" "chmod -R a+rX /opt/aeger/compose/$(dirname "$c")"   # lisible par l'utilisateur du conteneur (pas de secret ici)
    # mosquitto exige que son fichier ACL lui appartienne et ne soit lisible que par lui
    if [ "$(basename "$c")" = mosquitto ]; then
      s "$vm" "sudo chown -R 1883 /opt/aeger/compose/$c && sudo chmod 0600 /opt/aeger/compose/$c/acl"
    fi
  done
}

cmd_vault() {
  local vm=vm-sec-01
  log "Vault : démarrage sans TLS + bootstrap"
  s $vm "sudo mkdir -p /opt/aeger/vault/{data,init,tls,out}"
  compose_push $vm
  s $vm "cd /opt/aeger/compose && sudo docker compose up -d vault && sleep 5 && sudo docker exec vault aeger-vault-bootstrap.sh init"
  log "Vault : redémarrage avec l'écoute TLS 8200"
  s $vm "cd /opt/aeger/compose && sudo docker compose restart vault && sleep 6 && sudo docker logs --tail 5 vault"
}

cmd_agent() {
  local ca
  # configurations des Vault Agent, avec l'adresse publique d'AEGER dans les certificats de nginx et mosquitto
  python -I "$SERVICES/securite/vault-agent/gen_configs.py" >/dev/null
  ca=$(s vm-sec-01 "sudo cat /opt/aeger/vault/init/ca.pem")
  for vm in "$@"; do need_vm "$vm"; log "$vm : Vault Agent"
    local creds rid sid
    creds=$(s vm-sec-01 "sudo docker exec vault aeger-vault-bootstrap.sh approle $vm")
    rid=$(echo "$creds" | python -c 'import json,sys; print(json.load(sys.stdin)["role_id"])')
    sid=$(echo "$creds" | python -c 'import json,sys; print(json.load(sys.stdin)["secret_id"])')
    s "$vm" "sudo tee /opt/aeger/vault-agent/ca.pem >/dev/null && echo $rid | sudo tee /opt/aeger/vault-agent/role_id >/dev/null \
      && echo $sid | sudo tee /opt/aeger/vault-agent/secret_id >/dev/null && sudo chmod 600 /opt/aeger/vault-agent/secret_id" <<<"$ca"
    s "$vm" "sudo chown -R aeger /opt/aeger/vault-agent"
    cp_to "$vm" /opt/aeger/vault-agent/agent.hcl "$HERE/$vm/vault-agent.hcl"
    s "$vm" "sudo chown -R root:root /opt/aeger/vault-agent"
    compose_push "$vm"
    s "$vm" "cd /opt/aeger/compose && sudo docker compose up -d vault-agent"
    for i in $(seq 1 30); do
      if s "$vm" "sudo test -s /opt/aeger/secrets/ca.pem && sudo ls /opt/aeger/secrets/*-cert.pem >/dev/null 2>&1"; then break; fi
      sleep 3
    done
    s "$vm" "sudo ls -l /opt/aeger/secrets/ | tail -n +2 | awk '{print \"   \" \$1, \$3\":\"\$4, \$NF}'"
  done
}

cmd_up() {
  for vm in "$@"; do need_vm "$vm"; log "$vm : docker compose up"
    compose_push "$vm"
    s "$vm" "cd /opt/aeger/compose && sudo docker compose up -d --no-build --remove-orphans && sudo docker compose ps --format 'table {{.Name}}\t{{.Status}}'"
  done
}

cmd_systemd() {   # kafka | postgres
  local svc=$1 vm dir
  case $svc in kafka) vm=vm-kafka-01; dir=tier4-donnees/kafka ;; postgres) vm=vm-db-01; dir=tier4-donnees/postgresql ;; esac
  log "$vm : installation de $svc (systemd)"
  local tmp="/tmp/aeger-$svc"
  s $vm "rm -rf $tmp && mkdir -p $tmp"
  cp_to $vm "$tmp/" "$SERVICES/$dir"/*
  s $vm "sudo bash $tmp/install.sh"
}

cmd_realm_key() {
  s vm-sec-01 "sudo docker exec vault aeger-vault-bootstrap.sh realm-key https://vm-sec-01:8443/auth/realms/aeger"
}

cmd_bracelets() {
  local nhr=${1:-1000} nstd=${2:-9000} dest="$SERVICES/tier1-externe/capteur/certs"
  s vm-sec-01 "sudo docker exec vault aeger-vault-bootstrap.sh bracelets $nhr $nstd"
  mkdir -p "$dest"
  s vm-sec-01 "sudo cat /opt/aeger/vault/out/bracelets.tar.gz" | tar -xzf - -C "$dest"
  log "certificats des bracelets dans $dest : $(ls "$dest/hr" | wc -l) haut risque, $(ls "$dest/std" | wc -l) standards"
}

case "${1:-}" in
  images) cmd_images ;;
  prep) shift; cmd_prep "$@" ;;
  load) shift; cmd_load "$@" ;;
  vault) cmd_vault ;;
  agent) shift; cmd_agent "$@" ;;
  up) shift; cmd_up "$@" ;;
  kafka|postgres) cmd_systemd "$1" ;;
  realm-key) cmd_realm_key ;;
  bracelets) shift; cmd_bracelets "$@" ;;
  time) shift; cmd_time "$@" ;;
  ssh) shift; vm=$1; shift; s "$vm" "$@" ;;
  *) sed -n '2,15p' "$0"; exit 1 ;;
esac
