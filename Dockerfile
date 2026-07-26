# syntax=docker/dockerfile:1
FROM maven:3.9.14-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre
LABEL org.opencontainers.image.source="https://github.com/atomhuck/repethelper"
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system repethelper \
    && useradd --system --gid repethelper --home-dir /app --shell /usr/sbin/nologin repethelper \
    && mkdir -p /app/uploads \
    && chown -R repethelper:repethelper /app
COPY --from=build --chown=repethelper:repethelper /workspace/target/repethelper-*.jar app.jar
USER repethelper
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
