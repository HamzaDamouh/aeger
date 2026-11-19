#!/bin/bash
# Installe PostgreSQL 16 + TimescaleDB comme SERVICE SYSTEMD sur VM-DB-01.
# (PostgreSQL ne tourne pas en conteneur : choix de l'architecture AEGER v2.)
# Prérequis : le Vault Agent a écrit dans /opt/aeger/secrets : postgres-cert.pem, postgres-key.pem, ca.pem,
#             vitals.env, kpi.env, grafana.env, keycloak.env (mots de passe des rôles).
# Usage : sudo bash install.sh        (depuis le dossier Services/postgresql copié sur la VM)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
PGV=16
S=/opt/aeger/secrets

echo "== Dépôts PostgreSQL (PGDG) et TimescaleDB"
apt-get install -y -qq curl gnupg postgresql-common >/dev/null
[ -f /etc/apt/sources.list.d/pgdg.list ] || /usr/share/postgresql-common/pgdg/apt.postgresql.org.sh -y >/dev/null
if [ ! -f /etc/apt/sources.list.d/timescaledb.list ]; then
  curl -fsSL https://packagecloud.io/timescale/timescaledb/gpgkey | gpg --dearmor -o /etc/apt/trusted.gpg.d/timescaledb.gpg
  echo "deb https://packagecloud.io/timescale/timescaledb/ubuntu/ jammy main" > /etc/apt/sources.list.d/timescaledb.list
fi
apt-get update -qq
apt-get install -y -qq postgresql-$PGV timescaledb-2-postgresql-$PGV >/dev/null
usermod -aG aeger-secrets postgres       # lecture des certificats écrits par le Vault Agent

echo "== Configuration (TLS obligatoire, écoute sur le VLAN 40 seulement)"
CONF=/etc/postgresql/$PGV/main
install -m 0644 -o postgres -g postgres "$HERE/aeger.conf" $CONF/conf.d/aeger.conf
install -m 0640 -o postgres -g postgres "$HERE/pg_hba.conf" $CONF/pg_hba.conf
grep -q "^include_dir = 'conf.d'" $CONF/postgresql.conf || echo "include_dir = 'conf.d'" >> $CONF/postgresql.conf
systemctl restart postgresql@$PGV-main
systemctl enable postgresql >/dev/null

echo "== Rôles, bases et tables"
set -a; . $S/vitals.env; . $S/kpi.env; . $S/grafana.env; . $S/keycloak.env; set +a
sudo -u postgres psql -q -v ON_ERROR_STOP=1 \
  -v vitals_writer_pw="$DB_WRITER_PASSWORD" -v vitals_reader_pw="$DB_READER_PASSWORD" \
  -v kpi_writer_pw="$KPI_DB_PASSWORD" -v grafana_ro_pw="$GRAFANA_DB_PASSWORD" -v keycloak_pw="$KC_DB_PASSWORD" \
  -f "$HERE/init.sql"
echo "== PostgreSQL prêt"
