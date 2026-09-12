FROM eclipse-temurin:17-jre
WORKDIR /app
COPY abac-service/target/*.jar app.jar
EXPOSE 4112
ENTRYPOINT ["java", "-Xmx256m", "-jar", "/app/app.jar"]
