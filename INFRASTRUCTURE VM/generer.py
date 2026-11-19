# -*- coding: utf-8 -*-
"""Génère le réseau AEGER v2 pour VMware vSphere, à partir d'une seule source de vérité.

- _seed/<VM>/ : configuration cloud-init (NoCloud) de chaque VM (réseau, routes, pare-feu, compte aeger),
  puis creer-iso.ps1 en fait un petit CD par VM (_seed/<VM>.iso) à déposer sur un datastore ;
- vms.json : la liste des VM, de leurs ressources et de leurs cartes réseau (lue par creer-vms-vsphere.ps1) ;
- ARCHITECTURE APPLICATIVE/AEGER_reseau_VM.md : le document réseau (mêmes commandes que celles appliquées).

Les 11 VM du diagramme, sans VM supplémentaire :
- VM-EDGE-01 est la seule VM branchée sur le switch LAN : entrée du Tier 1, et passerelle des autres VM
  (sortie Internet par NAT, DNS, heure, bastion SSH) ;
- VM-COLD-01 (Airflow, aucun port ouvert) porte aussi le routage et le filtrage entre les 5 VLAN.

Paramètres propres à votre vSphere (groupes de ports, adresse de VM-EDGE-01 sur le LAN) : site.json.
Lancer depuis ce dossier :  python generer.py
"""
import json
import pathlib

HERE = pathlib.Path(__file__).resolve().parent
PROJECT = HERE.parent
SEED_ROOT = HERE / "_seed"
DOC_PATH = PROJECT / "ARCHITECTURE APPLICATIVE" / "AEGER_reseau_VM.md"
JSON_PATH = HERE / "vms.json"
RESULTS_PATH = HERE / "resultats-tests-reseau.json"
SITE = json.loads((HERE / "site.json").read_text(encoding="utf-8"))
_pub = pathlib.Path.home() / ".ssh" / "aeger_ed25519.pub"
PUBKEY = _pub.read_text().strip() if _pub.exists() else "ssh-ed25519 A_REMPLACER_PAR_VOTRE_CLE aeger-admin"

USER = PASSWORD = "aeger"
PLAN = "10.0.0.0/16"
VLANS = [10, 20, 30, 40, 50]
VLAN_NAME = {10: "DMZ", 20: "SÉCURITÉ", 30: "TEMPS RÉEL (hot)", 40: "DONNÉES", 50: "ANALYTIQUE (cold)"}
VLAN_ASCII = {10: "DMZ", 20: "SECURITE", 30: "TEMPS REEL", 40: "DONNEES", 50: "ANALYTIQUE"}
VLAN_TIER = {10: "Tier 2", 20: "Sécurité", 30: "Tier 3", 40: "Tier 4", 50: "Tier 5"}
PG = {k: SITE["portgroups"][str(k)] for k in VLANS}               # groupes de ports vSphere
VID = {k: SITE["vlan_ids"][str(k)] for k in VLANS}
PG_LAN = SITE["portgroup_lan"]
GW = {k: f"10.0.{k}.1" for k in VLANS}                            # passerelle de chaque VLAN = VM-COLD-01
IF = {k: f"lan{k}" for k in VLANS}                                # nom de la carte d'un VLAN dans Ubuntu
EXT = "ext"                                                       # carte de VM-EDGE-01 sur le switch LAN

RNAME = "VM-COLD-01"      # routeur entre les VLAN (aucun flux entrant, aucune donnée sensible)
ENAME = "VM-EDGE-01"      # seule VM sur le LAN : entrée Tier 1 + passerelle Internet, DNS, NTP, bastion
EDGE_IP = "10.0.10.11"


def mac(vlan, last):
    """MAC fixe (plage manuelle autorisée par vSphere : 00:50:56:00:00:00 à 00:50:56:3F:FF:FF)."""
    return f"00:50:56:0a:{vlan}:{last:02d}"


def mac_ext(last):
    return f"00:50:56:0a:ff:{last:02d}"


EDGE_LAN = None if SITE["edge_lan"] == "dhcp" else SITE["edge_lan"]
PUBLIC = EDGE_LAN.split("/")[0] if EDGE_LAN else "<IP-EDGE>"     # adresse publique d'AEGER = bastion SSH


def vm(name, ip, cpus, ram, disk, role):
    vlan = int(ip.split(".")[2])
    last = int(ip.split(".")[3])
    nics = [dict(name=IF[vlan], portgroup=PG[vlan], mac=mac(vlan, last))]
    if name == RNAME:          # routeur : une carte dans chacun des autres VLAN
        nics += [dict(name=IF[k], portgroup=PG[k], mac=mac(k, 1)) for k in VLANS if k != vlan]
    if name == ENAME:          # passerelle : une carte sur le switch LAN
        nics.append(dict(name=EXT, portgroup=PG_LAN, mac=mac_ext(last)))
    return dict(name=name, host=name.lower(), ip=ip, vlan=vlan, gw=GW[vlan], cpus=cpus, ram=ram, disk=disk,
                role=role, edge=(name == ENAME), router=(name == RNAME), nics=nics)


# Ressources : le nécessaire pour faire tourner les services (1 vCPU par VM)
VMS = [
    vm("VM-EDGE-01", "10.0.10.11", 1, 768, 25, "NGINX + passerelle (Internet, DNS, NTP, bastion SSH)"),
    vm("VM-SEC-01", "10.0.20.11", 1, 1536, 25, "Keycloak + Vault"),
    vm("VM-HOT-01", "10.0.30.11", 1, 1024, 25, "mosquitto + ingestion"),
    vm("VM-HOT-02", "10.0.30.12", 1, 1024, 25, "notification + alerting"),
    vm("VM-HOT-03", "10.0.30.13", 1, 1024, 25, "vitals"),
    vm("VM-KAFKA-01", "10.0.40.11", 1, 1280, 50, "Apache Kafka"),
    vm("VM-DB-01", "10.0.40.12", 1, 1024, 50, "PostgreSQL + TimescaleDB"),
    vm("VM-COLD-01", "10.0.50.11", 1, 1536, 25, "Apache Airflow + routeur entre les VLAN"),
    vm("VM-COLD-02", "10.0.50.12", 1, 768, 60, "Data Lake (SeaweedFS)"),
    vm("VM-COLD-03", "10.0.50.13", 1, 2560, 25, "Apache Spark"),
    vm("VM-COLD-04", "10.0.50.14", 1, 768, 25, "Grafana"),
]
BY = {v["name"]: v for v in VMS}
RT, ED = BY[RNAME], BY[ENAME]
RAM = {v["name"]: v["ram"] for v in VMS}

# Flux entrants depuis le Tier 1 (postes du LAN) vers VM-EDGE-01, carte ext
TIER1 = [
    ("F01", 8883, "Bracelets haut risque -> NGINX", "[1] MQTT sur TLS · mTLS"),
    ("F02", 8443, "Bracelets standards -> NGINX", "HTTPS · mTLS"),
    ("F03", 443, "Medecin et analyste -> NGINX", "[7] HTTPS + WebSocket · HTTPS TLS + OIDC"),
]

