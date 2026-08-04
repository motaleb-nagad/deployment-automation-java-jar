# ---- build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
# Corporate build servers reach the internet only through an HTTP proxy, which Jenkins passes
# as these build-args. Maven does NOT read HTTP(S)_PROXY env vars, so translate them into a
# ~/.m2/settings.xml <proxies> block; without this `go-offline` hits Maven Central directly and
# fails DNS on an offline build node. No proxy arg -> direct network (unchanged local builds).
ARG HTTP_PROXY
ARG HTTPS_PROXY
ARG NO_PROXY
WORKDIR /app
RUN set -eu; \
    PROXY="${HTTPS_PROXY:-${HTTP_PROXY:-}}"; \
    if [ -n "$PROXY" ]; then \
      HP="$(printf '%s' "$PROXY" | sed -E 's#^[a-zA-Z]+://##; s#/.*$##')"; \
      PHOST="${HP%:*}"; PPORT="${HP##*:}"; \
      case "$PPORT" in ''|*[!0-9]*) PPORT=8080 ;; esac; \
      NPH="$(printf '%s' "${NO_PROXY:-localhost,127.0.0.1}" | sed 's/,/|/g')"; \
      mkdir -p /root/.m2; \
      { \
        echo "<settings><proxies>"; \
        echo "<proxy><id>corp-http</id><active>true</active><protocol>http</protocol><host>${PHOST}</host><port>${PPORT}</port><nonProxyHosts>${NPH}</nonProxyHosts></proxy>"; \
        echo "<proxy><id>corp-https</id><active>true</active><protocol>https</protocol><host>${PHOST}</host><port>${PPORT}</port><nonProxyHosts>${NPH}</nonProxyHosts></proxy>"; \
        echo "</proxies></settings>"; \
      } > /root/.m2/settings.xml; \
      echo "[build] maven proxy -> ${PHOST}:${PPORT} (nonProxy: ${NPH})"; \
    else \
      echo "[build] no proxy build-arg set; using direct network"; \
    fi
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
