# AEGER · Contrat d'interfaces entre les services

Ce fichier fixe tout ce que les services doivent partager : messages, ports, URL, certificats, secrets.
Tout composant développé pour AEGER doit le respecter. Référence fonctionnelle : `AEGER — Présentation fonctionnelle.pdf`.
Référence réseau : `ARCHITECTURE APPLICATIVE/AEGER_reseau_VM.md`.

## 1. Où tourne quoi

| VM (IP) | Composants | Type |
|---|---|---|
| VM-EDGE-01 (10.0.10.11, côté Tier 1 : `<PUBLIC>`) | NGINX + application web React | Docker |
| VM-SEC-01 (10.0.20.11) | Vault 8200, Keycloak 8443 | Docker |
| VM-HOT-01 (10.0.30.11) | mosquitto 8883, **ingestion** 8000 | Docker |
| VM-HOT-02 (10.0.30.12) | **alerting** (pas de port), **notification** 8000 | Docker |
| VM-HOT-03 (10.0.30.13) | **vitals** 8000 | Docker |
| VM-KAFKA-01 (10.0.40.11) | Kafka 9093 (mTLS) | systemd |
| VM-DB-01 (10.0.40.12) | PostgreSQL 16 + TimescaleDB 5432 (TLS) | systemd |
| VM-COLD-01 (10.0.50.11) | Airflow (scheduler) | Docker |
| VM-COLD-02 (10.0.50.12) | SeaweedFS, API S3 8333 (TLS) | Docker |
| VM-COLD-03 (10.0.50.13) | Spark standalone (master 7077 + worker) | Docker |
| VM-COLD-04 (10.0.50.14) | Grafana (mTLS 3000 via sidecar NGINX) | Docker |

`<PUBLIC>` = **adresse publique d'AEGER** : VM-EDGE-01 vue du Tier 1 (sa carte sur le switch LAN de vSphere). Elle est fixée par la variable `AEGER_PUBLIC_HOST` (fichier `deploy/aeger.conf`) ; 192.168.56.10 sur la maquette VirtualBox.