# (n°, source, destination, port TCP, libellé ASCII pour ufw, flèche du diagramme)
FLOWS = [
    ("F04", "VM-EDGE-01", "VM-HOT-01", 8883, "NGINX -> mosquitto", "[2] relais TCP 8883"),
    ("F05", "VM-EDGE-01", "VM-HOT-01", 8000, "NGINX -> ingestion", "HTTPS 8000 · mTLS"),
    ("F06", "VM-EDGE-01", "VM-HOT-02", 8000, "NGINX -> notification", "[8] WebSocket relayé · 8000"),
    ("F07", "VM-EDGE-01", "VM-HOT-03", 8000, "NGINX -> vitals", "historique patient · HTTPS 8000"),
    ("F08", "VM-EDGE-01", "VM-COLD-04", 3000, "NGINX -> Grafana", "/grafana · HTTPS 3000"),
    ("F09", "VM-EDGE-01", "VM-SEC-01", 8443, "NGINX -> Keycloak", "/auth · connexion OIDC · HTTPS 8443"),
    ("F10", "VM-EDGE-01", "VM-SEC-01", 8200, "NGINX -> Vault", "🔑 8200"),
    ("F11", "VM-HOT-01", "VM-KAFKA-01", 9093, "ingestion -> Kafka", "[4] écrit dans vitals.raw · 9093"),
    ("F12", "VM-HOT-02", "VM-KAFKA-01", 9093, "alerting -> Kafka", "[5] lit vitals.raw · 9093"),
    ("F13", "VM-HOT-03", "VM-KAFKA-01", 9093, "vitals -> Kafka", "lit vitals.raw · 9093"),
    ("F14", "VM-HOT-03", "VM-DB-01", 5432, "vitals -> PostgreSQL", "SQL · TLS 5432"),
    ("F15", "VM-SEC-01", "VM-DB-01", 5432, "Keycloak -> PostgreSQL", "Keycloak → PostgreSQL · 5432"),
    ("F16", "VM-COLD-01", "VM-COLD-03", 7077, "Airflow -> Spark", "spark-submit (port non indiqué, 7077 supposé)"),
    ("F17", "VM-COLD-03", "VM-KAFKA-01", 9093, "Spark -> Kafka", "lit vitals.raw · 1× / jour · 9093"),
    ("F18", "VM-COLD-03", "VM-COLD-02", 8333, "Spark -> Data Lake", "API S3 · TLS 8333"),
    ("F19", "VM-COLD-03", "VM-DB-01", 5432, "Spark -> PostgreSQL", "écrit les KPI · TLS 5432"),
    ("F20", "VM-COLD-04", "VM-DB-01", 5432, "Grafana -> PostgreSQL", "lit les KPI · TLS 5432"),
    ("F21", "VM-COLD-04", "VM-SEC-01", 8443, "Grafana -> Keycloak", "Grafana → Keycloak · 8443"),
]
for _n in ["VM-HOT-01", "VM-HOT-02", "VM-HOT-03", "VM-KAFKA-01", "VM-DB-01",
           "VM-COLD-01", "VM-COLD-02", "VM-COLD-03", "VM-COLD-04"]:
    FLOWS.append(("F22", _n, "VM-SEC-01", 8200, "Vault Agent -> Vault", "🔑 Vault Agent → Vault · 8200"))

# Flux d'infrastructure (pas des flèches du schéma)
INFRA = [
    ("I1", "chaque VM", f"Internet, via {RNAME} puis le NAT de {ENAME}", "80, 443", "mises à jour (apt) et images Docker"),
    ("I2", "chaque VM", f"{ENAME} ({EDGE_IP})", "53 (DNS), 123/udp (NTP)", "noms de domaine et heure"),
    ("I3", "poste d'administration (LAN)", f"{ENAME} (carte ext)", "22", "SSH d'administration (bastion, clé uniquement)"),
    ("I4", f"{ENAME} ({EDGE_IP})", "chaque VM", "22", "SSH d'administration relayé par le bastion"),
]

# Flux interdits testés depuis chaque VM (destination, port)
NEG = {
    "VM-EDGE-01": [("VM-KAFKA-01", 9093), ("VM-DB-01", 5432), ("VM-SEC-01", 5432)],
    "VM-SEC-01": [("VM-HOT-01", 8000), ("VM-KAFKA-01", 22)],
    "VM-HOT-01": [("VM-DB-01", 5432), ("VM-HOT-02", 8000), ("VM-COLD-02", 8333)],
    "VM-HOT-02": [("VM-DB-01", 5432), ("VM-HOT-03", 8000)],
    "VM-HOT-03": [("VM-COLD-04", 3000), ("VM-HOT-01", 8883)],
    "VM-KAFKA-01": [("VM-DB-01", 5432), ("VM-COLD-03", 7077)],
    "VM-DB-01": [("VM-KAFKA-01", 9093), ("VM-HOT-01", 8000)],
    "VM-COLD-01": [("VM-KAFKA-01", 9093), ("VM-COLD-02", 8333), ("VM-DB-01", 5432)],
    "VM-COLD-02": [("VM-COLD-03", 7077), ("VM-KAFKA-01", 9093)],
    "VM-COLD-03": [("VM-COLD-04", 3000), ("VM-HOT-01", 8000)],
    "VM-COLD-04": [("VM-KAFKA-01", 9093), ("VM-COLD-02", 8333)],
}


# ---------------------------------------------------------------- calculs
def flows_out(v):
    return [f for f in FLOWS if f[1] == v["name"]]


def flows_in(v):
    return [f for f in FLOWS if f[2] == v["name"]]


def crosses(f):
    return BY[f[1]]["vlan"] != BY[f[2]]["vlan"]


def src_ip(src, dst):
    """Adresse source réelle d'un flux. VM-COLD-01 étant branchée sur tous les VLAN, elle joint une VM d'un autre
    VLAN directement, avec son adresse dans ce VLAN (10.0.X.1) et non avec 10.0.50.11."""
    if src == RNAME and BY[dst]["vlan"] != RT["vlan"]:
        return GW[BY[dst]["vlan"]]
    return BY[src]["ip"]


assert not [f for f in FLOWS if f[2] == RNAME and crosses(f)], "le routeur ne doit recevoir aucun flux d'un autre VLAN"


def uniq(seq):
    out = []
    for x in seq:
        if x not in out:
            out.append(x)
    return out


def fr(x):
    return f"{x:g}".replace(".", ",")


# ---------------------------------------------------------------- fichiers des VM (version doc, avec sudo)
HOSTS = "\n".join(
    ["127.0.0.1 localhost", "", "# AEGER - une ligne par VM (adresse dans son VLAN)"]
    + [f"{v['ip']:<12} {v['host']}" for v in VMS]
    + ["", "::1     localhost ip6-localhost ip6-loopback", "ff02::1 ip6-allnodes", "ff02::2 ip6-allrouters"]
) + "\n"

NIC_STATIC = ["dhcp4: false", "dhcp6: false", "accept-ra: false", "link-local: []"]


def nic_block(name, mac_addr, comment):
    return [f"    {name}:   # {comment}", "      match:", f'        macaddress: "{mac_addr}"', f"      set-name: {name}"]


