FROM eclipse-temurin:17-jre
WORKDIR /app
COPY agent-service/target/*.jar app.jar
EXPOSE 4116
ENTRYPOINT ["java", "-Xmx256m", "-jar", "/app/app.jar"]
