# ---- build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
# Cache dependencies first for faster rebuilds.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

# ---- runtime stage ----
# Alpine JRE: a musl base with a minimal package set, so the image carries far fewer
# OS-level CVEs than the Ubuntu (jammy) JRE image. The floating tag also picks up the
# latest patched Alpine on every rebuild.
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# openssh-client: real deploy runs SSH into the jump host to invoke ./run.sh.
RUN apk add --no-cache openssh-client
# Run as an unprivileged, fixed-UID user (matches the k8s runAsUser).
RUN addgroup -g 1000 nagad && adduser -u 1000 -G nagad -D nagad
COPY --from=build /app/target/deploy-console-*.jar app.jar
USER 1000
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseZGC"
# Container-friendly: honours cgroup memory limits, graceful shutdown handled by Spring.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