def ext_block(name, mac_addr, static, comment):
    """Carte sur le switch LAN : DHCP, ou IP fixe avec passerelle et DNS du LAN."""
    L = nic_block(name, mac_addr, comment)
    if static:
        L += ["      " + o for o in NIC_STATIC] + ["      addresses:", f"        - {static}"]
        if SITE.get("passerelle_lan"):
            L += ["      routes:", "        - to: default", f"          via: {SITE['passerelle_lan']}"]
        if SITE.get("dns_lan"):
            L += ["      nameservers:", f"        addresses: [{', '.join(SITE['dns_lan'])}]"]
    else:
        L += ["      dhcp4: true", "      dhcp6: false", "      accept-ra: false"]
    return L


def netplan(v):
    k = v["vlan"]
    L = [f"# Réseau de {v['name']} - VLAN {k} {VLAN_ASCII[k]}",
         "# Chaque carte est reconnue par son adresse MAC (fixée dans vSphere) et renommée."]
    L += ["network:", "  version: 2", "  ethernets:"]
    L += nic_block(IF[k], v["nics"][0]["mac"], f"groupe de ports {PG[k]}")
    L += ["      " + o for o in NIC_STATIC]
    if v["router"]:
        # son adresse + la passerelle de son propre VLAN (10.0.50.1) ; sortie vers Internet par VM-EDGE-01
        L += ["      addresses:", f"        - {v['ip']}/24", f"        - {GW[k]}/24   # passerelle du VLAN {k}",
              "      nameservers:", f"        addresses: [{EDGE_IP}]"]
        for n in v["nics"][1:]:
            j = int(n["name"][3:])
            L += nic_block(IF[j], n["mac"], f"groupe de ports {PG[j]}")
            L += ["      " + o for o in NIC_STATIC] + ["      addresses:", f"        - {GW[j]}/24   # passerelle du VLAN {j}"]
            if j == ED["vlan"]:
                L += ["      routes:", "        - to: default", f"          via: {EDGE_IP}   # Internet par VM-EDGE-01 (NAT)"]
    elif v["edge"]:
        # VM-EDGE-01 : sortie directe par le LAN (carte ext) ; le plan AEGER passe par le routeur VM-COLD-01
        L += ["      addresses:", f"        - {v['ip']}/24",
              "      routes:", f"        - to: {PLAN}", f"          via: {v['gw']}   # VM-COLD-01 (routeur)"]
        L += ext_block(EXT, v["nics"][1]["mac"], EDGE_LAN, f"groupe de ports {PG_LAN} : adresse publique d'AEGER")
    else:
        L += ["      addresses:", f"        - {v['ip']}/24",
              "      routes:", "        - to: default", f"          via: {v['gw']}   # VM-COLD-01 : autres VLAN et Internet",
              "      nameservers:", f"        addresses: [{EDGE_IP}]   # DNS servi par VM-EDGE-01"]
    return "\n".join(L) + "\n"


UFW_HEAD = ["sudo ufw --force reset", "sudo ufw default deny incoming", "sudo ufw default allow outgoing",
            "sudo ufw default deny routed"]
ADMIN_VM = "comment 'ADMIN ssh depuis le bastion (VM-EDGE-01)'"
ROUTING_CMDS = [
    "echo 'net.ipv4.ip_forward=1' | sudo tee /etc/sysctl.d/90-aeger-routage.conf",
    "sudo sysctl -p /etc/sysctl.d/90-aeger-routage.conf",
    "sudo sed -i 's|^#net/ipv4/ip_forward=1|net/ipv4/ip_forward=1|' /etc/ufw/sysctl.conf",
]


def inbound_rules(v):
    """Flux du diagramme qui arrivent sur la VM (source et port précis)."""
    R = []
    for fid, src, dst, port, lab, _ in flows_in(v):
        R.append(f"sudo ufw allow in on {IF[v['vlan']]} proto tcp from {src_ip(src, dst)} to {v['ip']} port {port} "
                 f"comment '{fid} {src}: {lab}'")
    return R


def ufw_vm(v):
    R = list(UFW_HEAD)
    R.append(f"sudo ufw allow in on {IF[v['vlan']]} proto tcp from {EDGE_IP} to {v['ip']} port 22 {ADMIN_VM}")
    R += inbound_rules(v)
    R.append("sudo ufw --force enable")
    return R


def time_cmds(v):
    """Heure des VM : synchronisée sur VM-EDGE-01 (NTP), sans paquet à installer."""
    return ["sudo mkdir -p /etc/systemd/timesyncd.conf.d",
            f"printf '[Time]\\nNTP={EDGE_IP}\\nFallbackNTP=\\n' | sudo tee /etc/systemd/timesyncd.conf.d/aeger.conf",
            "sudo systemctl restart systemd-timesyncd"]


# ---- VM-EDGE-01 : passerelle (NAT, DNS, NTP, bastion)
NAT_RULES = (f"# AEGER-NAT : les VM sortent sur Internet avec l'adresse de VM-EDGE-01 (carte {EXT})\n"
             "*nat\n:POSTROUTING ACCEPT [0:0]\n"
             f"-A POSTROUTING -s {PLAN} -o {EXT} -j MASQUERADE\nCOMMIT\n")
DNSMASQ = ("# DNS des VM AEGER : VM-EDGE-01 répond sur son adresse du VLAN 10 et relaie vers le DNS du LAN\n"
           "bind-dynamic\n"
           f"listen-address={EDGE_IP}\n"
           "no-resolv\nserver=127.0.0.53\ndomain-needed\nbogus-priv\ncache-size=1000\n")
CHRONY_SERVER = ("# Heure : VM-EDGE-01 se synchronise sur Internet et sert l'heure aux VM\n"
                 f"allow {PLAN}\nlocal stratum 10\n")


def ufw_edge():
    R = list(UFW_HEAD)
    src = SITE.get("admin_ssh_depuis") or "any"
    R.append(f"sudo ufw allow in on {EXT} proto tcp from {src} to any port 22 comment 'ADMIN ssh (bastion), cle uniquement'")
    for fid, port, lab, _ in TIER1:
        R.append(f"sudo ufw allow in on {EXT} proto tcp to any port {port} comment '{fid} Tier 1: {lab}'")
    R.append(f"sudo ufw allow in on {IF[10]} from {PLAN} to {EDGE_IP} port 53 comment 'I2 DNS pour les VM'")
    R.append(f"sudo ufw allow in on {IF[10]} proto udp from {PLAN} to {EDGE_IP} port 123 comment 'I2 NTP pour les VM'")
    R += ["sudo cp -n /etc/ufw/before.rules /etc/ufw/before.rules.orig",
          "sudo sh -c 'cat /etc/ufw/aeger-nat.rules /etc/ufw/before.rules.orig > /etc/ufw/before.rules'"]
    R.append(f"sudo ufw route allow in on {IF[10]} out on {EXT} proto tcp from {PLAN} to any port 80,443 "
             "comment 'I1 Internet des VM (apt, Docker)'")
    R.append("sudo ufw --force enable")
    return R


