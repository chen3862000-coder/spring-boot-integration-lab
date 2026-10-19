FROM eclipse-temurin:27-jdk AS build
WORKDIR /workspace
COPY . .
RUN ./mvnw -pl mock-partner -am -DskipTests package

FROM eclipse-temurin:27-jre
WORKDIR /app
COPY --from=build /workspace/mock-partner/target/mock-partner-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
