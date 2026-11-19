# ---- Étape 1 : compilation (Maven + JDK 21) ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Couche des dépendances : reconstruite seulement si pom.xml change
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
# Tests unitaires des seuils (rapides)
RUN mvn -B -q test && cat target/surefire-reports/*.txt
RUN mvn -B -q -DskipTests package

# ---- Étape 2 : exécution (JRE 21 seul) ----
FROM eclipse-temurin:21-jre-alpine
# Utilisateur non root, UID/GID fixes (10001) : les fichiers de /opt/aeger/secrets doivent lui être lisibles
RUN addgroup -S -g 10001 aeger && adduser -S -D -H -u 10001 -G aeger aeger
WORKDIR /app
COPY --from=build /build/target/app.jar app.jar
ENV SERVICE_NAME=alerting \
    AEGER_SECRETS_DIR=/opt/aeger/secrets \
    JAVA_OPTS="-Xms64m -Xmx192m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k"
USER 10001:10001
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
