#!/bin/bash
# Installe Apache Kafka 4.3 (mode KRaft, 1 nœud) comme SERVICE SYSTEMD sur VM-KAFKA-01.
# (Kafka ne tourne pas en conteneur : choix de l'architecture AEGER v2.)
# Prérequis : le Vault Agent a écrit /opt/aeger/secrets/kafka.pem et ca.pem.
# Usage : sudo bash install.sh        (depuis le dossier Services/kafka copié sur la VM)
set -euo pipefail
KAFKA_VERSION=4.3.2
SCALA=2.13
HERE="$(cd "$(dirname "$0")" && pwd)"

echo "== Java 17 et utilisateur kafka"
apt-get install -y -qq openjdk-17-jre-headless >/dev/null
id kafka >/dev/null 2>&1 || useradd --system --home /var/lib/kafka --shell /usr/sbin/nologin kafka
usermod -aG aeger-secrets kafka          # lecture des certificats écrits par le Vault Agent

echo "== Téléchargement de Kafka $KAFKA_VERSION"
if [ ! -d /opt/kafka_$SCALA-$KAFKA_VERSION ]; then
  curl -fsSL "https://downloads.apache.org/kafka/$KAFKA_VERSION/kafka_$SCALA-$KAFKA_VERSION.tgz" -o /tmp/kafka.tgz
  tar -xzf /tmp/kafka.tgz -C /opt && rm /tmp/kafka.tgz
fi
ln -sfn /opt/kafka_$SCALA-$KAFKA_VERSION /opt/kafka

echo "== Configuration"
mkdir -p /etc/kafka /var/lib/kafka/data /var/log/kafka
install -m 0644 "$HERE/server.properties" /etc/kafka/server.properties
install -m 0644 "$HERE/client-admin.properties" /etc/kafka/client-admin.properties
chown -R kafka:kafka /var/lib/kafka /var/log/kafka
export LOG_DIR=/tmp/kafka-admin-logs      # journaux des outils en ligne de commande

if [ ! -f /var/lib/kafka/data/meta.properties ]; then
  echo "== Formatage du stockage KRaft"
  CLUSTER_ID=$(/opt/kafka/bin/kafka-storage.sh random-uuid)
  sudo -u kafka /opt/kafka/bin/kafka-storage.sh format -t "$CLUSTER_ID" -c /etc/kafka/server.properties
fi

install -m 0644 "$HERE/kafka.service" /etc/systemd/system/kafka.service
systemctl daemon-reload
systemctl enable kafka >/dev/null 2>&1
systemctl restart kafka

echo "== Attente du broker"
for i in $(seq 1 60); do
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server vm-kafka-01:9093 --command-config /etc/kafka/client-admin.properties --list >/dev/null 2>&1 && break
  sleep 3
done

bash "$HERE/acl.sh"
echo "== Kafka prêt"
