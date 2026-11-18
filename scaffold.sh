#!/usr/bin/env bash


set -euo pipefail

echo "Building Structure............"

dir=(
    services/ingestion/app
    services/ingestion/tests
    services/alerting/app
    services/alerting/tests
    services/notification/app
    services/notification/tests
    services/vitals/app
    services/vitals/tests

    web
    simulator

    cold/spark-jobs
    cold/airflow-dags

    infra/vm-edge-01
    infra/vm-hot-01
    infra/vm-hot-02
    infra/vm-hot-03
    infra/vm-kafka-01
    infra/vm-db-01


    docs/architecture
    docs/decision-making



    .gitlab/ci
    .gitlab/merge_request_templates
)

for d in "${dir[@]}"; do
    mkdir -p "$d"
    echo "[DIR] $d"
done

files=(
    services/ingestion/Dockerfile
    services/ingestion/requirements.txt
    services/ingestion/requirements-dev.txt
    services/ingestion/.gitlab-ci.yml

    services/alerting/Dockerfile
    services/alerting/requirements.txt
    services/alerting/requirements-dev.txt
    services/alerting/.gitlab-ci.yml

    services/notification/Dockerfile
    services/notification/requirements.txt
    services/notification/requirements-dev.txt
    services/notification/.gitlab-ci.yml

    services/vitals/Dockerfile
    services/vitals/requirements.txt
    services/vitals/requirements-dev.txt
    services/vitals/.gitlab-ci.yml

    infra/vm-edge-01/nginx.conf
    infra/vm-edge-01/compose.yaml

    infra/vm-hot-01/mosquitto.conf
    infra/vm-hot-01/compose.yaml
    infra/vm-hot-02/compose.yaml
    infra/vm-hot-03/compose.yaml

    infra/vm-kafka-01/README.md
    infra/vm-db-01/README.md

    docs/architecture/aeger_v2.drawio

    .gitlab/ci/templates.yml
    .gitlab/ci/deploy.yml

    .gitlab/merge_request_templates/Default.md

    .gitlab-ci.yml
    .gitignore
)

for f in "${files[@]}"; do
    if [[ ! -e "$f" ]]; then
        touch "$f"
        echo "[FILE] $f"
    fi
done


echo ""
echo ""
echo "AEGER STRUCTURE BUILT SUCCESSFULLY"