# Vault Agent de vm-cold-03 (généré par Services/securite/vault-agent/gen_configs.py)
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

# Certificat mTLS « spark » (30 jours, renouvelé par l'agent) :
#   spark.pem = clé + certificat + CA ; spark-key.pem et spark-cert.pem séparés pour NGINX, mosquitto, PostgreSQL…
template {
  destination = "/opt/aeger/secrets/spark.pem"
  perms       = "0640"
  contents    = <<EOT
{{- with pkiCert "pki/issue/spark" "common_name=spark" "alt_names=vm-cold-03,localhost" "ip_sans=10.0.50.13,127.0.0.1" "private_key_format=pkcs8" -}}
{{ .Key }}
{{ .Cert }}
{{ .CA }}
{{ .Key | writeToFile "/opt/aeger/secrets/spark-key.pem" "" "" "0640" }}
{{ printf "%s\n%s\n" .Cert .CA | writeToFile "/opt/aeger/secrets/spark-cert.pem" "" "" "0640" }}
{{- end }}
EOT
}

# Secrets « spark » -> spark.env (CLE=valeur)
template {
  destination = "/opt/aeger/secrets/spark.env"
  perms       = "0640"
  contents    = <<EOT
{{ with secret "kv/data/aeger/spark" }}{{ range $key, $v := .Data.data }}{{ $key }}={{ $v }}
{{ end }}{{ end }}
EOT
}

# Secrets « kpi » -> kpi.env (CLE=valeur)
template {
  destination = "/opt/aeger/secrets/kpi.env"
  perms       = "0640"
  contents    = <<EOT
{{ with secret "kv/data/aeger/kpi" }}{{ range $key, $v := .Data.data }}{{ $key }}={{ $v }}
{{ end }}{{ end }}
EOT
}

# Secrets « s3 » -> s3.env (CLE=valeur)
template {
  destination = "/opt/aeger/secrets/s3.env"
  perms       = "0640"
  contents    = <<EOT
{{ with secret "kv/data/aeger/s3" }}{{ range $key, $v := .Data.data }}{{ $key }}={{ $v }}
{{ end }}{{ end }}
EOT
}
