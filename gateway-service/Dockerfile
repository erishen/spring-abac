FROM eclipse-temurin:17-jre
WORKDIR /app
COPY gateway-service/target/*.jar app.jar
EXPOSE 4110
ENTRYPOINT ["java", "-Xmx256m", "-jar", "/app/app.jar"]
