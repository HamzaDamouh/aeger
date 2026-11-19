# AEGER - Airflow (scheduler seul) qui lance chaque nuit le job Spark du cold path.
#   cd Services/airflow && docker build -t aeger/airflow:1.0 .
FROM apache/airflow:2.10.5-python3.12

# Même version que le cluster (image aeger/spark) : le client spark-submit et le maître doivent concorder.
ARG SPARK_VERSION=3.5.9

USER root
# Java pour spark-submit. /opt/java/openjdk = même JAVA_HOME que l'image apache/spark : en mode cluster
# standalone, spark-submit transmet son environnement (dont JAVA_HOME) au driver lancé par le worker.
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends openjdk-17-jre-headless; \
    apt-get clean; \
    rm -rf /var/lib/apt/lists/*; \
    mkdir -p /opt/java; \
    ln -s "$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")" /opt/java/openjdk; \
    /opt/java/openjdk/bin/java -version
COPY entrypoint.sh /opt/aeger/bin/entrypoint.sh
RUN set -eux; \
    sed -i 's/\r$//' /opt/aeger/bin/entrypoint.sh; \
    chmod 0755 /opt/aeger/bin/entrypoint.sh; \
    mkdir -p /opt/airflow/data; \
    chown airflow:root /opt/airflow/data

USER airflow
# pyspark fournit spark-submit (client uniquement). apache-airflow épinglé pour que pip n'y touche pas.
RUN pip install --no-cache-dir "apache-airflow==${AIRFLOW_VERSION}" "pyspark==${SPARK_VERSION}" \
 && spark-submit --version

COPY --chown=airflow:root dags/ /opt/airflow/dags/

ENV JAVA_HOME=/opt/java/openjdk \
    AIRFLOW__CORE__EXECUTOR=SequentialExecutor \
    AIRFLOW__DATABASE__SQL_ALCHEMY_CONN=sqlite:////opt/airflow/data/airflow.db \
    AIRFLOW__DATABASE__LOAD_DEFAULT_CONNECTIONS=False \
    AIRFLOW__CORE__LOAD_EXAMPLES=False \
    AIRFLOW__CORE__PARALLELISM=1 \
    AIRFLOW__CORE__MAX_ACTIVE_TASKS_PER_DAG=1 \
    AIRFLOW__CORE__MAX_ACTIVE_RUNS_PER_DAG=1 \
    AIRFLOW__CORE__DAGS_ARE_PAUSED_AT_CREATION=False \
    AIRFLOW__CORE__DEFAULT_TIMEZONE=Europe/Paris \
    AIRFLOW__SCHEDULER__CATCHUP_BY_DEFAULT=False \
    AIRFLOW__SCHEDULER__PARSING_PROCESSES=1 \
    AIRFLOW__SCHEDULER__MIN_FILE_PROCESS_INTERVAL=60 \
    AIRFLOW__SCHEDULER__DAG_DIR_LIST_INTERVAL=120 \
    AIRFLOW__SCHEDULER__ENABLE_HEALTH_CHECK=False \
    AIRFLOW__LOGGING__LOGGING_LEVEL=INFO

ENTRYPOINT ["/usr/bin/dumb-init", "--", "/opt/aeger/bin/entrypoint.sh"]
CMD []
