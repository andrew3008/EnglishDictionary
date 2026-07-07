# Collector-образ перф-стенда: конфиг запекается в образ (COPY, не volume —
# ограничение remote Docker daemon). Пин версии = пин contrib Track B+ (ADR-collector-boundary).
FROM otel/opentelemetry-collector-contrib:0.154.0

COPY otel-collector-perf.yaml /etc/otelcol-contrib/config.yaml
