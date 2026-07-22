# Perf harness SUT for local/reference lab (PR-9H-E2L).
# Build context: preparePerfAppDockerContext (bootJar + javaagent + extension via COPY).
FROM eclipse-temurin:21-jre

RUN apt-get update && apt-get install -y --no-install-recommends wget && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY sut-app.jar /app/sut-app.jar
COPY opentelemetry-javaagent.jar /otel/opentelemetry-javaagent.jar
COPY platform-tracing-otel-javaagent-extension-agent.jar /otel/platform-tracing-otel-javaagent-extension-agent.jar

ENV PERF_BASE_JVM_OPTS="-Xms256m -Xmx512m"
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $PERF_BASE_JVM_OPTS $PERF_SCENARIO_JVM_OPTS -jar /app/sut-app.jar"]
