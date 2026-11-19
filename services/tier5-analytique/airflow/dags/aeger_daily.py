"""
AEGER - cold path quotidien (CONTRAT.md, section 10).

Chaque nuit à 02:00 (Europe/Paris), lance le job Spark `aeger.batch.DailyJob` sur le cluster standalone
`spark://vm-cold-03:7077` en mode **cluster** (le driver tourne dans le worker Spark de VM-COLD-03 ;
Airflow n'a besoin que du flux sortant 7077).

Jour traité : `dag_run.conf["day"]` s'il est fourni, sinon `ds` (date logique = la veille pour un run planifié).
Démo : `airflow dags trigger aeger_daily --conf '{"day":"2026-11-18"}'`.

En mode cluster standalone, le code de sortie de spark-submit ne reflète pas l'issue du driver : la tâche lit
l'état final du driver dans la sortie de spark-submit ("State of driver driver-... is FINISHED|FAILED|ERROR|KILLED")
et n'est en succès que si cet état est FINISHED.

Jar : `file:/opt/aeger/jobs/aeger-batch.jar` = chemin sur le worker (présent dans l'image aeger/spark).
Le schéma `local:` n'est pas accepté par le worker standalone en mode cluster
("No FileSystem for scheme local").
"""
from __future__ import annotations

from datetime import timedelta

import pendulum
from airflow import DAG
from airflow.operators.bash import BashOperator

# Script bash (gabarit Jinja : ne pas y écrire de double accolade ni de séquence accolade-dièse).
SPARK_SUBMIT_SCRIPT = r"""
set -uo pipefail

DAY="${AEGER_DAY:-}"
MASTER="${AEGER_SPARK_MASTER:-spark://vm-cold-03:7077}"
# Chemin sur le worker (le worker le copie dans le dossier du driver ; "local:" y est refusé en mode cluster).
JAR="file:/opt/aeger/jobs/aeger-batch.jar"

if ! [[ "$DAY" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]]; then
  echo "[aeger] jour invalide : '$DAY' (attendu YYYY-MM-DD)" >&2
  exit 2
fi
if [ -z "${SPARK_AUTH_SECRET:-}" ]; then
  echo "[aeger] SPARK_AUTH_SECRET non défini dans le conteneur airflow (airflow.env)" >&2
  exit 2
fi

OUT="$(mktemp /tmp/aeger-spark-submit.XXXXXX)"
trap 'rm -f "$OUT"' EXIT

echo "[aeger] spark-submit aeger.batch.DailyJob --day $DAY sur $MASTER (deploy-mode cluster)"
# env -i : en mode cluster standalone, spark-submit transmet tout son environnement au driver ;
# on n'y met que le strict nécessaire (JAVA_HOME = même chemin que dans l'image aeger/spark).
env -i PATH="$PATH" HOME="$HOME" LANG=C.UTF-8 JAVA_HOME="${JAVA_HOME:-}" \
  spark-submit \
    --master "$MASTER" \
    --deploy-mode cluster \
    --class aeger.batch.DailyJob \
    --conf spark.authenticate=true \
    --conf "spark.authenticate.secret=$SPARK_AUTH_SECRET" \
    --conf spark.network.crypto.enabled=true \
    --conf spark.standalone.submit.waitAppCompletion=true \
    --conf spark.driver.memory=512m \
    --conf spark.executor.memory=512m \
    "$JAR" --day "$DAY" 2>&1 | tee "$OUT"
RC=${PIPESTATUS[0]}

DRIVER="$(grep -oE 'driver-[0-9]+-[0-9]+' "$OUT" | head -n 1 || true)"
# "State of driver driver-X is FINISHED, exiting spark-submit JVM." (fin normale) ou
# "State of driver-X is ERROR" (suivi de l'exception du cluster) : on garde le dernier état vu.
STATE="$(grep -oE 'State of (driver )?driver-[0-9-]+ is [A-Z_]+' "$OUT" | tail -n 1 | awk '{print $NF}' || true)"
echo "[aeger] spark-submit : code=$RC driver=${DRIVER:-?} etat final=${STATE:-INCONNU}"

if [ "$RC" -eq 0 ] && [ "$STATE" = "FINISHED" ]; then
  echo "[aeger] job Spark OK pour le $DAY"
  exit 0
fi
echo "[aeger] ECHEC du job Spark pour le $DAY (etat ${STATE:-INCONNU}, code $RC)." >&2
echo "[aeger] Journaux du driver : VM-COLD-03, conteneur spark-worker, /opt/aeger/work/${DRIVER:-driver-*}/stderr" >&2
exit 1
"""

with DAG(
    dag_id="aeger_daily",
    description="AEGER cold path : Kafka vitals.raw -> Data Lake Iceberg -> KPI PostgreSQL (Spark DailyJob)",
    schedule="0 2 * * *",
    start_date=pendulum.datetime(2026, 11, 1, tz="Europe/Paris"),
    catchup=False,
    max_active_runs=1,
    max_active_tasks=1,
    is_paused_upon_creation=False,
    default_args={
        "owner": "aeger",
        "retries": 1,
        "retry_delay": timedelta(minutes=5),
        "execution_timeout": timedelta(hours=2),
    },
    tags=["aeger", "cold-path", "spark"],
    doc_md=__doc__,
) as dag:
    BashOperator(
        task_id="spark_daily_job",
        bash_command=SPARK_SUBMIT_SCRIPT,
        # Le jour passe par une variable d'environnement (jamais interprétée par le shell) puis est validé.
        env={"AEGER_DAY": "{{ (dag_run.conf or {}).get('day') or ds }}"},
        append_env=True,
    )