# ---- VM-COLD-01 : routeur entre les VLAN
def ufw_router():
    R = list(UFW_HEAD)
    R.append(f"sudo ufw allow in on {IF[10]} proto tcp from {EDGE_IP} port 22 {ADMIN_VM}".replace(
        f"from {EDGE_IP} port 22", f"from {EDGE_IP} to any port 22"))
    R += inbound_rules(RT)
    out = IF[ED["vlan"]]
    for k in VLANS:
        if k == ED["vlan"]:
            continue
        net = f"10.0.{k}.0/24"
        R.append(f"sudo ufw route allow in on {IF[k]} out on {out} proto tcp from {net} to any port 80,443 "
                 f"comment 'I1 Internet du VLAN {k} via VM-EDGE-01'")
        R.append(f"sudo ufw route allow in on {IF[k]} out on {out} from {net} to {EDGE_IP} port 53 comment 'I2 DNS du VLAN {k}'")
        R.append(f"sudo ufw route allow in on {IF[k]} out on {out} proto udp from {net} to {EDGE_IP} port 123 "
                 f"comment 'I2 NTP du VLAN {k}'")
        R.append(f"sudo ufw route allow in on {out} out on {IF[k]} proto tcp from {EDGE_IP} to {net} port 22 "
                 f"comment 'I4 SSH admin vers le VLAN {k}'")
    for fid, s, d, port, lab, _ in FLOWS:
        if not crosses((fid, s, d)) or RNAME in (s, d):
            continue
        a, b = BY[s], BY[d]
        R.append(f"sudo ufw route allow in on {IF[a['vlan']]} out on {IF[b['vlan']]} proto tcp "
                 f"from {a['ip']} to {b['ip']} port {port} comment '{fid} {s} -> {d}: {lab}'")
    R.append("sudo ufw --force enable")
    return R


def as_root(cmds):
    return [c.replace("sudo ", "") for c in cmds]


def test_script(v):
    L = ["#!/bin/bash",
         f"# aeger-test-flux - teste les flux reseau de {v['name']} (genere)",
         "# ACCESSIBLE : le paquet arrive (service actif, ou 'refused' = aucun service pour l'instant)",
         "# BLOQUE     : filtre par le routeur ou par le pare-feu de la VM d'en face, ou pas de route",
         "ok=0; ko=0",
         "check() {",
         "  local attendu=\"$1\" ip=\"$2\" port=\"$3\" lib=\"$4\" out rc etat detail r",
         "  out=$(timeout 4 bash -c \"exec 3<>/dev/tcp/$ip/$port\" 2>&1); rc=$?",
         "  if [ $rc -eq 0 ]; then etat=ACCESSIBLE; detail=\"service actif\"",
         "  elif [[ \"$out\" == *refused* ]]; then etat=ACCESSIBLE; detail=\"aucun service pour l'instant\"",
         "  elif [ $rc -eq 124 ]; then etat=BLOQUE; detail=\"filtre\"",
         "  elif [[ \"$out\" == *\"No route\"* || \"$out\" == *nreachable* ]]; then etat=BLOQUE; detail=\"pas de route\"",
         "  else etat=BLOQUE; detail=\"${out##*: }\"; fi",
         "  if [ \"$etat\" = \"$attendu\" ]; then r=\"OK    \"; ok=$((ok+1)); else r=\"ERREUR\"; ko=$((ko+1)); fi",
         "  printf '%s  %-11s -> %-18s %-5s %-10s %-29s %s\\n' \"$r\" \"$(hostname)\" \"$ip\" \"$port\" \"$etat\" \"($detail)\" \"$lib\"",
         "}"]
    for fid, src, dst, port, lab, _ in flows_out(v):
        via = "via VM-COLD-01" if BY[src]["vlan"] != BY[dst]["vlan"] else "meme VLAN"
        L.append(f"check ACCESSIBLE {BY[dst]['ip']} {port} '{fid} autorise : {dst} ({lab}, {via})'")
    for dst, port in NEG.get(v["name"], []):
        L.append(f"check BLOQUE {BY[dst]['ip']} {port} 'interdit : {dst} port {port}'")
    if v["edge"]:
        L.append("check ACCESSIBLE archive.ubuntu.com 80 'Internet par le LAN (carte ext)'")
    else:
        L.append("check ACCESSIBLE archive.ubuntu.com 80 'I1 Internet via VM-COLD-01 et le NAT de VM-EDGE-01 (DNS de VM-EDGE-01)'")
        L.append("check BLOQUE archive.ubuntu.com 25 'interdit : Internet hors 80/443'")
    L += ["echo \"== $(hostname) : $ok OK, $ko ERREUR\"", "[ $ko -eq 0 ]"]
    return "\n".join(L) + "\n"


def block(text, indent):
    pad = " " * indent
    return [pad + l if l else "" for l in text.rstrip("\n").split("\n")]


def script(title, cmds):
    return "\n".join(["#!/bin/bash", f"# {title} (genere)", "set -e"] + as_root(cmds) + ["ufw status numbered"]) + "\n"


def user_data(v, files, runcmd, packages=()):
    L = ["#cloud-config", f"hostname: {v['host']}", "manage_etc_hosts: false", "timezone: Europe/Paris",
         "keyboard:", "  layout: fr", "users:", f"  - name: {USER}", "    gecos: Administrateur AEGER",
         "    groups: [adm, sudo]", "    shell: /bin/bash", "    sudo: \"ALL=(ALL) NOPASSWD:ALL\"",
         "    lock_passwd: false", f"    plain_text_passwd: {PASSWORD}", "    ssh_authorized_keys:",
         f"      - {PUBKEY}", "ssh_pwauth: false", "package_upgrade: false"]
    if packages:
        L += ["package_update: true", "packages:"] + [f"  - {p}" for p in packages]
    else:
        L += ["package_update: false"]
    L += ["write_files:"]
    files = list(files) + [("/etc/cloud/cloud.cfg.d/99-aeger.cfg", "0644",
                            "# le reseau est gere par /etc/netplan/*.yaml\nnetwork: {config: disabled}\n")]
    for path, perm, content in files:
        L += [f"  - path: {path}", f"    permissions: \"{perm}\"", "    owner: root:root", "    content: |"]
        L += block(content, 6)
    L += ["runcmd:"] + [f"  - {c}" for c in runcmd]
    return "\n".join(L) + "\n"


MARK = ("[sh, -c, \"{ echo '### AEGER CONFIG'; hostname; ip -br addr; ip route; ufw status numbered; "
        "echo '### AEGER FIN'; } > /dev/console 2>&1\"]")


