# SPDX-License-Identifier: AGPL-3.0-only
# Single image: the Spring Boot backend serves the built SPA. Build from the repository root:
#   docker build -f deploy/Dockerfile -t honestrobin-time .

FROM docker.io/library/node:24-alpine AS web
WORKDIR /src
RUN corepack enable
COPY package.json pnpm-workspace.yaml pnpm-lock.yaml ./
COPY frontend/package.json frontend/
COPY packages/api-client/package.json packages/api-client/
RUN pnpm install --frozen-lockfile --filter @honestrobin/web...
COPY packages/api-client packages/api-client
COPY frontend frontend
RUN pnpm --filter @honestrobin/web build

FROM docker.io/library/eclipse-temurin:25-jdk AS backend
WORKDIR /src
COPY gradlew settings.gradle.kts gradle.properties ./
COPY gradle gradle
COPY backend/build.gradle.kts backend/
RUN ./gradlew --no-daemon -q :backend:dependencies > /dev/null
COPY backend backend
COPY extension/src/selectors.json extension/src/selectors.json
COPY --from=web /src/frontend/dist frontend/dist
ARG HONESTROBIN_VERSION=0.1.0-SNAPSHOT
RUN ./gradlew --no-daemon :backend:bootJar -PhonestrobinVersion=${HONESTROBIN_VERSION} -x test

# Java 25: since Java 24, virtual threads no longer pin their carrier inside synchronized code,
# which on Java 21 could stall every request on a small machine (decision records 0007, 0018).
FROM docker.io/library/eclipse-temurin:25-jre AS runtime
RUN groupadd --system --gid 10001 honestrobin \
 && useradd --system --uid 10001 --gid honestrobin --home /data honestrobin \
 && mkdir -p /data \
 && chown honestrobin:honestrobin /data
WORKDIR /app
USER honestrobin
ENV HONESTROBIN_SECRETS_KEY_FILE=/data/secrets.key \
    HONESTROBIN_STORAGE_PATH=/data/files \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
VOLUME ["/data"]
EXPOSE 8080
LABEL org.opencontainers.image.title="Honest Robin: Time" \
      org.opencontainers.image.licenses="AGPL-3.0-only" \
      org.opencontainers.image.source="https://github.com/honestrobin/time"
ENTRYPOINT ["java", "--sun-misc-unsafe-memory-access=allow", "-jar", "/app/honestrobin-time.jar"]

# CI only: a jar built once outside Docker, with backend/build/libs as the build context. The jar
# runs on any architecture, so the arm64 image needs no compiling under emulation.
FROM runtime AS prebuilt
COPY honestrobin-time.jar /app/honestrobin-time.jar

# The default: the jar built from source above.
FROM runtime
COPY --from=backend /src/backend/build/libs/honestrobin-time.jar /app/honestrobin-time.jar
