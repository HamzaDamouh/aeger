# Vault (VM-SEC-01) : autorité de certification (PKI) + coffre à secrets (KV)
FROM hashicorp/vault:1.18

# Le script d'entrée choisit la configuration (avec ou sans écoute TLS sur 8200)
# et déverrouille Vault au démarrage (clé stockée sur la VM : simplification de TP).
COPY entrypoint.sh /usr/local/bin/aeger-vault-entrypoint.sh
COPY bootstrap.sh /usr/local/bin/aeger-vault-bootstrap.sh
RUN chmod +x /usr/local/bin/aeger-vault-entrypoint.sh /usr/local/bin/aeger-vault-bootstrap.sh \
 && apk add --no-cache jq openssl curl python3

ENV VAULT_ADDR=http://127.0.0.1:8201
EXPOSE 8200
ENTRYPOINT ["/usr/local/bin/aeger-vault-entrypoint.sh"]
