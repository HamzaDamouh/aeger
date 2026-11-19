# Keycloak (VM-SEC-01) : connexion des médecins et des analystes (OIDC).
# Image « optimisée » : seules les options de BUILD sont figées ici (base PostgreSQL, chemin /auth).
# Toute la configuration d'exécution (hostname, certificats, base, realm importé) est dans docker-compose.yml.
FROM quay.io/keycloak/keycloak:26.0.7 AS builder
ENV KC_DB=postgres \
    KC_HEALTH_ENABLED=true \
    KC_HTTP_RELATIVE_PATH=/auth
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.0.7
COPY --from=builder /opt/keycloak/ /opt/keycloak/
EXPOSE 8443
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start", "--optimized", "--import-realm"]
