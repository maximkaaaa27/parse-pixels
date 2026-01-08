# 1. Сборочный образ
FROM gradle:8.10-jdk21 AS builder
WORKDIR /app
COPY . .
RUN gradle clean build --no-daemon

# 2. Минимальный образ для запуска
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /app/build/libs/parse-pixels.jar app.jar
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]