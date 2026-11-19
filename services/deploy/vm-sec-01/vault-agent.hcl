# Vault Agent de vm-sec-01 (généré par Services/securite/vault-agent/gen_configs.py)
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

# Certificat mTLS « keycloak » (30 jours, renouvelé par l'agent) :
#   keycloak.pem = clé + certificat + CA ; keycloak-key.pem et keycloak-cert.pem séparés pour NGINX, mosquitto, PostgreSQL…
template {
  destination = "/opt/aeger/secrets/keycloak.pem"
  perms       = "0640"
  contents    = <<EOT
{{- with pkiCert "pki/issue/keycloak" "common_name=keycloak" "alt_names=vm-sec-01,localhost" "ip_sans=10.0.20.11,127.0.0.1" "private_key_format=pkcs8" -}}
{{ .Key }}
{{ .Cert }}
{{ .CA }}
{{ .Key | writeToFile "/opt/aeger/secrets/keycloak-key.pem" "" "" "0640" }}
{{ printf "%s\n%s\n" .Cert .CA | writeToFile "/opt/aeger/secrets/keycloak-cert.pem" "" "" "0640" }}
{{- end }}
EOT
}

# Secrets « keycloak » -> keycloak.env (CLE=valeur)
template {
  destination = "/opt/aeger/secrets/keycloak.env"
  perms       = "0640"
  contents    = <<EOT
{{ with secret "kv/data/aeger/keycloak" }}{{ range $key, $v := .Data.data }}{{ $key }}={{ $v }}
{{ end }}{{ end }}
EOT
}
