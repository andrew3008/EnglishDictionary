# Local reference sink — OTLP HTTP receiver with bounded queue (PR-9H-E2L test-only)
FROM otel/opentelemetry-collector-contrib:0.154.0
COPY otel-collector-sink.yaml /etc/otelcol/config.yaml
CMD ["--config=/etc/otelcol/config.yaml"]
