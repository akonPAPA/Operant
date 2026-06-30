# Release image: unit tests must pass in the verify stage before packaging.
FROM maven:3.9-eclipse-temurin-21 AS verify
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -q -B test

FROM verify AS package
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app --home-dir /app --shell /usr/sbin/nologin app
COPY --from=package --chown=app:app /workspace/target/core-api-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8080
USER app
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
