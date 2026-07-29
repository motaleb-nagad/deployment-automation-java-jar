# ---- build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
# Cache dependencies first for faster rebuilds.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

# ---- runtime stage ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# openssh-client: real staging runs SSH into the jump host to invoke ./run.sh and stage uploads.
RUN apk add --no-cache openssh-client
# Run as an unprivileged, fixed-UID user (matches the k8s runAsUser).
RUN addgroup -g 1000 nagad && adduser -u 1000 -G nagad -D nagad
COPY --from=build /app/target/deploy-console-stg-*.jar app.jar
USER 1000
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseZGC"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
