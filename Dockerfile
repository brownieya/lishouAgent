FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN mkdir -p /app/data/chat-memory && chown -R 10001:10001 /app
COPY --from=build --chown=10001:10001 /workspace/target/lishou-agent-0.1.0-SNAPSHOT.jar /app/app.jar
USER 10001:10001
EXPOSE 8124
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