def user_data_for(v):
    test = ("/usr/local/bin/aeger-test-flux", "0755", test_script(v))
    if v["edge"]:
        files = [("/etc/hosts", "0644", HOSTS), ("/etc/ufw/aeger-nat.rules", "0644", NAT_RULES),
                 ("/etc/dnsmasq.d/aeger.conf", "0644", DNSMASQ), ("/etc/chrony/conf.d/aeger.conf", "0644", CHRONY_SERVER),
                 ("/usr/local/sbin/aeger-pare-feu.sh", "0755",
                  script("Passerelle (NAT, DNS, NTP, bastion) et pare-feu AEGER - VM-EDGE-01", ROUTING_CMDS + ufw_edge())),
                 test]
        return user_data(v, files, ["[/usr/local/sbin/aeger-pare-feu.sh]", "[systemctl, restart, dnsmasq, chrony]", MARK],
                         packages=["dnsmasq", "chrony"])
    if v["router"]:
        cmds = ROUTING_CMDS + time_cmds(v) + ufw_router()
        title = "Routage entre VLAN, heure et pare-feu AEGER - VM-COLD-01"
    else:
        cmds = time_cmds(v) + ufw_vm(v)
        title = f"Heure et pare-feu AEGER - {v['name']}"
    files = [("/etc/hosts", "0644", HOSTS), ("/usr/local/sbin/aeger-pare-feu.sh", "0755", script(title, cmds)), test]
    return user_data(v, files, ["[/usr/local/sbin/aeger-pare-feu.sh]", MARK])


def meta_data(v):
    return f"instance-id: aeger-{v['host']}-vsphere-v2\nlocal-hostname: {v['host']}\n"


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)


# ---------------------------------------------------------------- documentation
def md_table(head, rows):
    out = ["| " + " | ".join(head) + " |", "|" + "|".join("---" for _ in head) + "|"]
    out += ["| " + " | ".join(str(c) for c in r) + " |" for r in rows]
    return out


def schema():
    members = {10: "VM-EDGE-01", 20: "VM-SEC-01", 30: "VM-HOT-01, VM-HOT-02, VM-HOT-03",
               40: "VM-KAFKA-01, VM-DB-01", 50: "VM-COLD-02, VM-COLD-03, VM-COLD-04"}
    side = {10: "┐", 20: "│", 30: "├─ VM-COLD-01 (Airflow + routeur)", 40: "│  seul passage entre VLAN", 50: "┘  (10.0.50.11 + 10.0.50.1)"}
    L = ["Postes du LAN = TIER 1 (bracelets simulés, médecins, analystes)   +   Internet",
         "   │",
         f"═══╪══════ groupe de ports « {PG_LAN} » ═════════════  (seule VM branchée : VM-EDGE-01)",
         "   │  443, 8443, 8883 (Tier 1) · 22 (bastion SSH)",
         f"   └── VM-EDGE-01  carte {EXT} : {PUBLIC}   ·   NGINX + passerelle : NAT Internet, DNS, NTP, bastion",
         "",
         f"{'groupe de ports':<15} {'VM branchées':<46} {'réseau':<13}    carte de VM-COLD-01"]
    for k in VLANS:
        L.append(f"{PG[k]:<15} {members[k]:<46} 10.0.{k}.0/24 ── {IF[k]} {GW[k]:<10} {side[k]}")
    return L


def nic_rows(v):
    rows = []
    for n in v["nics"]:
        if n["name"] == EXT:
            addr = f"{SITE['edge_lan']} (adresse publique)"
        elif v["router"] and n is not v["nics"][0]:
            addr = f"`{GW[int(n['name'][3:])]}/24` (passerelle)"
        elif v["router"]:
            addr = f"`{v['ip']}/24` + `{GW[v['vlan']]}/24` (passerelle)"
        else:
            addr = f"`{v['ip']}/24`"
        rows.append((f"`{n['name']}`", f"`{n['portgroup']}`", f"`{n['mac']}`", addr))
    return rows


def vm_section(D, title, v, intro, steps):
    a = D.append
    a(title)
    a("")
    D += md_table(["Carte", "Groupe de ports", "MAC", "Adresse"], nic_rows(v))
    a("")
    if intro:
        a(intro)
        a("")
    for head, lines in steps:
        a(head)
        a("")
        a("```bash")
        D.extend(lines)
        a("```")
        a("")


def netplan_cmds(v):
    return (["sudo tee /etc/netplan/60-aeger.yaml > /dev/null <<'EOF'"] + netplan(v).rstrip("\n").split("\n")
            + ["EOF", "sudo chmod 600 /etc/netplan/60-aeger.yaml",
               "sudo netplan apply && sudo reboot      # le reboot applique les noms de cartes"])


def heredoc(path, text):
    return [f"sudo tee {path} > /dev/null <<'EOF'"] + text.rstrip("\n").split("\n") + ["EOF"]


