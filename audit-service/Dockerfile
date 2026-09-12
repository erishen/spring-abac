FROM eclipse-temurin:17-jre
WORKDIR /app
COPY audit-service/target/*.jar app.jar
EXPOSE 4114
ENTRYPOINT ["java", "-Xmx256m", "-jar", "/app/app.jar"]
