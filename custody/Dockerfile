# ─── Stage 1: Build ───────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /workspace

# Gradle wrapper + build scripts (캐시 레이어 분리)
COPY gradlew gradlew.bat settings.gradle build.gradle ./
COPY gradle ./gradle
RUN ./gradlew dependencies --no-daemon -q 2>/dev/null || true

# 소스 복사 후 bootJar
COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test -q

# ─── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S custody && adduser -S custody -G custody
WORKDIR /app

COPY --from=builder /workspace/build/libs/*.jar app.jar

RUN chown custody:custody app.jar
USER custody

EXPOSE 8080

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
