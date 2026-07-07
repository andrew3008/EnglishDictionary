# SUT-образ для перф-прогонов (Фаза 17, PR-3).
# Build context собирается задачей :platform-tracing-perf-tests:prepareSutDockerContext
# (bootJar + javaagent + extension попадают в образ через COPY: volume mounts на
# удалённый Docker daemon (Gentoo) не работают с Windows-путей).
FROM eclipse-temurin:21-jre

# wget нужен для healthcheck compose (actuator/health).
RUN apt-get update && apt-get install -y --no-install-recommends wget && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY sut-app.jar /app/sut-app.jar
COPY opentelemetry-javaagent.jar /otel/opentelemetry-javaagent.jar
COPY platform-tracing-otel-extension-agent.jar /otel/platform-tracing-otel-extension-agent.jar

# Конфигурация сценария (наличие агента, ratio, endpoint, JFR) передаётся через
# JAVA_TOOL_OPTIONS из docker-compose env-файла сценария — образ один для всех Mx.
EXPOSE 8080

# Базовые JVM-флаги, одинаковые для ВСЕХ сценариев: фиксированный heap (RSS-сравнимость),
# GC-логи в /perf-artifacts (забираются docker cp).
ENV PERF_BASE_JVM_OPTS="-Xms512m -Xmx512m -Xlog:gc*:file=/perf-artifacts/gc.log:time,uptime:filecount=2,filesize=50m"

RUN mkdir -p /perf-artifacts

ENTRYPOINT ["sh", "-c", "exec java $PERF_BASE_JVM_OPTS $PERF_SCENARIO_JVM_OPTS -jar /app/sut-app.jar"]
