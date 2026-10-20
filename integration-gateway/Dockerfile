FROM eclipse-temurin:27-jdk AS build
WORKDIR /workspace
COPY . .
RUN ./mvnw -pl integration-gateway -am -DskipTests package

FROM eclipse-temurin:27-jre
WORKDIR /app
COPY --from=build /workspace/integration-gateway/target/integration-gateway-*.jar app.jar
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system app \
    && useradd --system --uid 10001 --gid app app
USER 10001:app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
