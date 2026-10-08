# One Dockerfile for every Kotlin service. Build with: --build-arg SERVICE=user-service
FROM eclipse-temurin:21-jdk AS build
ARG SERVICE
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew --no-daemon -q :services:${SERVICE}:bootJar -x test \
 && cp services/${SERVICE}/build/libs/${SERVICE}.jar /app.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 1001 app
USER app
COPY --from=build /app.jar /app.jar
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app.jar"]
