FROM eclipse-temurin:21-jre-alpine
RUN addgroup -g 10001 -S app && adduser -u 10001 -S -D -H -G app app
WORKDIR /app
COPY target/job-scheduler-keda-demo-*.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
