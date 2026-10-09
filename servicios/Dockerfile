FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY servicios/ servicios/
RUN --mount=type=cache,target=/root/.m2 mvn -B -f servicios/pom.xml -DskipTests package
RUN javac -d /checks servicios/docker/Healthcheck.java

FROM eclipse-temurin:17-jre
ARG MODULE
WORKDIR /app
RUN mkdir -p /app/keys && chown -R 10001:10001 /app
COPY --from=build --chown=10001:10001 /src/servicios/${MODULE}/target/${MODULE}-0.1.0-SNAPSHOT.jar /app/app.jar
COPY --from=build /checks/Healthcheck.class /app/Healthcheck.class
USER 10001:10001
HEALTHCHECK --interval=10s --timeout=5s --start-period=45s --retries=12 CMD ["java","-cp","/app","Healthcheck"]
ENTRYPOINT ["java","-XX:MaxRAMPercentage=65","-jar","/app/app.jar"]
