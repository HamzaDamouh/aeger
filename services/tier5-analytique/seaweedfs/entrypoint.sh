#!/bin/sh
# Démarre SeaweedFS (master + volume + filer + S3) et crée le bucket du Data Lake.
# Variables (s3.env, écrit par le Vault Agent) : S3_ACCESS_KEY, S3_SECRET_KEY
set -e
S=/opt/aeger/secrets
: "${S3_ACCESS_KEY:?S3_ACCESS_KEY manquant}" "${S3_SECRET_KEY:?S3_SECRET_KEY manquant}"
mkdir -p /data /etc/seaweedfs

# Une seule identité S3 (le compte de Spark), droits limités au bucket du Data Lake
cat > /etc/seaweedfs/s3.json <<EOF
{ "identities": [ {
    "name": "spark",
    "credentials": [ { "accessKey": "$S3_ACCESS_KEY", "secretKey": "$S3_SECRET_KEY" } ],
    "actions": [ "Read:aeger-lake", "Write:aeger-lake", "List:aeger-lake", "Tagging:aeger-lake", "Admin:aeger-lake" ]
} ] }
EOF

# Écoute sur toutes les interfaces : le pare-feu ufw de la VM n'ouvre que 8333, et seulement à VM-COLD-03
weed server -dir=/data -ip=127.0.0.1 -ip.bind=0.0.0.0 \
  -master.volumeSizeLimitMB=256 -volume.max=0 -filer \
  -s3 -s3.port=8333 -s3.config=/etc/seaweedfs/s3.json \
  -s3.cert.file=$S/seaweedfs-cert.pem -s3.key.file=$S/seaweedfs-key.pem &
PID=$!

# Création du bucket (idempotent), une fois le filer prêt
for i in $(seq 1 60); do
  if echo "s3.bucket.list" | weed shell -master=127.0.0.1:9333 2>/dev/null | grep -q .; then break; fi
  sleep 2
done
echo "s3.bucket.create -name aeger-lake" | weed shell -master=127.0.0.1:9333 >/dev/null 2>&1 || true
echo "[aeger] bucket aeger-lake prêt"
wait $PID
