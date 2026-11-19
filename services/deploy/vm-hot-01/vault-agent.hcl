# Vault Agent de vm-hot-01 (généré par Services/securite/vault-agent/gen_configs.py)
pid_file = "/tmp/vault-agent.pid"

vault {
  address = "https://vm-sec-01:8200"
  ca_cert = "/opt/aeger/vault-agent/ca.pem"
}

auto_auth {
  method "approle" {
    config = {
      role_id_file_path                   = "/opt/aeger/vault-agent/role_id"
      secret_id_file_path                 = "/opt/aeger/vault-agent/secret_id"
      remove_secret_id_file_after_reading = false
    }
  }
}

template_config {
  static_secret_render_interval = "2m"
  exit_on_retry_failure         = false
}

# Certificat de l'autorité AEGER
template {
  destination = "/opt/aeger/secrets/ca.pem"
  perms       = "0644"
  contents    = "{{ with secret \"pki/cert/ca\" }}{{ .Data.certificate }}{{ end }}\n"
}

# Certificat mTLS « mosquitto » (30 jours, renouvelé par l'agent) :
#   mosquitto.pem = clé + certificat + CA ; mosquitto-key.pem et mosquitto-cert.pem séparés pour NGINX, mosquitto, PostgreSQL…
template {
  destination = "/opt/aeger/secrets/mosquitto.pem"
  perms       = "0640"
  contents    = <<EOT
{{- with pkiCert "pki/issue/mosquitto" "common_name=mosquitto" "alt_names=vm-hot-01,localhost,aeger.local" "ip_sans=10.0.30.11,127.0.0.1,172.31.249.218" "private_key_format=pkcs8" -}}
{{ .Key }}
{{ .Cert }}
{{ .CA }}
{{ .Key | writeToFile "/opt/aeger/secrets/mosquitto-key.pem" "" "" "0640" }}
{{ printf "%s\n%s\n" .Cert .CA | writeToFile "/opt/aeger/secrets/mosquitto-cert.pem" "" "" "0640" }}
{{- end }}
EOT
}

# Certificat mTLS « ingestion » (30 jours, renouvelé par l'agent) :
#   ingestion.pem = clé + certificat + CA ; ingestion-key.pem et ingestion-cert.pem séparés pour NGINX, mosquitto, PostgreSQL…
template {
  destination = "/opt/aeger/secrets/ingestion.pem"
  perms       = "0640"
  contents    = <<EOT
{{- with pkiCert "pki/issue/ingestion" "common_name=ingestion" "alt_names=vm-hot-01,localhost" "ip_sans=10.0.30.11,127.0.0.1" "private_key_format=pkcs8" -}}
{{ .Key }}
{{ .Cert }}
{{ .CA }}
{{ .Key | writeToFile "/opt/aeger/secrets/ingestion-key.pem" "" "" "0640" }}
{{ printf "%s\n%s\n" .Cert .CA | writeToFile "/opt/aeger/secrets/ingestion-cert.pem" "" "" "0640" }}
{{- end }}
EOT
}
