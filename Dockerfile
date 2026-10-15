FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B -ntp package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --create-home app && chown app:app /app
USER app
COPY --from=build /build/target/yangbin-ai-agent-0.0.1-SNAPSHOT.jar /app/app.jar
ENV SERVER_ADDRESS=0.0.0.0 SPRING_PROFILES_ACTIVE=prod
EXPOSE 8123
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
