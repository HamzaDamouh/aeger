#!/usr/bin/env bash
# AEGER - point d'entrée de l'image aeger/airflow.
#   sans argument : migration de la base SQLite puis scheduler seul (SequentialExecutor, pas de serveur web)
#   avec arguments : migration puis exécution de la commande (ex. airflow dags list)
set -euo pipefail

log() { echo "[aeger-airflow] $(date -u +%Y-%m-%dT%H:%M:%SZ) $*" >&2; }

[ -n "${SPARK_AUTH_SECRET:-}" ] || log "ATTENTION: SPARK_AUTH_SECRET non défini (airflow.env) : le DAG aeger_daily échouera"

case "${AIRFLOW__DATABASE__SQL_ALCHEMY_CONN:-}" in
  sqlite:///*) mkdir -p "$(dirname "${AIRFLOW__DATABASE__SQL_ALCHEMY_CONN#sqlite:///}")" ;;
esac
log "airflow db migrate ($AIRFLOW__DATABASE__SQL_ALCHEMY_CONN)"
airflow db migrate >/dev/null

if [ "$#" -gt 0 ]; then
  exec "$@"
fi

log "démarrage du scheduler (SequentialExecutor, parallelism 1, sans webserver ni serveur de logs)"
exec airflow scheduler --skip-serve-logs
