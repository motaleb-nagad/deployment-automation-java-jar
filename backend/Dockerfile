# ---- build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
# Cache dependencies first for faster rebuilds.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

# ---- runtime stage ----
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
# openssh-client: real deploy runs SSH into the jump host to invoke ./run.sh.
RUN apt-get update \
    && apt-get install -y --no-install-recommends openssh-client \
    && rm -rf /var/lib/apt/lists/*
# Run as an unprivileged, fixed-UID user (matches the k8s runAsUser).
RUN groupadd -g 1000 nagad && useradd -u 1000 -g nagad -m nagad
COPY --from=build /app/target/deploy-console-*.jar app.jar
USER 1000
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseZGC"
# Container-friendly: honours cgroup memory limits, graceful shutdown handled by Spring.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
