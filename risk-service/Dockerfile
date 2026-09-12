FROM eclipse-temurin:17-jre
WORKDIR /app
COPY risk-service/target/*.jar app.jar
EXPOSE 4115
ENTRYPOINT ["java", "-Xmx256m", "-jar", "/app/app.jar"]
