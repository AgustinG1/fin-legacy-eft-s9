FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml ./
COPY batch/pom.xml batch/pom.xml
COPY batch/src batch/src
RUN mvn -B -f batch/pom.xml -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN mkdir -p /app/output && chown -R 10001:10001 /app
COPY --from=build --chown=10001:10001 /build/batch/target/batch-0.1.0-SNAPSHOT.jar app.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "app.jar"]
