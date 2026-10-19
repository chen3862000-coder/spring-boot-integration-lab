FROM eclipse-temurin:27-jdk AS build
WORKDIR /workspace
COPY . .
RUN ./mvnw -pl integration-gateway -am -DskipTests package

FROM eclipse-temurin:27-jre
WORKDIR /app
COPY --from=build /workspace/integration-gateway/target/integration-gateway-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
