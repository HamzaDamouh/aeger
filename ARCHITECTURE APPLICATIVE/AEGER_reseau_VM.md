# AEGER · Réseau des VM sur VMware vSphere

Ce document décrit le réseau des 11 VM de l'architecture AEGER v2 ([aeger_v2.drawio](aeger_v2.drawio)) sur **VMware vSphere** : les groupes de ports (un par VLAN), le routage entre les VLAN, l'accès à Internet, l'adresse de chaque VM et les règles qui n'autorisent que les flux dessinés dans le diagramme. La configuration de chaque VM est générée par [INFRASTRUCTURE VM/generer.py](../INFRASTRUCTURE%20VM/generer.py) et appliquée automatiquement au premier démarrage ; ce document sert à la comprendre, la vérifier ou la refaire à la main.

Système : **Ubuntu Server 22.04 LTS** sur toutes les VM. Compte : `aeger` / mot de passe `aeger` (console vSphere ; à changer avec `passwd`), SSH par clé uniquement.

> Paramètres propres à votre vSphere (noms des groupes de ports, VLAN ID, adresse de VM-EDGE-01 sur le switch LAN) : [INFRASTRUCTURE VM/site.json](../INFRASTRUCTURE%20VM/site.json). Après modification : `python generer.py`.

## 1. Principe

Les **11 VM du diagramme**, sans VM supplémentaire :

