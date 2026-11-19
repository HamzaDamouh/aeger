# Vault Agent de vm-hot-03 (généré par Services/securite/vault-agent/gen_configs.py)
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

# Certificat mTLS « vitals » (30 jours, renouvelé par l'agent) :
#   vitals.pem = clé + certificat + CA ; vitals-key.pem et vitals-cert.pem séparés pour NGINX, mosquitto, PostgreSQL…
template {
  destination = "/opt/aeger/secrets/vitals.pem"
  perms       = "0640"
  contents    = <<EOT
{{- with pkiCert "pki/issue/vitals" "common_name=vitals" "alt_names=vm-hot-03,localhost" "ip_sans=10.0.30.13,127.0.0.1" "private_key_format=pkcs8" -}}
{{ .Key }}
{{ .Cert }}
{{ .CA }}
{{ .Key | writeToFile "/opt/aeger/secrets/vitals-key.pem" "" "" "0640" }}
{{ printf "%s\n%s\n" .Cert .CA | writeToFile "/opt/aeger/secrets/vitals-cert.pem" "" "" "0640" }}
{{- end }}
EOT
}

# Secrets « vitals » -> vitals.env (CLE=valeur)
template {
  destination = "/opt/aeger/secrets/vitals.env"
  perms       = "0640"
  contents    = <<EOT
{{ with secret "kv/data/aeger/vitals" }}{{ range $key, $v := .Data.data }}{{ $key }}={{ $v }}
{{ end }}{{ end }}
EOT
}

# Clé publique du realm Keycloak : les services vérifient les jetons OIDC hors ligne
template {
  destination = "/opt/aeger/secrets/keycloak-realm.pem"
  perms       = "0644"
  contents    = "{{ with secret \"kv/data/aeger/realm\" }}{{ .Data.data.public_key }}{{ end }}"
}