def doc():
    D = []
    a = D.append
    total = sum(RAM.values()) / 1024
    a("# AEGER · Réseau des VM sur VMware vSphere")
    a("")
    a("Ce document décrit le réseau des 11 VM de l'architecture AEGER v2 ([aeger_v2.drawio](aeger_v2.drawio)) sur "
      "**VMware vSphere** : les groupes de ports (un par VLAN), le routage entre les VLAN, l'accès à Internet, l'adresse de "
      "chaque VM et les règles qui n'autorisent que les flux dessinés dans le diagramme. La configuration de chaque VM est "
      "générée par [INFRASTRUCTURE VM/generer.py](../INFRASTRUCTURE%20VM/generer.py) et appliquée automatiquement au premier "
      "démarrage ; ce document sert à la comprendre, la vérifier ou la refaire à la main.")
    a("")
    a("Système : **Ubuntu Server 22.04 LTS** sur toutes les VM. Compte : `aeger` / mot de passe `aeger` (console vSphere ; "
      "à changer avec `passwd`), SSH par clé uniquement.")
    a("")
    a("> Paramètres propres à votre vSphere (noms des groupes de ports, VLAN ID, adresse de VM-EDGE-01 sur le switch LAN) : "
      "[INFRASTRUCTURE VM/site.json](../INFRASTRUCTURE%20VM/site.json). Après modification : `python generer.py`.")
    a("")
    # 1
    a("## 1. Principe")
    a("")
    a("Les **11 VM du diagramme**, sans VM supplémentaire :")
    a("")
    a("- **Un groupe de ports par VLAN** : " + ", ".join(f"`{PG[k]}`" for k in VLANS) + ". Chaque VM est branchée sur le "
      "groupe de ports de son VLAN : elle ne voit pas les autres VLAN.")
    a(f"- **VM-EDGE-01 est la seule VM branchée sur le switch `{PG_LAN}`** (réseau de l'école et Internet). Sa carte `{EXT}` "
      f"est l'**adresse publique d'AEGER** ({PUBLIC}) : elle n'accepte que 443, 8443 et 8883 (Tier 1) et le SSH "
      "d'administration. VM-EDGE-01 sert aussi de **passerelle** aux autres VM : sortie Internet par NAT (ports 80 et 443 "
      "seulement : paquets Ubuntu, images Docker), **DNS**, **heure (NTP)** et **bastion SSH**.")
    a("- **VM-COLD-01 (Airflow) porte le routage entre les VLAN** : une carte dans chaque VLAN, avec l'adresse de passerelle "
      "`10.0.X.1` ; c'est le seul passage d'un VLAN à l'autre, et son pare-feu n'autorise que les flux du diagramme. "
      "Elle a été choisie car **aucun flux n'arrive sur elle** (aucun port ouvert) et elle ne garde aucune donnée sensible.")
    a("- **Les autres VM n'ont qu'une carte** (dans leur VLAN) et une seule route : `default via 10.0.X.1` (VM-COLD-01). "
      "Elles ne sont joignables ni depuis le LAN, ni depuis Internet.")
    a("- **Le pare-feu ufw de chaque VM reste en place** : deuxième barrière, et seule barrière entre deux VM d'un même VLAN.")
    a("")
    a("> **Pas d'étiquettes 802.1Q dans les VM.** Les VLAN sont faits par vSphere : chaque groupe de ports a son VLAN ID "
      "(le vSwitch étiquette lui-même les trames), ou bien chaque VLAN a son propre vSwitch sans carte physique.")
    a("")
    a("> **Concentration des rôles (à connaître).** VM-EDGE-01, exposée au Tier 1, est aussi la passerelle Internet et le "
      "bastion ; VM-COLD-01 voit passer tous les échanges entre VLAN. Le mTLS entre services et le pare-feu de chaque VM "
      "protègent toujours les services, mais une VM routeur dédiée serait plus sûre.")
    a("")
    a("```text")
    D.extend(schema())
    a("```")
    a("")
    # 2
    a("## 2. Plan d'adressage")
    a("")
    a("### Réseaux")
    a("")
    rows = [(f"VLAN {k} {VLAN_NAME[k]}", VLAN_TIER[k], f"`{PG[k]}`", VID[k], f"10.0.{k}.0/24", f"{GW[k]} (VM-COLD-01)") for k in VLANS]
    rows.append(("LAN (réseau de l'école)", "Tier 1 + Internet", f"`{PG_LAN}`", "(existant)", "celui de l'école", "celle de l'école"))
    D += md_table(["Réseau", "Zone du diagramme", "Groupe de ports", "VLAN ID proposé", "Adresses", "Passerelle"], rows)
    a("")
    a(f"Pour les VM : **passerelle** `10.0.X.1` (VM-COLD-01), **DNS et heure** `{EDGE_IP}` (VM-EDGE-01).")
    a("")
    a("### VM et cartes réseau")
    a("")
    a("Chaque carte a une **adresse MAC fixe** (vSphere : *Adresse MAC → Manuel*). Ubuntu reconnaît la carte grâce à elle "
      "et lui donne un nom clair (`lan30` = carte du VLAN 30, `ext` = carte du switch LAN).")
    a("")
    rows = []
    for v in VMS:
        cards = "<br>".join(f"`{n['name']}` → `{n['portgroup']}` · `{n['mac']}`" for n in v["nics"])
        if v["router"]:
            ips = f"`{v['ip']}/24`<br>passerelles : {', '.join(GW[k] for k in VLANS)}"
        elif v["edge"]:
            ips = f"`{v['ip']}/24`<br>ext : {SITE['edge_lan']}"
        else:
            ips = f"`{v['ip']}/24`"
        rows.append((f"**{v['name']}**", v["role"], cards, ips, v["cpus"], f"{v['ram']} Mo", f"{v['disk']} Go"))
    D += md_table(["VM", "Rôle", "Cartes (nom → groupe de ports · MAC)", "Adresse(s) IP", "vCPU", "RAM", "Disque"], rows)
    a("")
    a(f"Les 11 VM demandent {fr(total)} Go de RAM au total. Disques en *thin provisioning* conseillés.")
    a("")
    # 3
    a("## 3. Flux autorisés")
    a("")
    a("### Flux de l'application (repris du diagramme)")
    a("")
    rows = [(fid, "Tier 1 (LAN)", f"VM-EDGE-01 (carte {EXT})", port, "non (LAN)", diag) for fid, port, lab, diag in TIER1]
    seen22 = False
    for f in FLOWS:
        fid, src, dst, port, lab, diag = f
        via = "oui" if crosses(f) and RNAME not in (src, dst) else ("non (VM-COLD-01 est dans les deux VLAN)" if crosses(f) else "non (même VLAN)")
        if fid == "F22":
            if seen22:
                continue
            seen22 = True
            rows.append((fid, "chaque VM des tiers 3 à 5", f"VM-SEC-01 ({BY[dst]['ip']})", port, "oui", diag))
            continue
        rows.append((fid, f"{src} ({BY[src]['ip']})", f"{dst} ({BY[dst]['ip']})", port, via, diag))
    D += md_table(["N°", "Source", "Destination", "Port TCP", "Passe par VM-COLD-01", "Flèche du diagramme"], rows)
    a("")
    a("Les flèches [3] (ingestion → mosquitto) et [6] (alerting → notification) restent à l'intérieur d'une même VM. "
      "VM-COLD-01 joint VM-SEC-01 (F22) directement, avec son adresse du VLAN 20 (`10.0.20.1`).")
    a("")
    a("### Flux d'infrastructure (pas sur le schéma)")
    a("")
    D += md_table(["N°", "Source", "Destination", "Port", "Usage"], INFRA)
    a("")
    a("Tous les autres flux sont interdits, y compris depuis le LAN vers les VLAN.")
    a("")
    # 4
    a("## 4. Mise en place dans vSphere")
    a("")
    a("### 4.1 Les groupes de ports")
    a("")
    a(f"Le groupe de ports `{PG_LAN}` existe déjà. Créer les 5 autres (*Réseau → vSwitch distribué ou standard → "
      "Nouveau groupe de ports*) :")
    a("")
    D += md_table(["Groupe de ports", "VLAN ID", "VM branchées"],
                  [(f"`{PG[k]}`", VID[k], ", ".join(uniq(["VM-COLD-01"] + [v["name"] for v in VMS if v["vlan"] == k])))
                   for k in VLANS])
    a("")
    a("- **Avec VLAN ID** (cas habituel, vSwitch distribué) : les VLAN ID doivent être libres et transportés entre les hôtes "
      "ESXi si les VM sont réparties sur plusieurs hôtes. **À valider avec l'administrateur du vCenter**, qui peut imposer "
      "d'autres numéros : il suffit de les reporter dans `site.json`.")
    a("- **Sans VLAN ID possible** : créer un vSwitch standard **sans carte physique** par VLAN, avec un seul groupe de ports "
      "chacun ; toutes les VM doivent alors être sur le même hôte ESXi.")
    a("- Sécurité des groupes de ports : mode promiscuité, modifications d'adresse MAC et transmissions forgées sur **Rejeter** "
      "(valeurs par défaut) ; le routage n'en a pas besoin.")
    a("")
    a("### 4.2 L'image Ubuntu Server 22.04 LTS")
    a("")
    a("1. *Hôtes et clusters → clic droit → Déployer un modèle OVF* depuis l'URL "
      "`https://cloud-images.ubuntu.com/releases/jammy/release/ubuntu-22.04-server-cloudimg-amd64.ova` "
      "(image cloud officielle, avec cloud-init et les VMware Tools).")
    a("2. Nom : `ubuntu-22.04-cloud`. Laisser les propriétés de l'assistant vides. **Ne pas démarrer cette VM**.")
    a("3. *Clic droit → Modèle → Convertir en modèle.*")
    a("")
    a("### 4.3 Les CD de configuration (cloud-init)")
    a("")
    a("Sur le PC (PowerShell), dans `INFRASTRUCTURE VM` :")
    a("")
    a("```powershell")
    a("python generer.py     # configuration de chaque VM -> _seed\\<VM>\\ (+ ce document)")
    a(".\\creer-iso.ps1       # un CD par VM -> _seed\\<VM>.iso")
    a("```")
    a("")
    a("Déposer les 11 fichiers `.iso` sur un datastore, dans un dossier `aeger-seed` (*Stockage → datastore → Fichiers → "
      "Charger des fichiers*).")
    a("")
    a("### 4.4 Créer les 11 VM")
    a("")
    a("Pour chaque VM : *clic droit sur le modèle `ubuntu-22.04-cloud` → Nouvelle VM à partir de ce modèle* :")
    a("")
    a("1. Nom de la VM (tableau de la section 2), dossier, hôte ou cluster, datastore (*thin provisioning*).")
    a("2. **Ne pas** cocher *Personnaliser le système d'exploitation* ; cocher **Personnaliser le matériel** :")
    a("   - CPU, mémoire et taille du disque du tableau de la section 2 ;")
    a("   - **cartes réseau** : supprimer celle du modèle, puis ajouter les cartes du tableau, type **VMXNET3**, "
      "chacune sur son groupe de ports, avec **Adresse MAC : Manuel** = la MAC du tableau ;")
    a("   - **Lecteur CD/DVD** : *Fichier ISO de la banque de données* → `aeger-seed/<NOM-DE-LA-VM>.iso`, "
      "**Connecter à la mise sous tension** coché.")
    a("3. Terminer, sans démarrer.")
    a("")
    a("Ou en une commande avec PowerCLI : [INFRASTRUCTURE VM/creer-vms-vsphere.ps1](../INFRASTRUCTURE%20VM/creer-vms-vsphere.ps1).")
    a("")
    a("### 4.5 Premier démarrage")
    a("")
    a("1. **Démarrer VM-EDGE-01 en premier** et attendre environ 2 minutes : elle installe son DNS et son serveur NTP "
      "(depuis le LAN), puis devient la passerelle des autres VM. Son adresse sur le LAN s'affiche dans vSphere "
      "(*Résumé → Adresses IP*) : c'est l'**adresse publique d'AEGER** et le bastion SSH. Une IP fixe (ou une réservation "
      "DHCP) est fortement conseillée.")
    a("2. **Démarrer VM-COLD-01** (le routeur entre les VLAN).")
    a("3. Démarrer les autres VM. Chacune se configure seule au premier démarrage : nom, compte `aeger`, réseau, routes, "
      "pare-feu, heure. La console vSphere affiche `### AEGER CONFIG` puis `### AEGER FIN`.")
    a("")
    a(f"Les 11 VM demandent {fr(total)} Go de RAM. **Toujours démarrer VM-EDGE-01 puis VM-COLD-01 en premier.**")
    a("")
    # 5
    a("## 5. Configuration commune")
    a("")
    a("Résolution des noms : le même fichier `/etc/hosts` sur toutes les VM (VM-EDGE-01 sert aussi ces noms en DNS).")
    a("")
    a("```bash")
    D.extend(heredoc("/etc/hosts", HOSTS))
    a("```")
    a("")
    a("Le pare-feu `ufw` est déjà installé sur Ubuntu Server. Sur chaque VM, le SSH n'est accepté que depuis le bastion "
      f"(VM-EDGE-01, `{EDGE_IP}`).")
    a("")
    # 6
    a("## 6. Configuration de chaque VM")
    a("")
    a("Appliquée automatiquement au premier démarrage (cloud-init). Pour la refaire à la main, taper les commandes dans la "
      "VM concernée, en commençant par VM-EDGE-01 puis VM-COLD-01.")
    a("")
    vm_section(D, "### 6.1 VM-EDGE-01 · VLAN 10 DMZ · NGINX + passerelle (Internet, DNS, NTP, bastion)", ED,
               "Ports ouverts en entrée : 443, 8443 et 8883 sur `ext` (Tier 1, F01 à F03) ; 22 sur `ext` (bastion) ; "
               f"53 et 123/udp sur `lan10` pour les VM (DNS et heure).",
               [("**1. Réseau**", netplan_cmds(ED)),
                ("**2. DNS et heure pour les VM** (dnsmasq et chrony)",
                 ["sudo mkdir -p /etc/dnsmasq.d /etc/chrony/conf.d"] + heredoc("/etc/dnsmasq.d/aeger.conf", DNSMASQ)
                 + heredoc("/etc/chrony/conf.d/aeger.conf", CHRONY_SERVER)
                 + ["sudo apt-get update && sudo apt-get install -y dnsmasq chrony", "sudo systemctl restart dnsmasq chrony"]),
                ("**3. Sortie Internet des VM (NAT)**", ROUTING_CMDS + heredoc("/etc/ufw/aeger-nat.rules", NAT_RULES)),
                ("**4. Pare-feu** (`ufw route` filtre ce qui traverse VM-EDGE-01 : seulement la sortie Internet 80/443 des VM)",
                 ufw_edge()),
                ("**5. Vérifier**", ["ip -br addr", "sudo ufw status numbered",
                                     "sudo iptables -t nat -L POSTROUTING -n   # MASQUERADE de 10.0.0.0/16 vers ext",
                                     "aeger-test-flux"])])
    vm_section(D, "### 6.2 VM-COLD-01 · VLAN 50 ANALYTIQUE · Airflow + routeur entre les VLAN", RT,
               "Aucun port ouvert en entrée, sauf 22 depuis le bastion. `ufw route` filtre tout ce qui **traverse** "
               "VM-COLD-01 : chaque règle précise la carte d'entrée et de sortie, la source, la destination et le port.",
               [("**1. Réseau**", netplan_cmds(RT)),
                ("**2. Routage, heure et pare-feu**", ROUTING_CMDS + time_cmds(RT) + ufw_router()),
                ("**3. Vérifier**", ["ip -br addr                  # lan50 (10.0.50.11 + 10.0.50.1), lan10 à lan40 (10.0.X.1)",
                                     "sysctl net.ipv4.ip_forward   # doit afficher 1",
                                     "sudo ufw status numbered     # règles ALLOW FWD", "aeger-test-flux"])])
    a("Pour voir ce que VM-COLD-01 bloque : `sudo ufw logging on`, puis `sudo tail -f /var/log/ufw.log` (lignes `[UFW BLOCK]`).")
    a("")
    i = 3
    for v in VMS:
        if v["edge"] or v["router"]:
            continue
        parts = []
        groups = {}
        for fid, src, dst, port, lab, _ in flows_in(v):
            groups.setdefault((port, fid), []).append(src)
        for (port, fid), srcs in groups.items():
            parts.append(f"{port} depuis {', '.join(srcs)} ({fid})")
        parts.append("22 depuis le bastion")
        vm_section(D, f"### 6.{i} {v['name']} · VLAN {v['vlan']} {VLAN_NAME[v['vlan']]} · {v['role']}", v,
                   "Ports ouverts en entrée : " + "; ".join(parts) + ".",
                   [("**1. Réseau et route vers VM-COLD-01**", netplan_cmds(v)),
                    ("**2. Heure et pare-feu**", time_cmds(v) + ufw_vm(v)),
                    ("**3. Vérifier**", ["ip -br addr; ip route", "sudo ufw status numbered",
                                         "aeger-test-flux     # quand VM-EDGE-01, VM-COLD-01 et les autres VM sont démarrées"])])
        i += 1
    # 7
    a("## 7. Tester")
    a("")
    a("On teste si un paquet **arrive** jusqu'au port (aucun service n'est encore installé) :")
    a("")
    a("```bash")
    a("timeout 3 bash -c '</dev/tcp/10.0.40.11/9093'")
    a("```")
    a("")
    D += md_table(["Résultat", "Signification"], [
        ("`Connection refused`", "le paquet arrive, mais aucun service n'écoute encore → **flux autorisé**"),
        ("rien pendant 3 s", "bloqué par VM-COLD-01, ou par le pare-feu de la VM d'en face"),
        ("`No route to host`", "la VM d'en face ne répond pas (éteinte ?) ou VM-COLD-01 est éteinte"),
    ])
    a("")
    a("Chaque VM contient le script `aeger-test-flux` : il teste tous les flux autorisés de la VM, quelques flux interdits, "
      "et la sortie Internet, et affiche `OK` ou `ERREUR` pour chacun.")
    a("")
    a("Depuis un poste du LAN (Tier 1), seuls les ports 443, 8443, 8883 et 22 (bastion) de VM-EDGE-01 doivent répondre :")
    a("")
    a("```powershell")
    a("function Test-Port([string]$Ip, [int]$Port) {")
    a("  $c = New-Object System.Net.Sockets.TcpClient")
    a("  $t = $c.ConnectAsync($Ip, $Port)")
    a("  try { $fini = $t.Wait(3000) } catch { $fini = $true }")
    a("  $c.Dispose()")
    a("  if (-not $fini) { $r = 'BLOQUE (pas de reponse)' }")
    a("  elseif ($t.Status -eq 'RanToCompletion') { $r = 'ACCESSIBLE (service actif)' }")
    a("  elseif ($t.Exception.InnerException.SocketErrorCode -eq 'ConnectionRefused') { $r = 'ACCESSIBLE (aucun service)' }")
    a("  else { $r = 'BLOQUE (' + $t.Exception.InnerException.SocketErrorCode + ')' }")
    a('  "{0}:{1}  {2}" -f $Ip, $Port, $r')
    a("}")
    a(f"443, 8443, 8883, 22, 5432 | ForEach-Object {{ Test-Port {PUBLIC} $_ }}   # 5432 doit être BLOQUE")
    a("```")
    a("")
    if RESULTS_PATH.exists():
        res = json.loads(RESULTS_PATH.read_text(encoding="utf-8"))
        a(f"### Résultats obtenus le {res['date']}")
        a("")
        a(res.get("contexte", ""))
        a("")
        if res.get("maquette_vsphere"):
            D += md_table(["Vérification (maquette du réseau vSphere)", "Résultat"], res["maquette_vsphere"])
            a("")
    # 8
    a("## 8. Administration")
    a("")
    a("Le SSH passe par le **bastion** (VM-EDGE-01, carte `ext`), avec la clé `aeger_ed25519` (la même pour toutes les VM). "
      "Le plus simple : un fichier `%USERPROFILE%\\.ssh\\config` qui indique la clé pour le bastion **et** pour les VM "
      "(avec `ssh -J` seul, l'option `-i` ne s'appliquerait pas au premier saut) :")
    a("")
    a("```text")
    a("Host aeger-bastion")
    a(f"    HostName {PUBLIC}")
    a("    User aeger")
    a("    IdentityFile ~/.ssh/aeger_ed25519")
    a("Host vm-*")
    a("    User aeger")
    a("    IdentityFile ~/.ssh/aeger_ed25519")
    a("    ProxyJump aeger-bastion")
    a("```")
    a("")
    a("puis `ssh aeger-bastion` ou `ssh vm-hot-01` (les noms `vm-*` sont résolus par le DNS de VM-EDGE-01). Sans fichier de "
      "configuration, en une ligne :")
    a("")
    a("```powershell")
    a(f'ssh -i ~/.ssh/aeger_ed25519 -o "ProxyCommand=ssh -i ~/.ssh/aeger_ed25519 -W %h:%p aeger@{PUBLIC}" aeger@10.0.30.11')
    a("```")
    a("")
    a("Sans réseau, il reste toujours la **console vSphere** (clavier français configuré) avec `aeger` / `aeger`.")
    a("")
    # 9
    a("## 9. Hypothèses et points à vérifier")
    a("")
    a("- **VLAN ID et groupes de ports** : à valider avec l'administrateur du vCenter (numéros libres, transport entre hôtes "
      "ESXi) ; les noms et numéros se changent dans `site.json`.")
    a("- **Adresse de VM-EDGE-01 sur le LAN** : IP fixe (ou réservation DHCP) conseillée ; elle est inscrite dans les "
      "certificats, dans Keycloak et sert de bastion.")
    a("- **Sortie Internet limitée à 80 et 443** : suffisant pour apt, Docker Hub, Quay, packagecloud et downloads.apache.org. "
      "L'heure des VM vient de VM-EDGE-01, qui la prend sur Internet (`2.ubuntu.pool.ntp.org`, port 123/udp) : si le LAN de "
      "l'école bloque le NTP, mettre le serveur NTP de l'école dans `/etc/chrony/conf.d/aeger.conf` de VM-EDGE-01.")
    a("- **Docker sur VM-EDGE-01 et VM-COLD-01** : Docker ne doit pas gérer le pare-feu (`\"iptables\": false` dans "
      "`/etc/docker/daemon.json`), sinon il interfère avec le routage et le NAT ; tous les conteneurs AEGER sont en "
      "`network_mode: host` et n'en ont pas besoin. `deploy/aeger.sh prep` le règle.")
    a("- **F16 Airflow → Spark** : le diagramme n'indique pas de port. J'ai pris 7077 (port du maître Spark).")
    a("- **Ping** : ufw laisse passer le ping (ICMP) par défaut, y compris à travers VM-COLD-01 : utile pour le diagnostic.")
    a("- **Sur le schéma** : ajouter une note indiquant que VM-COLD-01 route entre les VLAN et que VM-EDGE-01 est la passerelle.")
    a("- **Services** : voir [Services/README.md](../Services/README.md) et `Services/docker-compose.yml`.")
    a("")
    return "\n".join(D) + "\n"


def main():
    meta = []
    for v in VMS:
        d = SEED_ROOT / v["name"]
        write(d / "user-data", user_data_for(v))
        write(d / "meta-data", meta_data(v))
        write(d / "network-config", netplan(v).split("network:\n", 1)[1].replace("\n  ", "\n").lstrip(" "))
        meta.append(dict(v, seed_dir=str(d), iso=f"{v['name']}.iso"))
    write(JSON_PATH, json.dumps(meta, indent=2, ensure_ascii=False))
    write(DOC_PATH, doc())
    print(f"seeds -> {SEED_ROOT}")
    print(f"doc   -> {DOC_PATH}")
    print(f"adresse publique d'AEGER et bastion : {PUBLIC}")


if __name__ == "__main__":
    main()
