# 构建
FROM maven:3.8-eclipse-temurin-8 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn package -DskipTests -B

# 运行
FROM eclipse-temurin:8
WORKDIR /app
COPY --from=build /build/target/rag-study-helper-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
