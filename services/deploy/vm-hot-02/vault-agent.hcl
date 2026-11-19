# Vault Agent de vm-hot-02 (généré par Services/securite/vault-agent/gen_configs.py)
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

# Certificat mTLS « alerting » (30 jours, renouvelé par l'agent) :
#   alerting.pem = clé + certificat + CA ; alerting-key.pem et alerting-cert.pem séparés pour NGINX, mosquitto, PostgreSQL…
template {
  destination = "/opt/aeger/secrets/alerting.pem"
  perms       = "0640"
  contents    = <<EOT
{{- with pkiCert "pki/issue/alerting" "common_name=alerting" "alt_names=vm-hot-02,localhost" "ip_sans=10.0.30.12,127.0.0.1" "private_key_format=pkcs8" -}}
{{ .Key }}
{{ .Cert }}
{{ .CA }}
{{ .Key | writeToFile "/opt/aeger/secrets/alerting-key.pem" "" "" "0640" }}
{{ printf "%s\n%s\n" .Cert .CA | writeToFile "/opt/aeger/secrets/alerting-cert.pem" "" "" "0640" }}
{{- end }}
EOT
}

# Certificat mTLS « notification » (30 jours, renouvelé par l'agent) :
#   notification.pem = clé + certificat + CA ; notification-key.pem et notification-cert.pem séparés pour NGINX, mosquitto, PostgreSQL…
template {
  destination = "/opt/aeger/secrets/notification.pem"
  perms       = "0640"
  contents    = <<EOT
{{- with pkiCert "pki/issue/notification" "common_name=notification" "alt_names=vm-hot-02,localhost" "ip_sans=10.0.30.12,127.0.0.1" "private_key_format=pkcs8" -}}
{{ .Key }}
{{ .Cert }}
{{ .CA }}
{{ .Key | writeToFile "/opt/aeger/secrets/notification-key.pem" "" "" "0640" }}
{{ printf "%s\n%s\n" .Cert .CA | writeToFile "/opt/aeger/secrets/notification-cert.pem" "" "" "0640" }}
{{- end }}
EOT
}

# Clé publique du realm Keycloak : les services vérifient les jetons OIDC hors ligne
template {
  destination = "/opt/aeger/secrets/keycloak-realm.pem"
  perms       = "0644"
  contents    = "{{ with secret \"kv/data/aeger/realm\" }}{{ .Data.data.public_key }}{{ end }}"
}
