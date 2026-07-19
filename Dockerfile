# 构建阶段（Debian Maven；仅 build 容器，不进入最终镜像）

FROM maven:3.9-eclipse-temurin-8 AS build

WORKDIR /build

COPY pom.xml .

COPY src ./src

COPY data ./data

RUN mvn -q -B -DskipTests package



# 运行阶段：Alpine JRE（较 jammy 更小）

FROM eclipse-temurin:8-jre-alpine

WORKDIR /app

RUN adduser -D -u 1001 appuser && chown -R appuser /app

COPY --from=build /build/target/rag-study-helper-0.0.1-SNAPSHOT.jar app.jar

COPY --from=build /build/data ./data

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-jar", "app.jar"]
