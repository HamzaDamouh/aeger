# AEGER - image Spark standalone (master / worker) contenant le job quotidien.
#
# Le contexte de build est le dossier tier5-analytique/ (le job est dans ../spark-job) :
#   cd Services/tier5-analytique && docker build -f spark/Dockerfile -t aeger/spark:1.0 .
#
# Options : --build-arg MAVEN_TEST_FLAGS=   (vide) pour exécuter aussi les tests unitaires du job.

ARG SPARK_VERSION=3.5.9

# ----------------------------------------------------------------------------- 1. jar du job
FROM maven:3.9-eclipse-temurin-17 AS job
ARG MAVEN_TEST_FLAGS=-DskipTests
WORKDIR /build
COPY spark-job/pom.xml ./pom.xml
COPY spark-job/src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q ${MAVEN_TEST_FLAGS} package \
 && ls -l target/aeger-batch.jar

# ------------------------------------------------- 2. jars supplémentaires (Maven Central)
# Téléchargés par Maven : sommes de contrôle vérifiées (-C = échec si une somme est fausse).
FROM maven:3.9-eclipse-temurin-17 AS jars
ARG SPARK_VERSION
ARG ICEBERG_VERSION=1.10.1
# Versions utilisées par spark-sql-kafka-0-10 ${SPARK_VERSION} (pom Spark 3.5.x : kafka 3.4.1, commons-pool2 2.11.1)
ARG KAFKA_CLIENTS_VERSION=3.4.1
ARG COMMONS_POOL2_VERSION=2.11.1
ARG POSTGRESQL_JDBC_VERSION=42.7.8
RUN --mount=type=cache,target=/root/.m2 \
    set -eux; mkdir -p /jars; \
    for a in \
      org.apache.iceberg:iceberg-spark-runtime-3.5_2.12:${ICEBERG_VERSION} \
      org.apache.iceberg:iceberg-aws-bundle:${ICEBERG_VERSION} \
      org.apache.spark:spark-sql-kafka-0-10_2.12:${SPARK_VERSION} \
      org.apache.spark:spark-token-provider-kafka-0-10_2.12:${SPARK_VERSION} \
      org.apache.kafka:kafka-clients:${KAFKA_CLIENTS_VERSION} \
      org.apache.commons:commons-pool2:${COMMONS_POOL2_VERSION} \
      org.postgresql:postgresql:${POSTGRESQL_JDBC_VERSION} ; do \
      mvn -B -q -C dependency:copy -Dartifact="$a" -DoutputDirectory=/jars; \
    done; \
    ls -l /jars

# --------------------------------------------------------------------------- 3. image finale
FROM apache/spark:${SPARK_VERSION}-scala2.12-java17-ubuntu

USER root
COPY --from=jars /jars/ /opt/spark/jars/
COPY --from=job /build/target/aeger-batch.jar /opt/aeger/jobs/aeger-batch.jar
COPY spark/entrypoint.sh /opt/aeger/bin/entrypoint.sh
RUN set -eu; \
    sed -i 's/\r$//' /opt/aeger/bin/entrypoint.sh; \
    chmod 0755 /opt/aeger/bin/entrypoint.sh; \
    chmod 0644 /opt/aeger/jobs/aeger-batch.jar; \
    mkdir -p /opt/aeger/conf /opt/aeger/work /opt/aeger/secrets; \
    chown spark:spark /opt/aeger /opt/aeger/conf /opt/aeger/work

# - SPARK_CONF_DIR : spark-defaults.conf écrit au démarrage par entrypoint.sh
# - AWS_* : le SDK AWS v2 (iceberg-aws-bundle) n'envoie des sommes CRC que si S3 les exige
#   (compatibilité SeaweedFS / S3 non-AWS)
ENV SPARK_CONF_DIR=/opt/aeger/conf \
    SPARK_WORKER_DIR=/opt/aeger/work \
    AWS_REQUEST_CHECKSUM_CALCULATION=WHEN_REQUIRED \
    AWS_RESPONSE_CHECKSUM_VALIDATION=WHEN_REQUIRED \
    PATH=/opt/spark/bin:/opt/spark/sbin:/opt/java/openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

USER spark
WORKDIR /opt/aeger
EXPOSE 7077
ENTRYPOINT ["/usr/bin/tini", "--", "/opt/aeger/bin/entrypoint.sh"]
CMD ["worker"]