- **Un groupe de ports par VLAN** : `AEGER-VLAN10-DMZ`, `AEGER-VLAN20-SEC`, `AEGER-VLAN30-HOT`, `AEGER-VLAN40-DATA`, `AEGER-VLAN50-COLD`. Chaque VM est branchée sur le groupe de ports de son VLAN : elle ne voit pas les autres VLAN.
- **VM-EDGE-01 est la seule VM branchée sur le switch `LAN`** (réseau de l'école et Internet). Sa carte `ext` est l'**adresse publique d'AEGER** (<IP-EDGE>) : elle n'accepte que 443, 8443 et 8883 (Tier 1) et le SSH d'administration. VM-EDGE-01 sert aussi de **passerelle** aux autres VM : sortie Internet par NAT (ports 80 et 443 seulement : paquets Ubuntu, images Docker), **DNS**, **heure (NTP)** et **bastion SSH**.
- **VM-COLD-01 (Airflow) porte le routage entre les VLAN** : une carte dans chaque VLAN, avec l'adresse de passerelle `10.0.X.1` ; c'est le seul passage d'un VLAN à l'autre, et son pare-feu n'autorise que les flux du diagramme. Elle a été choisie car **aucun flux n'arrive sur elle** (aucun port ouvert) et elle ne garde aucune donnée sensible.
- **Les autres VM n'ont qu'une carte** (dans leur VLAN) et une seule route : `default via 10.0.X.1` (VM-COLD-01). Elles ne sont joignables ni depuis le LAN, ni depuis Internet.
- **Le pare-feu ufw de chaque VM reste en place** : deuxième barrière, et seule barrière entre deux VM d'un même VLAN.

> **Pas d'étiquettes 802.1Q dans les VM.** Les VLAN sont faits par vSphere : chaque groupe de ports a son VLAN ID (le vSwitch étiquette lui-même les trames), ou bien chaque VLAN a son propre vSwitch sans carte physique.

> **Concentration des rôles (à connaître).** VM-EDGE-01, exposée au Tier 1, est aussi la passerelle Internet et le bastion ; VM-COLD-01 voit passer tous les échanges entre VLAN. Le mTLS entre services et le pare-feu de chaque VM protègent toujours les services, mais une VM routeur dédiée serait plus sûre.

```text
Postes du LAN = TIER 1 (bracelets simulés, médecins, analystes)   +   Internet
   │
═══╪══════ groupe de ports « LAN » ═════════════  (seule VM branchée : VM-EDGE-01)
   │  443, 8443, 8883 (Tier 1) · 22 (bastion SSH)
   └── VM-EDGE-01  carte ext : <IP-EDGE>   ·   NGINX + passerelle : NAT Internet, DNS, NTP, bastion

groupe de ports VM branchées                                   réseau           carte de VM-COLD-01
AEGER-VLAN10-DMZ VM-EDGE-01                                     10.0.10.0/24 ── lan10 10.0.10.1  ┐
AEGER-VLAN20-SEC VM-SEC-01                                      10.0.20.0/24 ── lan20 10.0.20.1  │
AEGER-VLAN30-HOT VM-HOT-01, VM-HOT-02, VM-HOT-03                10.0.30.0/24 ── lan30 10.0.30.1  ├─ VM-COLD-01 (Airflow + routeur)
AEGER-VLAN40-DATA VM-KAFKA-01, VM-DB-01                          10.0.40.0/24 ── lan40 10.0.40.1  │  seul passage entre VLAN
AEGER-VLAN50-COLD VM-COLD-02, VM-COLD-03, VM-COLD-04             10.0.50.0/24 ── lan50 10.0.50.1  ┘  (10.0.50.11 + 10.0.50.1)
```

## 2. Plan d'adressage

### Réseaux

| Réseau | Zone du diagramme | Groupe de ports | VLAN ID proposé | Adresses | Passerelle |
|---|---|---|---|---|---|
| VLAN 10 DMZ | Tier 2 | `AEGER-VLAN10-DMZ` | 10 | 10.0.10.0/24 | 10.0.10.1 (VM-COLD-01) |
| VLAN 20 SÉCURITÉ | Sécurité | `AEGER-VLAN20-SEC` | 20 | 10.0.20.0/24 | 10.0.20.1 (VM-COLD-01) |
| VLAN 30 TEMPS RÉEL (hot) | Tier 3 | `AEGER-VLAN30-HOT` | 30 | 10.0.30.0/24 | 10.0.30.1 (VM-COLD-01) |
| VLAN 40 DONNÉES | Tier 4 | `AEGER-VLAN40-DATA` | 40 | 10.0.40.0/24 | 10.0.40.1 (VM-COLD-01) |
| VLAN 50 ANALYTIQUE (cold) | Tier 5 | `AEGER-VLAN50-COLD` | 50 | 10.0.50.0/24 | 10.0.50.1 (VM-COLD-01) |
| LAN (réseau de l'école) | Tier 1 + Internet | `LAN` | (existant) | celui de l'école | celle de l'école |

Pour les VM : **passerelle** `10.0.X.1` (VM-COLD-01), **DNS et heure** `10.0.10.11` (VM-EDGE-01).

### VM et cartes réseau

Chaque carte a une **adresse MAC fixe** (vSphere : *Adresse MAC → Manuel*). Ubuntu reconnaît la carte grâce à elle et lui donne un nom clair (`lan30` = carte du VLAN 30, `ext` = carte du switch LAN).

| VM | Rôle | Cartes (nom → groupe de ports · MAC) | Adresse(s) IP | vCPU | RAM | Disque |
|---|---|---|---|---|---|---|
| **VM-EDGE-01** | NGINX + passerelle (Internet, DNS, NTP, bastion SSH) | `lan10` → `AEGER-VLAN10-DMZ` · `00:50:56:0a:10:11`<br>`ext` → `LAN` · `00:50:56:0a:ff:11` | `10.0.10.11/24`<br>ext : dhcp | 1 | 768 Mo | 25 Go |
| **VM-SEC-01** | Keycloak + Vault | `lan20` → `AEGER-VLAN20-SEC` · `00:50:56:0a:20:11` | `10.0.20.11/24` | 1 | 1536 Mo | 25 Go |
| **VM-HOT-01** | mosquitto + ingestion | `lan30` → `AEGER-VLAN30-HOT` · `00:50:56:0a:30:11` | `10.0.30.11/24` | 1 | 1024 Mo | 25 Go |
| **VM-HOT-02** | notification + alerting | `lan30` → `AEGER-VLAN30-HOT` · `00:50:56:0a:30:12` | `10.0.30.12/24` | 1 | 1024 Mo | 25 Go |
| **VM-HOT-03** | vitals | `lan30` → `AEGER-VLAN30-HOT` · `00:50:56:0a:30:13` | `10.0.30.13/24` | 1 | 1024 Mo | 25 Go |
| **VM-KAFKA-01** | Apache Kafka | `lan40` → `AEGER-VLAN40-DATA` · `00:50:56:0a:40:11` | `10.0.40.11/24` | 1 | 1280 Mo | 50 Go |
| **VM-DB-01** | PostgreSQL + TimescaleDB | `lan40` → `AEGER-VLAN40-DATA` · `00:50:56:0a:40:12` | `10.0.40.12/24` | 1 | 1024 Mo | 50 Go |
| **VM-COLD-01** | Apache Airflow + routeur entre les VLAN | `lan50` → `AEGER-VLAN50-COLD` · `00:50:56:0a:50:11`<br>`lan10` → `AEGER-VLAN10-DMZ` · `00:50:56:0a:10:01`<br>`lan20` → `AEGER-VLAN20-SEC` · `00:50:56:0a:20:01`<br>`lan30` → `AEGER-VLAN30-HOT` · `00:50:56:0a:30:01`<br>`lan40` → `AEGER-VLAN40-DATA` · `00:50:56:0a:40:01` | `10.0.50.11/24`<br>passerelles : 10.0.10.1, 10.0.20.1, 10.0.30.1, 10.0.40.1, 10.0.50.1 | 1 | 1536 Mo | 25 Go |
| **VM-COLD-02** | Data Lake (SeaweedFS) | `lan50` → `AEGER-VLAN50-COLD` · `00:50:56:0a:50:12` | `10.0.50.12/24` | 1 | 768 Mo | 60 Go |
| **VM-COLD-03** | Apache Spark | `lan50` → `AEGER-VLAN50-COLD` · `00:50:56:0a:50:13` | `10.0.50.13/24` | 1 | 2560 Mo | 25 Go |
| **VM-COLD-04** | Grafana | `lan50` → `AEGER-VLAN50-COLD` · `00:50:56:0a:50:14` | `10.0.50.14/24` | 1 | 768 Mo | 25 Go |

Les 11 VM demandent 13 Go de RAM au total. Disques en *thin provisioning* conseillés.

## 3. Flux autorisés

### Flux de l'application (repris du diagramme)

| N° | Source | Destination | Port TCP | Passe par VM-COLD-01 | Flèche du diagramme |
|---|---|---|---|---|---|
| F01 | Tier 1 (LAN) | VM-EDGE-01 (carte ext) | 8883 | non (LAN) | [1] MQTT sur TLS · mTLS |
| F02 | Tier 1 (LAN) | VM-EDGE-01 (carte ext) | 8443 | non (LAN) | HTTPS · mTLS |
| F03 | Tier 1 (LAN) | VM-EDGE-01 (carte ext) | 443 | non (LAN) | [7] HTTPS + WebSocket · HTTPS TLS + OIDC |
| F04 | VM-EDGE-01 (10.0.10.11) | VM-HOT-01 (10.0.30.11) | 8883 | oui | [2] relais TCP 8883 |
| F05 | VM-EDGE-01 (10.0.10.11) | VM-HOT-01 (10.0.30.11) | 8000 | oui | HTTPS 8000 · mTLS |
| F06 | VM-EDGE-01 (10.0.10.11) | VM-HOT-02 (10.0.30.12) | 8000 | oui | [8] WebSocket relayé · 8000 |
| F07 | VM-EDGE-01 (10.0.10.11) | VM-HOT-03 (10.0.30.13) | 8000 | oui | historique patient · HTTPS 8000 |
| F08 | VM-EDGE-01 (10.0.10.11) | VM-COLD-04 (10.0.50.14) | 3000 | oui | /grafana · HTTPS 3000 |
| F09 | VM-EDGE-01 (10.0.10.11) | VM-SEC-01 (10.0.20.11) | 8443 | oui | /auth · connexion OIDC · HTTPS 8443 |
| F10 | VM-EDGE-01 (10.0.10.11) | VM-SEC-01 (10.0.20.11) | 8200 | oui | 🔑 8200 |
| F11 | VM-HOT-01 (10.0.30.11) | VM-KAFKA-01 (10.0.40.11) | 9093 | oui | [4] écrit dans vitals.raw · 9093 |
| F12 | VM-HOT-02 (10.0.30.12) | VM-KAFKA-01 (10.0.40.11) | 9093 | oui | [5] lit vitals.raw · 9093 |
| F13 | VM-HOT-03 (10.0.30.13) | VM-KAFKA-01 (10.0.40.11) | 9093 | oui | lit vitals.raw · 9093 |
| F14 | VM-HOT-03 (10.0.30.13) | VM-DB-01 (10.0.40.12) | 5432 | oui | SQL · TLS 5432 |
| F15 | VM-SEC-01 (10.0.20.11) | VM-DB-01 (10.0.40.12) | 5432 | oui | Keycloak → PostgreSQL · 5432 |
| F16 | VM-COLD-01 (10.0.50.11) | VM-COLD-03 (10.0.50.13) | 7077 | non (même VLAN) | spark-submit (port non indiqué, 7077 supposé) |
| F17 | VM-COLD-03 (10.0.50.13) | VM-KAFKA-01 (10.0.40.11) | 9093 | oui | lit vitals.raw · 1× / jour · 9093 |
| F18 | VM-COLD-03 (10.0.50.13) | VM-COLD-02 (10.0.50.12) | 8333 | non (même VLAN) | API S3 · TLS 8333 |
| F19 | VM-COLD-03 (10.0.50.13) | VM-DB-01 (10.0.40.12) | 5432 | oui | écrit les KPI · TLS 5432 |
| F20 | VM-COLD-04 (10.0.50.14) | VM-DB-01 (10.0.40.12) | 5432 | oui | lit les KPI · TLS 5432 |
| F21 | VM-COLD-04 (10.0.50.14) | VM-SEC-01 (10.0.20.11) | 8443 | oui | Grafana → Keycloak · 8443 |
| F22 | chaque VM des tiers 3 à 5 | VM-SEC-01 (10.0.20.11) | 8200 | oui | 🔑 Vault Agent → Vault · 8200 |

Les flèches [3] (ingestion → mosquitto) et [6] (alerting → notification) restent à l'intérieur d'une même VM. VM-COLD-01 joint VM-SEC-01 (F22) directement, avec son adresse du VLAN 20 (`10.0.20.1`).

### Flux d'infrastructure (pas sur le schéma)

| N° | Source | Destination | Port | Usage |
|---|---|---|---|---|
| I1 | chaque VM | Internet, via VM-COLD-01 puis le NAT de VM-EDGE-01 | 80, 443 | mises à jour (apt) et images Docker |
| I2 | chaque VM | VM-EDGE-01 (10.0.10.11) | 53 (DNS), 123/udp (NTP) | noms de domaine et heure |
| I3 | poste d'administration (LAN) | VM-EDGE-01 (carte ext) | 22 | SSH d'administration (bastion, clé uniquement) |
| I4 | VM-EDGE-01 (10.0.10.11) | chaque VM | 22 | SSH d'administration relayé par le bastion |

Tous les autres flux sont interdits, y compris depuis le LAN vers les VLAN.

## 4. Mise en place dans vSphere

### 4.1 Les groupes de ports

Le groupe de ports `LAN` existe déjà. Créer les 5 autres (*Réseau → vSwitch distribué ou standard → Nouveau groupe de ports*) :

| Groupe de ports | VLAN ID | VM branchées |
|---|---|---|
| `AEGER-VLAN10-DMZ` | 10 | VM-COLD-01, VM-EDGE-01 |
| `AEGER-VLAN20-SEC` | 20 | VM-COLD-01, VM-SEC-01 |
| `AEGER-VLAN30-HOT` | 30 | VM-COLD-01, VM-HOT-01, VM-HOT-02, VM-HOT-03 |
| `AEGER-VLAN40-DATA` | 40 | VM-COLD-01, VM-KAFKA-01, VM-DB-01 |
| `AEGER-VLAN50-COLD` | 50 | VM-COLD-01, VM-COLD-02, VM-COLD-03, VM-COLD-04 |

- **Avec VLAN ID** (cas habituel, vSwitch distribué) : les VLAN ID doivent être libres et transportés entre les hôtes ESXi si les VM sont réparties sur plusieurs hôtes. **À valider avec l'administrateur du vCenter**, qui peut imposer d'autres numéros : il suffit de les reporter dans `site.json`.
- **Sans VLAN ID possible** : créer un vSwitch standard **sans carte physique** par VLAN, avec un seul groupe de ports chacun ; toutes les VM doivent alors être sur le même hôte ESXi.
- Sécurité des groupes de ports : mode promiscuité, modifications d'adresse MAC et transmissions forgées sur **Rejeter** (valeurs par défaut) ; le routage n'en a pas besoin.

### 4.2 L'image Ubuntu Server 22.04 LTS

1. *Hôtes et clusters → clic droit → Déployer un modèle OVF* depuis l'URL `https://cloud-images.ubuntu.com/releases/jammy/release/ubuntu-22.04-server-cloudimg-amd64.ova` (image cloud officielle, avec cloud-init et les VMware Tools).
2. Nom : `ubuntu-22.04-cloud`. Laisser les propriétés de l'assistant vides. **Ne pas démarrer cette VM**.
3. *Clic droit → Modèle → Convertir en modèle.*

### 4.3 Les CD de configuration (cloud-init)

Sur le PC (PowerShell), dans `INFRASTRUCTURE VM` :

```powershell
python generer.py     # configuration de chaque VM -> _seed\<VM>\ (+ ce document)
.\creer-iso.ps1       # un CD par VM -> _seed\<VM>.iso
```

Déposer les 11 fichiers `.iso` sur un datastore, dans un dossier `aeger-seed` (*Stockage → datastore → Fichiers → Charger des fichiers*).

### 4.4 Créer les 11 VM

Pour chaque VM : *clic droit sur le modèle `ubuntu-22.04-cloud` → Nouvelle VM à partir de ce modèle* :

1. Nom de la VM (tableau de la section 2), dossier, hôte ou cluster, datastore (*thin provisioning*).
2. **Ne pas** cocher *Personnaliser le système d'exploitation* ; cocher **Personnaliser le matériel** :
   - CPU, mémoire et taille du disque du tableau de la section 2 ;
   - **cartes réseau** : supprimer celle du modèle, puis ajouter les cartes du tableau, type **VMXNET3**, chacune sur son groupe de ports, avec **Adresse MAC : Manuel** = la MAC du tableau ;
   - **Lecteur CD/DVD** : *Fichier ISO de la banque de données* → `aeger-seed/<NOM-DE-LA-VM>.iso`, **Connecter à la mise sous tension** coché.
3. Terminer, sans démarrer.

Ou en une commande avec PowerCLI : [INFRASTRUCTURE VM/creer-vms-vsphere.ps1](../INFRASTRUCTURE%20VM/creer-vms-vsphere.ps1).

### 4.5 Premier démarrage

1. **Démarrer VM-EDGE-01 en premier** et attendre environ 2 minutes : elle installe son DNS et son serveur NTP (depuis le LAN), puis devient la passerelle des autres VM. Son adresse sur le LAN s'affiche dans vSphere (*Résumé → Adresses IP*) : c'est l'**adresse publique d'AEGER** et le bastion SSH. Une IP fixe (ou une réservation DHCP) est fortement conseillée.
2. **Démarrer VM-COLD-01** (le routeur entre les VLAN).
3. Démarrer les autres VM. Chacune se configure seule au premier démarrage : nom, compte `aeger`, réseau, routes, pare-feu, heure. La console vSphere affiche `### AEGER CONFIG` puis `### AEGER FIN`.

Les 11 VM demandent 13 Go de RAM. **Toujours démarrer VM-EDGE-01 puis VM-COLD-01 en premier.**

## 5. Configuration commune

Résolution des noms : le même fichier `/etc/hosts` sur toutes les VM (VM-EDGE-01 sert aussi ces noms en DNS).

```bash
sudo tee /etc/hosts > /dev/null <<'EOF'
127.0.0.1 localhost

# AEGER - une ligne par VM (adresse dans son VLAN)
10.0.10.11   vm-edge-01
10.0.20.11   vm-sec-01
10.0.30.11   vm-hot-01
10.0.30.12   vm-hot-02
10.0.30.13   vm-hot-03
10.0.40.11   vm-kafka-01
10.0.40.12   vm-db-01
10.0.50.11   vm-cold-01
10.0.50.12   vm-cold-02
10.0.50.13   vm-cold-03
10.0.50.14   vm-cold-04

::1     localhost ip6-localhost ip6-loopback
ff02::1 ip6-allnodes
ff02::2 ip6-allrouters
EOF
```

Le pare-feu `ufw` est déjà installé sur Ubuntu Server. Sur chaque VM, le SSH n'est accepté que depuis le bastion (VM-EDGE-01, `10.0.10.11`).

## 6. Configuration de chaque VM

Appliquée automatiquement au premier démarrage (cloud-init). Pour la refaire à la main, taper les commandes dans la VM concernée, en commençant par VM-EDGE-01 puis VM-COLD-01.

### 6.1 VM-EDGE-01 · VLAN 10 DMZ · NGINX + passerelle (Internet, DNS, NTP, bastion)

| Carte | Groupe de ports | MAC | Adresse |
|---|---|---|---|
| `lan10` | `AEGER-VLAN10-DMZ` | `00:50:56:0a:10:11` | `10.0.10.11/24` |
| `ext` | `LAN` | `00:50:56:0a:ff:11` | dhcp (adresse publique) |

Ports ouverts en entrée : 443, 8443 et 8883 sur `ext` (Tier 1, F01 à F03) ; 22 sur `ext` (bastion) ; 53 et 123/udp sur `lan10` pour les VM (DNS et heure).

**1. Réseau**

```bash
sudo tee /etc/netplan/60-aeger.yaml > /dev/null <<'EOF'
# Réseau de VM-EDGE-01 - VLAN 10 DMZ
# Chaque carte est reconnue par son adresse MAC (fixée dans vSphere) et renommée.
network:
  version: 2
  ethernets:
    lan10:   # groupe de ports AEGER-VLAN10-DMZ
      match:
        macaddress: "00:50:56:0a:10:11"
      set-name: lan10
      dhcp4: false
      dhcp6: false
      accept-ra: false
      link-local: []
      addresses:
        - 10.0.10.11/24
      routes:
        - to: 10.0.0.0/16
          via: 10.0.10.1   # VM-COLD-01 (routeur)
    ext:   # groupe de ports LAN : adresse publique d'AEGER
      match:
        macaddress: "00:50:56:0a:ff:11"
      set-name: ext
      dhcp4: true
      dhcp6: false
      accept-ra: false
EOF
sudo chmod 600 /etc/netplan/60-aeger.yaml
sudo netplan apply && sudo reboot      # le reboot applique les noms de cartes
```

**2. DNS et heure pour les VM** (dnsmasq et chrony)

```bash
sudo mkdir -p /etc/dnsmasq.d /etc/chrony/conf.d
sudo tee /etc/dnsmasq.d/aeger.conf > /dev/null <<'EOF'
# DNS des VM AEGER : VM-EDGE-01 répond sur son adresse du VLAN 10 et relaie vers le DNS du LAN
bind-dynamic
listen-address=10.0.10.11
no-resolv
server=127.0.0.53
domain-needed
bogus-priv
cache-size=1000
EOF
sudo tee /etc/chrony/conf.d/aeger.conf > /dev/null <<'EOF'
# Heure : VM-EDGE-01 se synchronise sur Internet et sert l'heure aux VM
allow 10.0.0.0/16
local stratum 10
EOF
sudo apt-get update && sudo apt-get install -y dnsmasq chrony
sudo systemctl restart dnsmasq chrony
```

**3. Sortie Internet des VM (NAT)**

```bash
echo 'net.ipv4.ip_forward=1' | sudo tee /etc/sysctl.d/90-aeger-routage.conf
sudo sysctl -p /etc/sysctl.d/90-aeger-routage.conf
sudo sed -i 's|^#net/ipv4/ip_forward=1|net/ipv4/ip_forward=1|' /etc/ufw/sysctl.conf
sudo tee /etc/ufw/aeger-nat.rules > /dev/null <<'EOF'
# AEGER-NAT : les VM sortent sur Internet avec l'adresse de VM-EDGE-01 (carte ext)
*nat
:POSTROUTING ACCEPT [0:0]
-A POSTROUTING -s 10.0.0.0/16 -o ext -j MASQUERADE
COMMIT
EOF
```

**4. Pare-feu** (`ufw route` filtre ce qui traverse VM-EDGE-01 : seulement la sortie Internet 80/443 des VM)

```bash
sudo ufw --force reset
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw default deny routed
sudo ufw allow in on ext proto tcp from any to any port 22 comment 'ADMIN ssh (bastion), cle uniquement'
sudo ufw allow in on ext proto tcp to any port 8883 comment 'F01 Tier 1: Bracelets haut risque -> NGINX'
sudo ufw allow in on ext proto tcp to any port 8443 comment 'F02 Tier 1: Bracelets standards -> NGINX'
sudo ufw allow in on ext proto tcp to any port 443 comment 'F03 Tier 1: Medecin et analyste -> NGINX'
sudo ufw allow in on lan10 from 10.0.0.0/16 to 10.0.10.11 port 53 comment 'I2 DNS pour les VM'
sudo ufw allow in on lan10 proto udp from 10.0.0.0/16 to 10.0.10.11 port 123 comment 'I2 NTP pour les VM'
sudo cp -n /etc/ufw/before.rules /etc/ufw/before.rules.orig
sudo sh -c 'cat /etc/ufw/aeger-nat.rules /etc/ufw/before.rules.orig > /etc/ufw/before.rules'
sudo ufw route allow in on lan10 out on ext proto tcp from 10.0.0.0/16 to any port 80,443 comment 'I1 Internet des VM (apt, Docker)'
sudo ufw --force enable
```

**5. Vérifier**

```bash
ip -br addr
sudo ufw status numbered
sudo iptables -t nat -L POSTROUTING -n   # MASQUERADE de 10.0.0.0/16 vers ext
aeger-test-flux
```

### 6.2 VM-COLD-01 · VLAN 50 ANALYTIQUE · Airflow + routeur entre les VLAN

| Carte | Groupe de ports | MAC | Adresse |
|---|---|---|---|
| `lan50` | `AEGER-VLAN50-COLD` | `00:50:56:0a:50:11` | `10.0.50.11/24` + `10.0.50.1/24` (passerelle) |
| `lan10` | `AEGER-VLAN10-DMZ` | `00:50:56:0a:10:01` | `10.0.10.1/24` (passerelle) |
| `lan20` | `AEGER-VLAN20-SEC` | `00:50:56:0a:20:01` | `10.0.20.1/24` (passerelle) |
| `lan30` | `AEGER-VLAN30-HOT` | `00:50:56:0a:30:01` | `10.0.30.1/24` (passerelle) |
| `lan40` | `AEGER-VLAN40-DATA` | `00:50:56:0a:40:01` | `10.0.40.1/24` (passerelle) |

Aucun port ouvert en entrée, sauf 22 depuis le bastion. `ufw route` filtre tout ce qui **traverse** VM-COLD-01 : chaque règle précise la carte d'entrée et de sortie, la source, la destination et le port.

**1. Réseau**

```bash
sudo tee /etc/netplan/60-aeger.yaml > /dev/null <<'EOF'
# Réseau de VM-COLD-01 - VLAN 50 ANALYTIQUE
# Chaque carte est reconnue par son adresse MAC (fixée dans vSphere) et renommée.
network:
  version: 2
  ethernets:
    lan50:   # groupe de ports AEGER-VLAN50-COLD
      match:
        macaddress: "00:50:56:0a:50:11"
      set-name: lan50
      dhcp4: false
      dhcp6: false
      accept-ra: false
      link-local: []
      addresses:
        - 10.0.50.11/24
        - 10.0.50.1/24   # passerelle du VLAN 50
      nameservers:
        addresses: [10.0.10.11]
    lan10:   # groupe de ports AEGER-VLAN10-DMZ
      match:
        macaddress: "00:50:56:0a:10:01"
      set-name: lan10
      dhcp4: false
      dhcp6: false
      accept-ra: false
      link-local: []
      addresses:
        - 10.0.10.1/24   # passerelle du VLAN 10
      routes:
        - to: default
          via: 10.0.10.11   # Internet par VM-EDGE-01 (NAT)
    lan20:   # groupe de ports AEGER-VLAN20-SEC
      match:
        macaddress: "00:50:56:0a:20:01"
      set-name: lan20
      dhcp4: false
      dhcp6: false
      accept-ra: false
      link-local: []
      addresses:
        - 10.0.20.1/24   # passerelle du VLAN 20
    lan30:   # groupe de ports AEGER-VLAN30-HOT
      match:
        macaddress: "00:50:56:0a:30:01"
      set-name: lan30
      dhcp4: false
      dhcp6: false
      accept-ra: false
      link-local: []
      addresses:
        - 10.0.30.1/24   # passerelle du VLAN 30
    lan40:   # groupe de ports AEGER-VLAN40-DATA
      match:
        macaddress: "00:50:56:0a:40:01"
      set-name: lan40
      dhcp4: false
      dhcp6: false
      accept-ra: false
      link-local: []
      addresses:
        - 10.0.40.1/24   # passerelle du VLAN 40
EOF
sudo chmod 600 /etc/netplan/60-aeger.yaml
sudo netplan apply && sudo reboot      # le reboot applique les noms de cartes
```

**2. Routage, heure et pare-feu**

```bash
echo 'net.ipv4.ip_forward=1' | sudo tee /etc/sysctl.d/90-aeger-routage.conf
sudo sysctl -p /etc/sysctl.d/90-aeger-routage.conf
sudo sed -i 's|^#net/ipv4/ip_forward=1|net/ipv4/ip_forward=1|' /etc/ufw/sysctl.conf
sudo mkdir -p /etc/systemd/timesyncd.conf.d
printf '[Time]\nNTP=10.0.10.11\nFallbackNTP=\n' | sudo tee /etc/systemd/timesyncd.conf.d/aeger.conf
sudo systemctl restart systemd-timesyncd
sudo ufw --force reset
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw default deny routed
sudo ufw allow in on lan10 proto tcp from 10.0.10.11 to any port 22 comment 'ADMIN ssh depuis le bastion (VM-EDGE-01)'
sudo ufw route allow in on lan20 out on lan10 proto tcp from 10.0.20.0/24 to any port 80,443 comment 'I1 Internet du VLAN 20 via VM-EDGE-01'
sudo ufw route allow in on lan20 out on lan10 from 10.0.20.0/24 to 10.0.10.11 port 53 comment 'I2 DNS du VLAN 20'
sudo ufw route allow in on lan20 out on lan10 proto udp from 10.0.20.0/24 to 10.0.10.11 port 123 comment 'I2 NTP du VLAN 20'
sudo ufw route allow in on lan10 out on lan20 proto tcp from 10.0.10.11 to 10.0.20.0/24 port 22 comment 'I4 SSH admin vers le VLAN 20'
sudo ufw route allow in on lan30 out on lan10 proto tcp from 10.0.30.0/24 to any port 80,443 comment 'I1 Internet du VLAN 30 via VM-EDGE-01'
sudo ufw route allow in on lan30 out on lan10 from 10.0.30.0/24 to 10.0.10.11 port 53 comment 'I2 DNS du VLAN 30'
sudo ufw route allow in on lan30 out on lan10 proto udp from 10.0.30.0/24 to 10.0.10.11 port 123 comment 'I2 NTP du VLAN 30'
sudo ufw route allow in on lan10 out on lan30 proto tcp from 10.0.10.11 to 10.0.30.0/24 port 22 comment 'I4 SSH admin vers le VLAN 30'
sudo ufw route allow in on lan40 out on lan10 proto tcp from 10.0.40.0/24 to any port 80,443 comment 'I1 Internet du VLAN 40 via VM-EDGE-01'
sudo ufw route allow in on lan40 out on lan10 from 10.0.40.0/24 to 10.0.10.11 port 53 comment 'I2 DNS du VLAN 40'
sudo ufw route allow in on lan40 out on lan10 proto udp from 10.0.40.0/24 to 10.0.10.11 port 123 comment 'I2 NTP du VLAN 40'
sudo ufw route allow in on lan10 out on lan40 proto tcp from 10.0.10.11 to 10.0.40.0/24 port 22 comment 'I4 SSH admin vers le VLAN 40'
sudo ufw route allow in on lan50 out on lan10 proto tcp from 10.0.50.0/24 to any port 80,443 comment 'I1 Internet du VLAN 50 via VM-EDGE-01'
sudo ufw route allow in on lan50 out on lan10 from 10.0.50.0/24 to 10.0.10.11 port 53 comment 'I2 DNS du VLAN 50'
sudo ufw route allow in on lan50 out on lan10 proto udp from 10.0.50.0/24 to 10.0.10.11 port 123 comment 'I2 NTP du VLAN 50'
sudo ufw route allow in on lan10 out on lan50 proto tcp from 10.0.10.11 to 10.0.50.0/24 port 22 comment 'I4 SSH admin vers le VLAN 50'
sudo ufw route allow in on lan10 out on lan30 proto tcp from 10.0.10.11 to 10.0.30.11 port 8883 comment 'F04 VM-EDGE-01 -> VM-HOT-01: NGINX -> mosquitto'
sudo ufw route allow in on lan10 out on lan30 proto tcp from 10.0.10.11 to 10.0.30.11 port 8000 comment 'F05 VM-EDGE-01 -> VM-HOT-01: NGINX -> ingestion'
sudo ufw route allow in on lan10 out on lan30 proto tcp from 10.0.10.11 to 10.0.30.12 port 8000 comment 'F06 VM-EDGE-01 -> VM-HOT-02: NGINX -> notification'
sudo ufw route allow in on lan10 out on lan30 proto tcp from 10.0.10.11 to 10.0.30.13 port 8000 comment 'F07 VM-EDGE-01 -> VM-HOT-03: NGINX -> vitals'
sudo ufw route allow in on lan10 out on lan50 proto tcp from 10.0.10.11 to 10.0.50.14 port 3000 comment 'F08 VM-EDGE-01 -> VM-COLD-04: NGINX -> Grafana'
sudo ufw route allow in on lan10 out on lan20 proto tcp from 10.0.10.11 to 10.0.20.11 port 8443 comment 'F09 VM-EDGE-01 -> VM-SEC-01: NGINX -> Keycloak'
sudo ufw route allow in on lan10 out on lan20 proto tcp from 10.0.10.11 to 10.0.20.11 port 8200 comment 'F10 VM-EDGE-01 -> VM-SEC-01: NGINX -> Vault'
sudo ufw route allow in on lan30 out on lan40 proto tcp from 10.0.30.11 to 10.0.40.11 port 9093 comment 'F11 VM-HOT-01 -> VM-KAFKA-01: ingestion -> Kafka'
sudo ufw route allow in on lan30 out on lan40 proto tcp from 10.0.30.12 to 10.0.40.11 port 9093 comment 'F12 VM-HOT-02 -> VM-KAFKA-01: alerting -> Kafka'
sudo ufw route allow in on lan30 out on lan40 proto tcp from 10.0.30.13 to 10.0.40.11 port 9093 comment 'F13 VM-HOT-03 -> VM-KAFKA-01: vitals -> Kafka'
sudo ufw route allow in on lan30 out on lan40 proto tcp from 10.0.30.13 to 10.0.40.12 port 5432 comment 'F14 VM-HOT-03 -> VM-DB-01: vitals -> PostgreSQL'
sudo ufw route allow in on lan20 out on lan40 proto tcp from 10.0.20.11 to 10.0.40.12 port 5432 comment 'F15 VM-SEC-01 -> VM-DB-01: Keycloak -> PostgreSQL'
sudo ufw route allow in on lan50 out on lan40 proto tcp from 10.0.50.13 to 10.0.40.11 port 9093 comment 'F17 VM-COLD-03 -> VM-KAFKA-01: Spark -> Kafka'
sudo ufw route allow in on lan50 out on lan40 proto tcp from 10.0.50.13 to 10.0.40.12 port 5432 comment 'F19 VM-COLD-03 -> VM-DB-01: Spark -> PostgreSQL'
sudo ufw route allow in on lan50 out on lan40 proto tcp from 10.0.50.14 to 10.0.40.12 port 5432 comment 'F20 VM-COLD-04 -> VM-DB-01: Grafana -> PostgreSQL'
sudo ufw route allow in on lan50 out on lan20 proto tcp from 10.0.50.14 to 10.0.20.11 port 8443 comment 'F21 VM-COLD-04 -> VM-SEC-01: Grafana -> Keycloak'
sudo ufw route allow in on lan30 out on lan20 proto tcp from 10.0.30.11 to 10.0.20.11 port 8200 comment 'F22 VM-HOT-01 -> VM-SEC-01: Vault Agent -> Vault'
sudo ufw route allow in on lan30 out on lan20 proto tcp from 10.0.30.12 to 10.0.20.11 port 8200 comment 'F22 VM-HOT-02 -> VM-SEC-01: Vault Agent -> Vault'
sudo ufw route allow in on lan30 out on lan20 proto tcp from 10.0.30.13 to 10.0.20.11 port 8200 comment 'F22 VM-HOT-03 -> VM-SEC-01: Vault Agent -> Vault'
sudo ufw route allow in on lan40 out on lan20 proto tcp from 10.0.40.11 to 10.0.20.11 port 8200 comment 'F22 VM-KAFKA-01 -> VM-SEC-01: Vault Agent -> Vault'
sudo ufw route allow in on lan40 out on lan20 proto tcp from 10.0.40.12 to 10.0.20.11 port 8200 comment 'F22 VM-DB-01 -> VM-SEC-01: Vault Agent -> Vault'
sudo ufw route allow in on lan50 out on lan20 proto tcp from 10.0.50.12 to 10.0.20.11 port 8200 comment 'F22 VM-COLD-02 -> VM-SEC-01: Vault Agent -> Vault'
sudo ufw route allow in on lan50 out on lan20 proto tcp from 10.0.50.13 to 10.0.20.11 port 8200 comment 'F22 VM-COLD-03 -> VM-SEC-01: Vault Agent -> Vault'
sudo ufw route allow in on lan50 out on lan20 proto tcp from 10.0.50.14 to 10.0.20.11 port 8200 comment 'F22 VM-COLD-04 -> VM-SEC-01: Vault Agent -> Vault'
sudo ufw --force enable
```

**3. Vérifier**

```bash
ip -br addr                  # lan50 (10.0.50.11 + 10.0.50.1), lan10 à lan40 (10.0.X.1)
sysctl net.ipv4.ip_forward   # doit afficher 1
sudo ufw status numbered     # règles ALLOW FWD
aeger-test-flux
```

Pour voir ce que VM-COLD-01 bloque : `sudo ufw logging on`, puis `sudo tail -f /var/log/ufw.log` (lignes `[UFW BLOCK]`).

### 6.3 VM-SEC-01 · VLAN 20 SÉCURITÉ · Keycloak + Vault

| Carte | Groupe de ports | MAC | Adresse |
|---|---|---|---|
| `lan20` | `AEGER-VLAN20-SEC` | `00:50:56:0a:20:11` | `10.0.20.11/24` |

Ports ouverts en entrée : 8443 depuis VM-EDGE-01 (F09); 8200 depuis VM-EDGE-01 (F10); 8443 depuis VM-COLD-04 (F21); 8200 depuis VM-HOT-01, VM-HOT-02, VM-HOT-03, VM-KAFKA-01, VM-DB-01, VM-COLD-01, VM-COLD-02, VM-COLD-03, VM-COLD-04 (F22); 22 depuis le bastion.

**1. Réseau et route vers VM-COLD-01**

```bash
sudo tee /etc/netplan/60-aeger.yaml > /dev/null <<'EOF'
# Réseau de VM-SEC-01 - VLAN 20 SECURITE
# Chaque carte est reconnue par son adresse MAC (fixée dans vSphere) et renommée.
network:
  version: 2
  ethernets:
    lan20:   # groupe de ports AEGER-VLAN20-SEC
      match:
        macaddress: "00:50:56:0a:20:11"
      set-name: lan20
      dhcp4: false
      dhcp6: false
      accept-ra: false
      link-local: []
      addresses:
        - 10.0.20.11/24
      routes:
        - to: default
          via: 10.0.20.1   # VM-COLD-01 : autres VLAN et Internet
      nameservers:
        addresses: [10.0.10.11]   # DNS servi par VM-EDGE-01
EOF
sudo chmod 600 /etc/netplan/60-aeger.yaml
sudo netplan apply && sudo reboot      # le reboot applique les noms de cartes
```

**2. Heure et pare-feu**

```bash
sudo mkdir -p /etc/systemd/timesyncd.conf.d
printf '[Time]\nNTP=10.0.10.11\nFallbackNTP=\n' | sudo tee /etc/systemd/timesyncd.conf.d/aeger.conf
sudo systemctl restart systemd-timesyncd
sudo ufw --force reset
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw default deny routed
sudo ufw allow in on lan20 proto tcp from 10.0.10.11 to 10.0.20.11 port 22 comment 'ADMIN ssh depuis le bastion (VM-EDGE-01)'
sudo ufw allow in on lan20 proto tcp from 10.0.10.11 to 10.0.20.11 port 8443 comment 'F09 VM-EDGE-01: NGINX -> Keycloak'
sudo ufw allow in on lan20 proto tcp from 10.0.10.11 to 10.0.20.11 port 8200 comment 'F10 VM-EDGE-01: NGINX -> Vault'
sudo ufw allow in on lan20 proto tcp from 10.0.50.14 to 10.0.20.11 port 8443 comment 'F21 VM-COLD-04: Grafana -> Keycloak'
sudo ufw allow in on lan20 proto tcp from 10.0.30.11 to 10.0.20.11 port 8200 comment 'F22 VM-HOT-01: Vault Agent -> Vault'
sudo ufw allow in on lan20 proto tcp from 10.0.30.12 to 10.0.20.11 port 8200 comment 'F22 VM-HOT-02: Vault Agent -> Vault'
sudo ufw allow in on lan20 proto tcp from 10.0.30.13 to 10.0.20.11 port 8200 comment 'F22 VM-HOT-03: Vault Agent -> Vault'
sudo ufw allow in on lan20 proto tcp from 10.0.40.11 to 10.0.20.11 port 8200 comment 'F22 VM-KAFKA-01: Vault Agent -> Vault'
sudo ufw allow in on lan20 proto tcp from 10.0.40.12 to 10.0.20.11 port 8200 comment 'F22 VM-DB-01: Vault Agent -> Vault'
sudo ufw allow in on lan20 proto tcp from 10.0.20.1 to 10.0.20.11 port 8200 comment 'F22 VM-COLD-01: Vault Agent -> Vault'
sudo ufw allow in on lan20 proto tcp from 10.0.50.12 to 10.0.20.11 port 8200 comment 'F22 VM-COLD-02: Vault Agent -> Vault'
sudo ufw allow in on lan20 proto tcp from 10.0.50.13 to 10.0.20.11 port 8200 comment 'F22 VM-COLD-03: Vault Agent -> Vault'
sudo ufw allow in on lan20 proto tcp from 10.0.50.14 to 10.0.20.11 port 8200 comment 'F22 VM-COLD-04: Vault Agent -> Vault'
sudo ufw --force enable
```

**3. Vérifier**

```bash
ip -br addr; ip route
sudo ufw status numbered
aeger-test-flux     # quand VM-EDGE-01, VM-COLD-01 et les autres VM sont démarrées
```

### 6.4 VM-HOT-01 · VLAN 30 TEMPS RÉEL (hot) · mosquitto + ingestion

| Carte | Groupe de ports | MAC | Adresse |
|---|---|---|---|
| `lan30` | `AEGER-VLAN30-HOT` | `00:50:56:0a:30:11` | `10.0.30.11/24` |

Ports ouverts en entrée : 8883 depuis VM-EDGE-01 (F04); 8000 depuis VM-EDGE-01 (F05); 22 depuis le bastion.

**1. Réseau et route vers VM-COLD-01**

```bash
sudo tee /etc/netplan/60-aeger.yaml > /dev/null <<'EOF'
# Réseau de VM-HOT-01 - VLAN 30 TEMPS REEL
# Chaque carte est reconnue par son adresse MAC (fixée dans vSphere) et renommée.
network:
  version: 2
  ethernets:
    lan30:   # groupe de ports AEGER-VLAN30-HOT
      match:
        macaddress: "00:50:56:0a:30:11"
      set-name: lan30
      dhcp4: false
      dhcp6: false
      accept-ra: false
      link-local: []
      addresses:
        - 10.0.30.11/24
      routes:
        - to: default
          via: 10.0.30.1   # VM-COLD-01 : autres VLAN et Internet
      nameservers:
        addresses: [10.0.10.11]   # DNS servi par VM-EDGE-01
EOF
sudo chmod 600 /etc/netplan/60-aeger.yaml
sudo netplan apply && sudo reboot      # le reboot applique les noms de cartes
```

**2. Heure et pare-feu**

```bash
sudo mkdir -p /etc/systemd/timesyncd.conf.d
printf '[Time]\nNTP=10.0.10.11\nFallbackNTP=\n' | sudo tee /etc/systemd/timesyncd.conf.d/aeger.conf
sudo systemctl restart systemd-timesyncd
sudo ufw --force reset
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw default deny routed
sudo ufw allow in on lan30 proto tcp from 10.0.10.11 to 10.0.30.11 port 22 comment 'ADMIN ssh depuis le bastion (VM-EDGE-01)'
sudo ufw allow in on lan30 proto tcp from 10.0.10.11 to 10.0.30.11 port 8883 comment 'F04 VM-EDGE-01: NGINX -> mosquitto'
sudo ufw allow in on lan30 proto tcp from 10.0.10.11 to 10.0.30.11 port 8000 comment 'F05 VM-EDGE-01: NGINX -> ingestion'
sudo ufw --force enable
```

**3. Vérifier**

```bash
ip -br addr; ip route
sudo ufw status numbered
aeger-test-flux     # quand VM-EDGE-01, VM-COLD-01 et les autres VM sont démarrées
```

### 6.5 VM-HOT-02 · VLAN 30 TEMPS RÉEL (hot) · notification + alerting

| Carte | Groupe de ports | MAC | Adresse |
|---|---|---|---|
| `lan30` | `AEGER-VLAN30-HOT` | `00:50:56:0a:30:12` | `10.0.30.12/24` |

Ports ouverts en entrée : 8000 depuis VM-EDGE-01 (F06); 22 depuis le bastion.

**1. Réseau et route vers VM-COLD-01**

```bash
sudo tee /etc/netplan/60-aeger.yaml > /dev/null <<'EOF'
# Réseau de VM-HOT-02 - VLAN 30 TEMPS REEL
# Chaque carte est reconnue par son adresse MAC (fixée dans vSphere) et renommée.
network:
  version: 2
  ethernets:
    lan30:   # groupe de ports AEGER-VLAN30-HOT
      match:
        macaddress: "00:50:56:0a:30:12"
      set-name: lan30
      dhcp4: false
      dhcp6: false
      accept-ra: false
      link-local: []
      addresses:
        - 10.0.30.12/24
      routes:
        - to: default
          via: 10.0.30.1   # VM-COLD-01 : autres VLAN et Internet
      nameservers:
        addresses: [10.0.10.11]   # DNS servi par VM-EDGE-01
EOF
sudo chmod 600 /etc/netplan/60-aeger.yaml
sudo netplan apply && sudo reboot      # le reboot applique les noms de cartes
```

**2. Heure et pare-feu**

```bash
sudo mkdir -p /etc/systemd/timesyncd.conf.d
printf '[Time]\nNTP=10.0.10.11\nFallbackNTP=\n' | sudo tee /etc/systemd/timesyncd.conf.d/aeger.conf
sudo systemctl restart systemd-timesyncd
sudo ufw --force reset
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw default deny routed
sudo ufw allow in on lan30 proto tcp from 10.0.10.11 to 10.0.30.12 port 22 comment 'ADMIN ssh depuis le bastion (VM-EDGE-01)'
sudo ufw allow in on lan30 proto tcp from 10.0.10.11 to 10.0.30.12 port 8000 comment 'F06 VM-EDGE-01: NGINX -> notification'
sudo ufw --force enable
```

**3. Vérifier**

```bash
ip -br addr; ip route
sudo ufw status numbered
aeger-test-flux     # quand VM-EDGE-01, VM-COLD-01 et les autres VM sont démarrées
```

### 6.6 VM-HOT-03 · VLAN 30 TEMPS RÉEL (hot) · vitals

| Carte | Groupe de ports | MAC | Adresse |
|---|---|---|---|
| `lan30` | `AEGER-VLAN30-HOT` | `00:50:56:0a:30:13` | `10.0.30.13/24` |

Ports ouverts en entrée : 8000 depuis VM-EDGE-01 (F07); 22 depuis le bastion.

**1. Réseau et route vers VM-COLD-01**

```bash
sudo tee /etc/netplan/60-aeger.yaml > /dev/null <<'EOF'
# Réseau de VM-HOT-03 - VLAN 30 TEMPS REEL
# Chaque carte est reconnue par son adresse MAC (fixée dans vSphere) et renommée.
network:
  version: 2
  ethernets:
    lan30:   # groupe de ports AEGER-VLAN30-HOT
      match:
        macaddress: "00:50:56:0a:30:13"
      set-name: lan30
      dhcp4: false
      dhcp6: false
      accept-ra: false
      link-local: []
      addresses:
        - 10.0.30.13/24
      routes:
        - to: default
          via: 10.0.30.1   # VM-COLD-01 : autres VLAN et Internet
      nameservers:
        addresses: [10.0.10.11]   # DNS servi par VM-EDGE-01
EOF
sudo chmod 600 /etc/netplan/60-aeger.yaml
sudo netplan apply && sudo reboot      # le reboot applique les noms de cartes
```

**2. Heure et pare-feu**

```bash
sudo mkdir -p /etc/systemd/timesyncd.conf.d
printf '[Time]\nNTP=10.0.10.11\nFallbackNTP=\n' | sudo tee /etc/systemd/timesyncd.conf.d/aeger.conf
sudo systemctl restart systemd-timesyncd
sudo ufw --force reset
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw default deny routed
sudo ufw allow in on lan30 proto tcp from 10.0.10.11 to 10.0.30.13 port 22 comment 'ADMIN ssh depuis le bastion (VM-EDGE-01)'
sudo ufw allow in on lan30 proto tcp from 10.0.10.11 to 10.0.30.13 port 8000 comment 'F07 VM-EDGE-01: NGINX -> vitals'
sudo ufw --force enable
```

**3. Vérifier**

```bash
ip -br addr; ip route
sudo ufw status numbered
aeger-test-flux     # quand VM-EDGE-01, VM-COLD-01 et les autres VM sont démarrées
```

### 6.7 VM-KAFKA-01 · VLAN 40 DONNÉES · Apache Kafka

| Carte | Groupe de ports | MAC | Adresse |
|---|---|---|---|
| `lan40` | `AEGER-VLAN40-DATA` | `00:50:56:0a:40:11` | `10.0.40.11/24` |

Ports ouverts en entrée : 9093 depuis VM-HOT-01 (F11); 9093 depuis VM-HOT-02 (F12); 9093 depuis VM-HOT-03 (F13); 9093 depuis VM-COLD-03 (F17); 22 depuis le bastion.

**1. Réseau et route vers VM-COLD-01**

```bash
sudo tee /etc/netplan/60-aeger.yaml > /dev/null <<'EOF'
# Réseau de VM-KAFKA-01 - VLAN 40 DONNEES
# Chaque carte est reconnue par son adresse MAC (fixée dans vSphere) et renommée.
network:
  version: 2
  ethernets:
    lan40:   # groupe de ports AEGER-VLAN40-DATA
      match:
        macaddress: "00:50:56:0a:40:11"
      set-name: lan40
      dhcp4: false
      dhcp6: false
      accept-ra: false
      link-local: []
      addresses:
        - 10.0.40.11/24
      routes:
        - to: default
          via: 10.0.40.1   # VM-COLD-01 : autres VLAN et Internet
      nameservers:
        addresses: [10.0.10.11]   # DNS servi par VM-EDGE-01
EOF
sudo chmod 600 /etc/netplan/60-aeger.yaml
sudo netplan apply && sudo reboot      # le reboot applique les noms de cartes
```

**2. Heure et pare-feu**

```bash
sudo mkdir -p /etc/systemd/timesyncd.conf.d
printf '[Time]\nNTP=10.0.10.11\nFallbackNTP=\n' | sudo tee /etc/systemd/timesyncd.conf.d/aeger.conf
sudo systemctl restart systemd-timesyncd
sudo ufw --force reset
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw default deny routed
sudo ufw allow in on lan40 proto tcp from 10.0.10.11 to 10.0.40.11 port 22 comment 'ADMIN ssh depuis le bastion (VM-EDGE-01)'
sudo ufw allow in on lan40 proto tcp from 10.0.30.11 to 10.0.40.11 port 9093 comment 'F11 VM-HOT-01: ingestion -> Kafka'
sudo ufw allow in on lan40 proto tcp from 10.0.30.12 to 10.0.40.11 port 9093 comment 'F12 VM-HOT-02: alerting -> Kafka'
sudo ufw allow in on lan40 proto tcp from 10.0.30.13 to 10.0.40.11 port 9093 comment 'F13 VM-HOT-03: vitals -> Kafka'
sudo ufw allow in on lan40 proto tcp from 10.0.50.13 to 10.0.40.11 port 9093 comment 'F17 VM-COLD-03: Spark -> Kafka'
sudo ufw --force enable
```

**3. Vérifier**

```bash
ip -br addr; ip route
sudo ufw status numbered
aeger-test-flux     # quand VM-EDGE-01, VM-COLD-01 et les autres VM sont démarrées
```

### 6.8 VM-DB-01 · VLAN 40 DONNÉES · PostgreSQL + TimescaleDB

| Carte | Groupe de ports | MAC | Adresse |
|---|---|---|---|
| `lan40` | `AEGER-VLAN40-DATA` | `00:50:56:0a:40:12` | `10.0.40.12/24` |

Ports ouverts en entrée : 5432 depuis VM-HOT-03 (F14); 5432 depuis VM-SEC-01 (F15); 5432 depuis VM-COLD-03 (F19); 5432 depuis VM-COLD-04 (F20); 22 depuis le bastion.

**1. Réseau et route vers VM-COLD-01**

```bash
sudo tee /etc/netplan/60-aeger.yaml > /dev/null <<'EOF'
# Réseau de VM-DB-01 - VLAN 40 DONNEES
# Chaque carte est reconnue par son adresse MAC (fixée dans vSphere) et renommée.
network:
  version: 2
  ethernets:
    lan40:   # groupe de ports AEGER-VLAN40-DATA
      match:
        macaddress: "00:50:56:0a:40:12"
      set-name: lan40
      dhcp4: false
      dhcp6: false
      accept-ra: false
      link-local: []
      addresses:
        - 10.0.40.12/24
      routes:
        - to: default
          via: 10.0.40.1   # VM-COLD-01 : autres VLAN et Internet
      nameservers:
        addresses: [10.0.10.11]   # DNS servi par VM-EDGE-01
EOF
sudo chmod 600 /etc/netplan/60-aeger.yaml
sudo netplan apply && sudo reboot      # le reboot applique les noms de cartes
```

**2. Heure et pare-feu**

```bash
sudo mkdir -p /etc/systemd/timesyncd.conf.d
printf '[Time]\nNTP=10.0.10.11\nFallbackNTP=\n' | sudo tee /etc/systemd/timesyncd.conf.d/aeger.conf
sudo systemctl restart systemd-timesyncd
sudo ufw --force reset
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw default deny routed
sudo ufw allow in on lan40 proto tcp from 10.0.10.11 to 10.0.40.12 port 22 comment 'ADMIN ssh depuis le bastion (VM-EDGE-01)'
sudo ufw allow in on lan40 proto tcp from 10.0.30.13 to 10.0.40.12 port 5432 comment 'F14 VM-HOT-03: vitals -> PostgreSQL'
sudo ufw allow in on lan40 proto tcp from 10.0.20.11 to 10.0.40.12 port 5432 comment 'F15 VM-SEC-01: Keycloak -> PostgreSQL'
sudo ufw allow in on lan40 proto tcp from 10.0.50.13 to 10.0.40.12 port 5432 comment 'F19 VM-COLD-03: Spark -> PostgreSQL'
sudo ufw allow in on lan40 proto tcp from 10.0.50.14 to 10.0.40.12 port 5432 comment 'F20 VM-COLD-04: Grafana -> PostgreSQL'
sudo ufw --force enable
```

**3. Vérifier**

```bash
ip -br addr; ip route
sudo ufw status numbered
aeger-test-flux     # quand VM-EDGE-01, VM-COLD-01 et les autres VM sont démarrées
```

### 6.9 VM-COLD-02 · VLAN 50 ANALYTIQUE (cold) · Data Lake (SeaweedFS)

| Carte | Groupe de ports | MAC | Adresse |
|---|---|---|---|
| `lan50` | `AEGER-VLAN50-COLD` | `00:50:56:0a:50:12` | `10.0.50.12/24` |

Ports ouverts en entrée : 8333 depuis VM-COLD-03 (F18); 22 depuis le bastion.

**1. Réseau et route vers VM-COLD-01**

```bash
sudo tee /etc/netplan/60-aeger.yaml > /dev/null <<'EOF'
# Réseau de VM-COLD-02 - VLAN 50 ANALYTIQUE
# Chaque carte est reconnue par son adresse MAC (fixée dans vSphere) et renommée.
network:
  version: 2
  ethernets:
    lan50:   # groupe de ports AEGER-VLAN50-COLD
      match:
        macaddress: "00:50:56:0a:50:12"
      set-name: lan50
      dhcp4: false
      dhcp6: false
      accept-ra: false
      link-local: []
      addresses:
        - 10.0.50.12/24
      routes:
        - to: default
          via: 10.0.50.1   # VM-COLD-01 : autres VLAN et Internet
      nameservers:
        addresses: [10.0.10.11]   # DNS servi par VM-EDGE-01
EOF
sudo chmod 600 /etc/netplan/60-aeger.yaml
sudo netplan apply && sudo reboot      # le reboot applique les noms de cartes
```

**2. Heure et pare-feu**

```bash
sudo mkdir -p /etc/systemd/timesyncd.conf.d
printf '[Time]\nNTP=10.0.10.11\nFallbackNTP=\n' | sudo tee /etc/systemd/timesyncd.conf.d/aeger.conf
sudo systemctl restart systemd-timesyncd
sudo ufw --force reset
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw default deny routed
sudo ufw allow in on lan50 proto tcp from 10.0.10.11 to 10.0.50.12 port 22 comment 'ADMIN ssh depuis le bastion (VM-EDGE-01)'
sudo ufw allow in on lan50 proto tcp from 10.0.50.13 to 10.0.50.12 port 8333 comment 'F18 VM-COLD-03: Spark -> Data Lake'
sudo ufw --force enable
```

**3. Vérifier**

```bash
ip -br addr; ip route
sudo ufw status numbered
aeger-test-flux     # quand VM-EDGE-01, VM-COLD-01 et les autres VM sont démarrées
```

### 6.10 VM-COLD-03 · VLAN 50 ANALYTIQUE (cold) · Apache Spark

| Carte | Groupe de ports | MAC | Adresse |
|---|---|---|---|
| `lan50` | `AEGER-VLAN50-COLD` | `00:50:56:0a:50:13` | `10.0.50.13/24` |

Ports ouverts en entrée : 7077 depuis VM-COLD-01 (F16); 22 depuis le bastion.

**1. Réseau et route vers VM-COLD-01**

```bash
sudo tee /etc/netplan/60-aeger.yaml > /dev/null <<'EOF'
# Réseau de VM-COLD-03 - VLAN 50 ANALYTIQUE
# Chaque carte est reconnue par son adresse MAC (fixée dans vSphere) et renommée.
network:
  version: 2
  ethernets:
    lan50:   # groupe de ports AEGER-VLAN50-COLD
      match:
        macaddress: "00:50:56:0a:50:13"
      set-name: lan50
      dhcp4: false
      dhcp6: false
      accept-ra: false
      link-local: []
      addresses:
        - 10.0.50.13/24
      routes:
        - to: default
          via: 10.0.50.1   # VM-COLD-01 : autres VLAN et Internet
      nameservers:
        addresses: [10.0.10.11]   # DNS servi par VM-EDGE-01
EOF
sudo chmod 600 /etc/netplan/60-aeger.yaml
sudo netplan apply && sudo reboot      # le reboot applique les noms de cartes
```

**2. Heure et pare-feu**

```bash
sudo mkdir -p /etc/systemd/timesyncd.conf.d
printf '[Time]\nNTP=10.0.10.11\nFallbackNTP=\n' | sudo tee /etc/systemd/timesyncd.conf.d/aeger.conf
sudo systemctl restart systemd-timesyncd
sudo ufw --force reset
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw default deny routed
sudo ufw allow in on lan50 proto tcp from 10.0.10.11 to 10.0.50.13 port 22 comment 'ADMIN ssh depuis le bastion (VM-EDGE-01)'
sudo ufw allow in on lan50 proto tcp from 10.0.50.11 to 10.0.50.13 port 7077 comment 'F16 VM-COLD-01: Airflow -> Spark'
sudo ufw --force enable
```

**3. Vérifier**

```bash
ip -br addr; ip route
sudo ufw status numbered
aeger-test-flux     # quand VM-EDGE-01, VM-COLD-01 et les autres VM sont démarrées
```

### 6.11 VM-COLD-04 · VLAN 50 ANALYTIQUE (cold) · Grafana

| Carte | Groupe de ports | MAC | Adresse |
|---|---|---|---|
| `lan50` | `AEGER-VLAN50-COLD` | `00:50:56:0a:50:14` | `10.0.50.14/24` |

Ports ouverts en entrée : 3000 depuis VM-EDGE-01 (F08); 22 depuis le bastion.

**1. Réseau et route vers VM-COLD-01**

```bash
sudo tee /etc/netplan/60-aeger.yaml > /dev/null <<'EOF'
# Réseau de VM-COLD-04 - VLAN 50 ANALYTIQUE
# Chaque carte est reconnue par son adresse MAC (fixée dans vSphere) et renommée.
network:
  version: 2
  ethernets:
    lan50:   # groupe de ports AEGER-VLAN50-COLD
      match:
        macaddress: "00:50:56:0a:50:14"
      set-name: lan50
      dhcp4: false
      dhcp6: false
      accept-ra: false
      link-local: []
      addresses:
        - 10.0.50.14/24
      routes:
        - to: default
          via: 10.0.50.1   # VM-COLD-01 : autres VLAN et Internet
      nameservers:
        addresses: [10.0.10.11]   # DNS servi par VM-EDGE-01
EOF
sudo chmod 600 /etc/netplan/60-aeger.yaml
sudo netplan apply && sudo reboot      # le reboot applique les noms de cartes
```

**2. Heure et pare-feu**

```bash
sudo mkdir -p /etc/systemd/timesyncd.conf.d
printf '[Time]\nNTP=10.0.10.11\nFallbackNTP=\n' | sudo tee /etc/systemd/timesyncd.conf.d/aeger.conf
sudo systemctl restart systemd-timesyncd
sudo ufw --force reset
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw default deny routed
sudo ufw allow in on lan50 proto tcp from 10.0.10.11 to 10.0.50.14 port 22 comment 'ADMIN ssh depuis le bastion (VM-EDGE-01)'
sudo ufw allow in on lan50 proto tcp from 10.0.10.11 to 10.0.50.14 port 3000 comment 'F08 VM-EDGE-01: NGINX -> Grafana'
sudo ufw --force enable
```

**3. Vérifier**

```bash
ip -br addr; ip route
sudo ufw status numbered
aeger-test-flux     # quand VM-EDGE-01, VM-COLD-01 et les autres VM sont démarrées
```

## 7. Tester

On teste si un paquet **arrive** jusqu'au port (aucun service n'est encore installé) :

```bash
timeout 3 bash -c '</dev/tcp/10.0.40.11/9093'
```

| Résultat | Signification |
|---|---|
| `Connection refused` | le paquet arrive, mais aucun service n'écoute encore → **flux autorisé** |
| rien pendant 3 s | bloqué par VM-COLD-01, ou par le pare-feu de la VM d'en face |
| `No route to host` | la VM d'en face ne répond pas (éteinte ?) ou VM-COLD-01 est éteinte |

Chaque VM contient le script `aeger-test-flux` : il teste tous les flux autorisés de la VM, quelques flux interdits, et la sortie Internet, et affiche `OK` ou `ERREUR` pour chacun.

Depuis un poste du LAN (Tier 1), seuls les ports 443, 8443, 8883 et 22 (bastion) de VM-EDGE-01 doivent répondre :

```powershell
function Test-Port([string]$Ip, [int]$Port) {
  $c = New-Object System.Net.Sockets.TcpClient
  $t = $c.ConnectAsync($Ip, $Port)
  try { $fini = $t.Wait(3000) } catch { $fini = $true }
  $c.Dispose()
  if (-not $fini) { $r = 'BLOQUE (pas de reponse)' }
  elseif ($t.Status -eq 'RanToCompletion') { $r = 'ACCESSIBLE (service actif)' }
  elseif ($t.Exception.InnerException.SocketErrorCode -eq 'ConnectionRefused') { $r = 'ACCESSIBLE (aucun service)' }
  else { $r = 'BLOQUE (' + $t.Exception.InnerException.SocketErrorCode + ')' }
  "{0}:{1}  {2}" -f $Ip, $Port, $r
}
443, 8443, 8883, 22, 5432 | ForEach-Object { Test-Port <IP-EDGE> $_ }   # 5432 doit être BLOQUE
```

### Résultats obtenus le 18/11/2026

Ce réseau a été validé le 18/11/2026 sur une maquette VirtualBox qui reproduit le réseau vSphere : un switch LAN simulé où seule VM-EDGE-01 est branchée, les groupes de ports, les adresses MAC fixes, et 5 VM (VM-EDGE-01, VM-COLD-01, VM-SEC-01, VM-HOT-01, VM-COLD-03) démarrées avec la configuration générée par generer.py. Les flux vers les VM absentes de la maquette n'ont pas pu être testés ici ; l'ensemble des 25 flux du diagramme avait été vérifié auparavant sur les 11 VM avec le même plan d'adressage et les mêmes règles de pare-feu.

| Vérification (maquette du réseau vSphere) | Résultat |
|---|---|
| Cartes reconnues par leur MAC et renommées (ext, lan10 à lan50), adresses et routes du tableau | OK sur les 5 VM |
| VM-COLD-01 : 10.0.50.11 + passerelle 10.0.50.1, passerelles 10.0.10.1 à 10.0.40.1, routage activé | OK |
| Flux entre VLAN à travers VM-COLD-01 : EDGE → HOT-01 (F04, F05), EDGE → SEC (F09, F10), HOT-01, SEC, COLD-03 → Vault (F22) | OK |
| VM-COLD-01 → Vault (F22) depuis son adresse du VLAN 20, VM-COLD-01 → COLD-03 (F16) | OK |
| Flux interdits (vers Kafka, PostgreSQL, SSH entre VM, autres ports) | tous bloqués |
| Internet des VM internes : via VM-COLD-01 puis le NAT de VM-EDGE-01 (port 80), port 25 bloqué | OK sur COLD-01, SEC, HOT-01, COLD-03 |
| DNS et heure servis par VM-EDGE-01 (10.0.10.11) ; VM-EDGE-01 à l'heure d'Internet | OK |
| Bastion SSH : PC → VM-EDGE-01 (carte ext) → chacune des 4 autres VM | OK |
| Tier 1 : port 443 de VM-EDGE-01 joignable depuis le LAN | OK |

## 8. Administration

Le SSH passe par le **bastion** (VM-EDGE-01, carte `ext`), avec la clé `aeger_ed25519` (la même pour toutes les VM). Le plus simple : un fichier `%USERPROFILE%\.ssh\config` qui indique la clé pour le bastion **et** pour les VM (avec `ssh -J` seul, l'option `-i` ne s'appliquerait pas au premier saut) :

```text
Host aeger-bastion
    HostName <IP-EDGE>
    User aeger
    IdentityFile ~/.ssh/aeger_ed25519
Host vm-*
    User aeger
    IdentityFile ~/.ssh/aeger_ed25519
    ProxyJump aeger-bastion
```

puis `ssh aeger-bastion` ou `ssh vm-hot-01` (les noms `vm-*` sont résolus par le DNS de VM-EDGE-01). Sans fichier de configuration, en une ligne :

```powershell
ssh -i ~/.ssh/aeger_ed25519 -o "ProxyCommand=ssh -i ~/.ssh/aeger_ed25519 -W %h:%p aeger@<IP-EDGE>" aeger@10.0.30.11
```

Sans réseau, il reste toujours la **console vSphere** (clavier français configuré) avec `aeger` / `aeger`.

## 9. Hypothèses et points à vérifier

- **VLAN ID et groupes de ports** : à valider avec l'administrateur du vCenter (numéros libres, transport entre hôtes ESXi) ; les noms et numéros se changent dans `site.json`.
- **Adresse de VM-EDGE-01 sur le LAN** : IP fixe (ou réservation DHCP) conseillée ; elle est inscrite dans les certificats, dans Keycloak et sert de bastion.
- **Sortie Internet limitée à 80 et 443** : suffisant pour apt, Docker Hub, Quay, packagecloud et downloads.apache.org. L'heure des VM vient de VM-EDGE-01, qui la prend sur Internet (`2.ubuntu.pool.ntp.org`, port 123/udp) : si le LAN de l'école bloque le NTP, mettre le serveur NTP de l'école dans `/etc/chrony/conf.d/aeger.conf` de VM-EDGE-01.
- **Docker sur VM-EDGE-01 et VM-COLD-01** : Docker ne doit pas gérer le pare-feu (`"iptables": false` dans `/etc/docker/daemon.json`), sinon il interfère avec le routage et le NAT ; tous les conteneurs AEGER sont en `network_mode: host` et n'en ont pas besoin. `deploy/aeger.sh prep` le règle.
- **F16 Airflow → Spark** : le diagramme n'indique pas de port. J'ai pris 7077 (port du maître Spark).
- **Ping** : ufw laisse passer le ping (ICMP) par défaut, y compris à travers VM-COLD-01 : utile pour le diagnostic.
- **Sur le schéma** : ajouter une note indiquant que VM-COLD-01 route entre les VLAN et que VM-EDGE-01 est la passerelle.
- **Services** : voir [Services/README.md](../Services/README.md) et `Services/docker-compose.yml`.

