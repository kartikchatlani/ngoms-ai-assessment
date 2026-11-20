# ---- build stage: full JDK + Maven wrapper -------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Dependencies first, so source-only changes reuse the cached dependency layer.
COPY .mvn .mvn
COPY mvnw pom.xml ./
# Normalise line endings in case the repo was checked out on Windows with CRLF.
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src src
# Tests run in CI / locally (they need Docker for Testcontainers, which is not available here).
RUN ./mvnw -B -q -DskipTests package && cp target/url-shortener-*.jar /src/app.jar

# ---- runtime stage: JRE only, non-root ----------------------------------------------------
FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --uid 10001 app
WORKDIR /app
COPY --from=build /src/app.jar app.jar
USER 10001
EXPOSE 8080
# Size the heap from the container memory limit rather than the host's RAM.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