Chaque VM résout les autres par leur nom (`vm-hot-01`, `vm-kafka-01`…) via `/etc/hosts`.
**Tous les conteneurs tournent en `network_mode: host`** (le pare-feu ufw de la VM s'applique donc à eux).

## 2. Certificats et secrets (Vault Agent)

Sur chaque VM, un conteneur **Vault Agent** écrit les fichiers dans `/opt/aeger/secrets/` (monté en lecture seule
dans les conteneurs au même chemin) :

| Fichier | Contenu |
|---|---|
| `ca.pem` | certificat de l'autorité AEGER (racine) |
| `<service>.pem` | clé privée **PKCS#8** (`-----BEGIN PRIVATE KEY-----`, clé EC P-256) + certificat + CA, dans un seul fichier |
| `<service>.env` | secrets du service au format `CLE=valeur` (mots de passe base, etc.) |
| `keycloak-realm.pem` | clé publique RSA du realm Keycloak `aeger` (`-----BEGIN PUBLIC KEY-----`) — VM-HOT-02 et VM-HOT-03 |

Le **CN** du certificat d'un service est son nom : `nginx`, `ingestion`, `alerting`, `notification`, `vitals`,
`mosquitto`, `kafka`, `postgres`, `keycloak`, `vault`, `spark`, `airflow`, `seaweedfs`, `grafana`.
Les SAN contiennent le nom de la VM et son IP (ex. `ingestion` : DNS `ingestion`, `vm-hot-01`, IP `10.0.30.11`).

Bracelets : CN = identifiant du bracelet, `hr-00001` à `hr-01000` (haut risque), `std-00001` à `std-09000` (standards).

## 3. Message « mesure »

Envoyé par les bracelets, écrit tel quel (enrichi) dans Kafka.

```json
{
  "bracelet_id": "hr-00042",
  "patient_id": "P00042",
  "type": "high_risk",
  "seq": 1532,
  "ts": "2026-11-18T14:03:05.120Z",
  "heart_rate": 72,
  "spo2": 97,
  "temperature": 36.8,
  "systolic": 121,
  "diastolic": 79,
  "anomaly": false
}
```

- `type` : `high_risk` (bracelets `hr-*`) ou `standard` (bracelets `std-*`).
- `patient_id` : `P` + numéro sur 5 chiffres. `hr-00042` → `P00042` ; `std-00042` → `P01042` (numéro + 1000).
- `seq` : compteur propre au bracelet, croissant. **Identifiant unique d'une mesure : `bracelet_id` + `seq`.**
- `anomaly` : mis à `true` par un bracelet standard quand il envoie une mesure anormale immédiatement (hors lot).
- Validation par ingestion : tous les champs présents ; `heart_rate` 20–250 ; `spo2` 50–100 ; `temperature` 30–43 ;
  `systolic` 50–260 ; `diastolic` 30–160 ; `ts` pas plus de 24 h dans le passé ni 5 min dans le futur ;
  `bracelet_id` cohérent avec `type` et `patient_id`.

### Transport

- **Bracelets haut risque** : MQTT 3.1.1 sur TLS, `<PUBLIC>:8883` (NGINX relaie en TCP vers mosquitto, TLS non
  terminé). Certificat client obligatoire. Topic : `aeger/vitals/<bracelet_id>`, QoS 1, un message = une mesure.
  ACL mosquitto : un bracelet n'écrit que dans son propre topic ; `ingestion` lit `aeger/vitals/#`.
- **Bracelets standards** : `POST https://<PUBLIC>:8443/api/measures` (mTLS, certificat du bracelet),
  corps = **tableau JSON** de mesures (lot horaire, ou une seule mesure avec `anomaly: true`).
  NGINX vérifie le certificat et transmet à ingestion `POST https://vm-hot-01:8000/measures` avec l'en-tête
  `X-Client-CN: <CN du bracelet>`. Ingestion refuse (403) toute mesure dont `bracelet_id` ≠ `X-Client-CN`.
  Réponse : `202 {"accepted": n, "duplicates": d, "rejected": r}` ; `400` si le JSON est illisible.

### Dans Kafka

- Topic **`vitals.raw`**, 3 partitions, rétention 3 jours, **clé = `patient_id`**.
- Valeur = la mesure + `"source": "mqtt" | "https"` + `"received_at": "<ISO-8601 UTC>"`.
- Ingestion retire les doublons (même `bracelet_id` + `seq` déjà reçu). Implémentation économe en mémoire :
  pour chaque bracelet, le `seq` maximal vu et une fenêtre des 4 096 derniers `seq` (tolère le désordre).
- Ingestion produit avec `acks=all` et idempotence activée.

## 4. Message « alerte »

Produit par **alerting**, transmis à **notification**, poussé au navigateur du médecin.

```json
{
  "alert_id": "6f1c…",
  "patient_id": "P00042",
  "bracelet_id": "hr-00042",
  "type": "high_risk",
  "severity": "CRITICAL",
  "measure_ts": "2026-11-18T14:03:05.120Z",
  "detected_at": "2026-11-18T14:03:05.900Z",
  "reasons": [ { "parameter": "spo2", "value": 84, "rule": "< 90" } ],
  "measure": { "...": "la mesure complète" }
}
```

Seuils (une mesure déclenche une alerte si au moins une règle est vraie) :

| Paramètre | WARNING | CRITICAL |
|---|---|---|
| heart_rate | < 40 ou > 130 | < 35 ou > 150 |
| spo2 | < 90 | < 85 |
| temperature | < 35.0 ou > 39.5 | < 34.0 ou > 40.5 |
| systolic | < 90 ou > 180 | < 80 ou > 200 |
| anomaly = true | toujours (règle `anomaly`) | — |

`severity` = la plus haute des règles vraies.

## 5. API des services (toutes en HTTPS, mTLS obligatoire, port 8000)

Le serveur exige un certificat client signé par `ca.pem` et vérifie son **CN** :

| Service | Méthode et chemin | CN client autorisé | Jeton OIDC |
|---|---|---|---|
| ingestion | `POST /measures` | `nginx` | non (le bracelet est authentifié par son certificat) |
| notification | `POST /internal/alerts` (corps = alerte) → `204` | `alerting` | non |
| notification | `GET /api/alerts/recent` → 100 dernières alertes | `nginx` | oui |
| notification | WebSocket `/ws/alerts?access_token=<JWT>` | `nginx` | oui (paramètre) |
| vitals | `GET /api/vitals/patients?q=&limit=50` | `nginx` | oui |
| vitals | `GET /api/vitals/patients/{id}/history?hours=168` | `nginx` | oui |

- `GET /api/vitals/patients` → `[{"patient_id","bracelet_id","type","last_ts","last":{mesure}}]`, triés par `patient_id`,
  filtrés par `q` (préfixe ou sous-chaîne du patient_id).
- `GET /api/vitals/patients/{id}/history?hours=h` (1 ≤ h ≤ 168) →
  `{"patient_id","hours","bucket_seconds","points":[{"ts","heart_rate","spo2","temperature","systolic","diastolic"}]}`.
  Moyennes par intervalle (`time_bucket`) pour ne jamais renvoyer plus de ~2 000 points.
- Chaque service expose aussi `GET /health` (`200 {"status":"UP"}`), sans jeton, pour tout CN valide.

**Jeton OIDC (JWT)** : émis par Keycloak, realm `aeger`. Vérification **hors ligne** avec la clé publique
`/opt/aeger/secrets/keycloak-realm.pem` (RS256) : signature, `exp`, émetteur
`https://<PUBLIC>/auth/realms/aeger`, et rôle **`medecin`** dans `realm_access.roles`. Sinon `401`/`403`.
(Pas d'appel réseau vers Keycloak : ce flux n'existe pas dans le schéma.)

## 6. Accès publics via NGINX (VM-EDGE-01)

| Port | Chemin | Destination |
|---|---|---|
| 443 | `/` | application web React (fichiers statiques) |
| 443 | `/auth/` | Keycloak `https://vm-sec-01:8443/auth/` |
| 443 | `/api/vitals/` | `https://vm-hot-03:8000/api/vitals/` |
| 443 | `/api/alerts/` | `https://vm-hot-02:8000/api/alerts/` |
| 443 | `/ws/` | `https://vm-hot-02:8000/ws/` (WebSocket) |
| 443 | `/grafana/` | `https://vm-cold-04:3000/grafana/` |
| 8443 | `/api/measures` | `https://vm-hot-01:8000/measures` (certificat bracelet exigé) |
| 8883 | TCP | `vm-hot-01:8883` (mosquitto, TLS de bout en bout) |

NGINX présente son certificat `nginx.pem` aux services (mTLS).

## 7. Keycloak

- URL publique : `https://<PUBLIC>/auth`, realm **`aeger`**.
- Client **`aeger-web`** (public, Authorization Code + PKCE, redirect `https://<PUBLIC>/*`, web origin `https://<PUBLIC>`).
- Client **`grafana`** (confidentiel).
- Rôles du realm : `medecin`, `analyste`. Utilisateurs de test : `medecin1` / `medecin1` (medecin),
  `analyste1` / `analyste1` (analyste).

## 8. Kafka (VM-KAFKA-01)

- `vm-kafka-01:9093`, `security.protocol=SSL`, certificat client obligatoire ; principal Kafka = CN du certificat.
- Clients Java : `ssl.keystore.type=PEM`, `ssl.keystore.location=/opt/aeger/secrets/<service>.pem`,
  `ssl.truststore.type=PEM`, `ssl.truststore.location=/opt/aeger/secrets/ca.pem`,
  `ssl.endpoint.identification.algorithm=https`.
- ACL : `ingestion` WRITE `vitals.raw` ; `alerting` READ `vitals.raw` + groupe `alerting` ;
  `vitals` READ `vitals.raw` + groupe `vitals` ; `spark` READ `vitals.raw` + groupes préfixés `aeger-spark`.

## 9. PostgreSQL (VM-DB-01)

- `vm-db-01:5432`, TLS obligatoire (`sslmode=verify-full`, `sslrootcert=/opt/aeger/secrets/ca.pem`), mot de passe SCRAM.
- Base **`vitals`** : hypertable `public.measures`, rétention 7 jours.

```sql
CREATE TABLE measures (
  time        TIMESTAMPTZ NOT NULL,      -- = ts de la mesure
  patient_id  TEXT NOT NULL,
  bracelet_id TEXT NOT NULL,
  type        TEXT NOT NULL,
  seq         BIGINT NOT NULL,
  heart_rate  INT, spo2 INT, temperature REAL, systolic INT, diastolic INT,
  anomaly     BOOLEAN NOT NULL DEFAULT false,
  UNIQUE (bracelet_id, seq, time)
);  -- + create_hypertable, index (patient_id, time DESC), add_retention_policy 7 days
```

  Rôles : `vitals_writer` (INSERT seul, `INSERT … ON CONFLICT DO NOTHING`), `vitals_reader` (SELECT seul).
  Variables dans `/opt/aeger/secrets/vitals.env` : `DB_WRITER_USER`, `DB_WRITER_PASSWORD`, `DB_READER_USER`, `DB_READER_PASSWORD`.
- Base **`kpi`** : schéma `kpi` (tables KPI, écrites par `kpi_writer`, lues par `grafana_ro`) et schéma `lake`
  (catalogue Iceberg JDBC, propriété de `kpi_writer`).

```sql
CREATE TABLE kpi.daily_summary (
  day DATE PRIMARY KEY, nb_measures BIGINT, nb_patients INT, nb_alerts BIGINT,
  nb_high_risk_measures BIGINT, nb_standard_measures BIGINT, nb_anomalies BIGINT,
  avg_heart_rate DOUBLE PRECISION, avg_spo2 DOUBLE PRECISION, avg_temperature DOUBLE PRECISION,
  pct_alert DOUBLE PRECISION, computed_at TIMESTAMPTZ);
CREATE TABLE kpi.daily_by_type (
  day DATE, type TEXT, nb_measures BIGINT, nb_patients INT, nb_alerts BIGINT,
  avg_heart_rate DOUBLE PRECISION, avg_spo2 DOUBLE PRECISION, avg_temperature DOUBLE PRECISION,
  PRIMARY KEY (day, type));
CREATE TABLE kpi.hourly (
  day DATE, hour INT, nb_measures BIGINT, nb_alerts BIGINT, avg_heart_rate DOUBLE PRECISION,
  PRIMARY KEY (day, hour));
CREATE TABLE kpi.top_patients (
  day DATE, patient_id TEXT, type TEXT, nb_alerts BIGINT, min_spo2 INT, max_heart_rate INT,
  PRIMARY KEY (day, patient_id));
```

  Une « alerte » au sens des KPI = une mesure qui déclenche au moins une règle du § 4.

## 10. Traitement quotidien (cold path)

- Airflow (VM-COLD-01) lance chaque nuit à 02:00 (Europe/Paris) le DAG `aeger_daily`, qui exécute
  `spark-submit --master spark://vm-cold-03:7077 --deploy-mode cluster` du job Java `aeger.batch.DailyJob`
  (jar présent dans l'image Spark sous `/opt/aeger/jobs/aeger-batch.jar`), avec l'argument `--day YYYY-MM-DD`
  (par défaut la veille ; pour une démo, on peut passer le jour courant).
- Sécurité Spark : `spark.authenticate=true` + secret partagé (`SPARK_AUTH_SECRET` dans `spark.env` / `airflow.env`),
  `spark.network.crypto.enabled=true`.
- Le job lit dans Kafka les mesures du jour demandé (`startingTimestamp` / `endingTimestamp`), puis écrit dans le
  Data Lake (tables **Iceberg**, catalogue JDBC dans `kpi` schéma `lake`, fichiers sur SeaweedFS
  `https://vm-cold-02:8333`, bucket `aeger-lake`) :
  `lake.raw.vitals` → `lake.cleaned.vitals` (doublons et valeurs invalides retirés) → `lake.kpi.daily_summary`
  (+ `daily_by_type`, `hourly`, `top_patients`), puis remplace les lignes du jour dans les tables `kpi.*` de PostgreSQL.
- Grafana (VM-COLD-04) lit `kpi.*` avec le rôle `grafana_ro`.
