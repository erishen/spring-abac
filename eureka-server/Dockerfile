FROM eclipse-temurin:17-jre
WORKDIR /app
COPY eureka-server/target/*.jar app.jar
EXPOSE 8762
ENTRYPOINT ["java", "-Xmx256m", "-jar", "/app/app.jar"]
