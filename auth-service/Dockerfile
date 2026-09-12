FROM eclipse-temurin:17-jre
WORKDIR /app
COPY auth-service/target/*.jar app.jar
EXPOSE 4111
ENTRYPOINT ["java", "-Xmx256m", "-jar", "/app/app.jar"]
