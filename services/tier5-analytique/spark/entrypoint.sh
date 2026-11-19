#!/usr/bin/env bash
# AEGER - point d'entrée de l'image aeger/spark.
#   master  : maître standalone, port 7077 (toutes interfaces), interface web 8080 sur 127.0.0.1
#   worker  : worker rattaché à spark://vm-cold-03:7077 ; exécute aussi le driver (mode cluster)
#   autre   : commande exécutée telle quelle (ex. spark-submit ..., bash)
set -euo pipefail

MODE="${1:-worker}"
SECRETS_DIR=/opt/aeger/secrets
CA_PEM="$SECRETS_DIR/ca.pem"
KAFKA_PEM="$SECRETS_DIR/spark.pem"
TRUSTSTORE=/opt/aeger/truststore.p12
TRUSTSTORE_PASSWORD="${TRUSTSTORE_PASSWORD:-changeit}"
CONF_DIR="${SPARK_CONF_DIR:-/opt/aeger/conf}"
TRUST_OPTS="-Djavax.net.ssl.trustStore=$TRUSTSTORE -Djavax.net.ssl.trustStoreType=PKCS12 -Djavax.net.ssl.trustStorePassword=$TRUSTSTORE_PASSWORD"
# Options Java 17 de Spark 3.5 (= org.apache.spark.launcher.JavaModuleOptions). spark-submit les ajoute au driver
# en mode client, mais en mode cluster le worker standalone lance le driver sans elles
# (IllegalAccessError sun.nio.ch.DirectBuffer) : on les fournit par JDK_JAVA_OPTIONS (cf. mode worker).
JAVA17_MODULE_OPTS="-XX:+IgnoreUnrecognizedVMOptions \
--add-opens=java.base/java.lang=ALL-UNNAMED \
--add-opens=java.base/java.lang.invoke=ALL-UNNAMED \
--add-opens=java.base/java.lang.reflect=ALL-UNNAMED \
--add-opens=java.base/java.io=ALL-UNNAMED \
--add-opens=java.base/java.net=ALL-UNNAMED \
--add-opens=java.base/java.nio=ALL-UNNAMED \
--add-opens=java.base/java.util=ALL-UNNAMED \
--add-opens=java.base/java.util.concurrent=ALL-UNNAMED \
--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED \
--add-opens=java.base/jdk.internal.ref=ALL-UNNAMED \
--add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
--add-opens=java.base/sun.nio.cs=ALL-UNNAMED \
--add-opens=java.base/sun.security.action=ALL-UNNAMED \
--add-opens=java.base/sun.util.calendar=ALL-UNNAMED \
--add-opens=java.security.jgss/sun.security.krb5=ALL-UNNAMED \
-Djdk.reflect.useDirectMethodHandle=false"
export SPARK_DAEMON_MEMORY="${SPARK_DAEMON_MEMORY:-256m}"

log() { echo "[aeger-spark] $(date -u +%Y-%m-%dT%H:%M:%SZ) $*" >&2; }
die() { log "ERREUR: $*"; exit 1; }

require_auth_secret() {
  [ -n "${SPARK_AUTH_SECRET:-}" ] || die "SPARK_AUTH_SECRET non défini (fichier spark.env)"
  case "$SPARK_AUTH_SECRET" in
    *\\*) die "SPARK_AUTH_SECRET ne doit pas contenir de '\\' (format .properties) : utiliser openssl rand -hex 32" ;;
  esac
}

# Attend qu'un fichier écrit par Vault Agent soit lisible (uid du conteneur : $(id -u)).
wait_for_file() {
  local f="$1" max="$2" i=0
  until [ -r "$f" ]; do
    [ "$i" -lt "$max" ] || return 1
    [ $((i % 15)) -ne 0 ] || log "attente de $f (Vault Agent)..."
    sleep 1
    i=$((i + 1))
  done
}

# Truststore PKCS12 construit à partir de ca.pem (tous les certificats du fichier).
# Utilisé par le JDK pour le TLS vers SeaweedFS (S3) ; Kafka et PostgreSQL lisent ca.pem directement.
build_truststore() {
  wait_for_file "$CA_PEM" "${SECRETS_WAIT_SECONDS:-300}" || die "$CA_PEM absent ou illisible pour l'uid $(id -u)"
  local tmp count=0 f
  tmp="$(mktemp -d)"
  awk -v dir="$tmp" '/-----BEGIN CERTIFICATE-----/{n++} n{print > (dir "/ca-" n ".pem")}' "$CA_PEM"
  rm -f "$TRUSTSTORE"
  for f in "$tmp"/ca-*.pem; do
    [ -e "$f" ] || continue
    count=$((count + 1))
    keytool -importcert -noprompt -alias "aeger-ca-$count" -file "$f" -keystore "$TRUSTSTORE" \
      -storetype PKCS12 -storepass "$TRUSTSTORE_PASSWORD" >/dev/null
  done
  rm -rf "$tmp"
  [ "$count" -gt 0 ] || die "aucun certificat trouvé dans $CA_PEM"
  chmod 0644 "$TRUSTSTORE"
  log "truststore $TRUSTSTORE créé ($count certificat(s) issu(s) de $CA_PEM)"
}

