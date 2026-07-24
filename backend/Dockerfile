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
# Run as an unprivileged user.
RUN groupadd -r nagad && useradd -r -g nagad nagad
COPY --from=build /app/target/deploy-console-*.jar app.jar
USER nagad
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseZGC"
# Container-friendly: honours cgroup memory limits, graceful shutdown handled by Spring.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
