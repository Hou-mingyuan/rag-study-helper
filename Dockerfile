FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /build

COPY .mvn .mvn
COPY pom.xml .
COPY src src
RUN --mount=type=cache,id=rag-study-helper-maven,target=/root/.m2,sharing=locked \
    mvn -gs .mvn/settings-central.xml -s .mvn/settings-central.xml \
        -B -DproductionBuild=true -Dmaven.test.skip=true package

FROM eclipse-temurin:17-jre-alpine

RUN apk upgrade --no-cache \
    && addgroup -S app \
    && adduser -S -G app -u 10001 app

WORKDIR /app

COPY --from=build --chown=app:app /build/target/rag-study-helper-2.0.0-RC1.jar app.jar
COPY --chown=app:app data/docs data/docs
RUN mkdir -p data/inbox && chown -R app:app /app

USER app

ENV SERVER_PORT=8080
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=240s --retries=5 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/api/readiness || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-Djava.io.tmpdir=/tmp", "-jar", "/app/app.jar"]
