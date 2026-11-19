#!/bin/bash
# Topic vitals.raw + ACL (moindre privilège : chaque service n'a que le droit dont il a besoin)
set -euo pipefail
B="--bootstrap-server vm-kafka-01:9093 --command-config /etc/kafka/client-admin.properties"
K=/opt/kafka/bin

$K/kafka-topics.sh $B --create --if-not-exists --topic vitals.raw --partitions 3 --replication-factor 1 \
  --config retention.ms=259200000

acl() { $K/kafka-acls.sh $B --add --force "$@" >/dev/null; }
# ingestion : écrit seulement
acl --allow-principal User:ingestion --operation Write --operation Describe --topic vitals.raw
# alerting et vitals : lisent seulement, chacun avec son groupe de consommateurs
acl --allow-principal User:alerting --operation Read --operation Describe --topic vitals.raw
acl --allow-principal User:alerting --operation Read --group alerting
acl --allow-principal User:vitals --operation Read --operation Describe --topic vitals.raw
acl --allow-principal User:vitals --operation Read --group vitals
# spark (traitement de nuit) : lit seulement, groupes préfixés aeger-spark
acl --allow-principal User:spark --operation Read --operation Describe --topic vitals.raw
acl --allow-principal User:spark --operation Read --resource-pattern-type prefixed --group aeger-spark

echo "== ACL Kafka :"
$K/kafka-acls.sh $B --list | grep -E "principal|Topic|Group" | sed 's/^/   /'