# spark-defaults.conf (lu par le maître, le worker et tout spark-submit lancé dans le conteneur).
write_spark_defaults() {
  local with_truststore="$1"
  mkdir -p "$CONF_DIR"
  (
    umask 077
    {
      echo "# Généré par entrypoint.sh ($MODE) au démarrage du conteneur : ne pas modifier."
      echo "spark.authenticate                  true"
      echo "spark.authenticate.secret           $SPARK_AUTH_SECRET"
      echo "spark.network.crypto.enabled        true"
      echo "spark.master.rest.enabled           false"
      echo "spark.driver.memory                 ${SPARK_DRIVER_MEMORY:-512m}"
      echo "spark.executor.memory               ${SPARK_EXECUTOR_MEMORY:-512m}"
      if [ "$with_truststore" = yes ]; then
        echo "spark.driver.extraJavaOptions       $TRUST_OPTS"
        echo "spark.executor.extraJavaOptions     $TRUST_OPTS"
      fi
      echo "spark.worker.cleanup.enabled        true"
      echo "spark.worker.cleanup.appDataTtl     604800"
      echo "spark.ui.showConsoleProgress        false"
    } > "$CONF_DIR/spark-defaults.conf"
  )
}

case "$MODE" in
  master)
    require_auth_secret
    write_spark_defaults no
    # L'interface web du maître se lie à SPARK_LOCAL_IP : 127.0.0.1 = accessible seulement depuis la VM
    # (ssh -L 8080:127.0.0.1:8080 ...). Le port RPC 7077 se lie à SPARK_MASTER_HOST.
    export SPARK_LOCAL_IP="${SPARK_MASTER_UI_HOST:-127.0.0.1}"
    log "maître : ${SPARK_MASTER_HOST:-0.0.0.0}:${SPARK_MASTER_PORT:-7077}, UI $SPARK_LOCAL_IP:${SPARK_MASTER_WEBUI_PORT:-8080}"
    exec /opt/spark/bin/spark-class org.apache.spark.deploy.master.Master \
      --host "${SPARK_MASTER_HOST:-0.0.0.0}" \
      --port "${SPARK_MASTER_PORT:-7077}" \
      --webui-port "${SPARK_MASTER_WEBUI_PORT:-8080}" \
      --properties-file "$CONF_DIR/spark-defaults.conf"
    ;;

  worker)
    require_auth_secret
    for v in KPI_DB_USER KPI_DB_PASSWORD S3_ACCESS_KEY S3_SECRET_KEY; do
      [ -n "${!v:-}" ] || log "ATTENTION: $v non défini : le job quotidien échouera (fichier spark.env)"
    done
    build_truststore
    [ -r "$KAFKA_PEM" ] || log "ATTENTION: $KAFKA_PEM illisible pour l'uid $(id -u) : la lecture Kafka échouera"
    write_spark_defaults yes
    # En mode cluster, le driver et les exécuteurs sont des processus fils du worker : ils héritent de cet
    # environnement (KPI_DB_*, S3_*, et JDK_JAVA_OPTIONS -> options Java 17 + truststore), quelle que soit
    # la conf envoyée par spark-submit (Airflow).
    export JDK_JAVA_OPTIONS="${JDK_JAVA_OPTIONS:+$JDK_JAVA_OPTIONS }$JAVA17_MODULE_OPTS $TRUST_OPTS"
    # Worker, driver et exécuteurs tournent sur la même VM que le maître : leurs ports restent sur 127.0.0.1.
    export SPARK_LOCAL_IP="${SPARK_LOCAL_IP:-127.0.0.1}"
    log "worker : ${SPARK_WORKER_CORES:-2} cœur(s), ${SPARK_WORKER_MEMORY:-1g}, maître ${SPARK_MASTER_URL:-spark://vm-cold-03:7077}"
    exec /opt/spark/bin/spark-class org.apache.spark.deploy.worker.Worker \
      --cores "${SPARK_WORKER_CORES:-2}" \
      --memory "${SPARK_WORKER_MEMORY:-1g}" \
      --work-dir "${SPARK_WORKER_DIR:-/opt/aeger/work}" \
      --webui-port "${SPARK_WORKER_WEBUI_PORT:-8081}" \
      --properties-file "$CONF_DIR/spark-defaults.conf" \
      "${SPARK_MASTER_URL:-spark://vm-cold-03:7077}"
    ;;

  *)
    exec "$@"
    ;;
esac
