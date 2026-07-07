# k6-образ перф-стенда: сценарии запекаются в образ (COPY, не volume —
# ограничение remote Docker daemon).
FROM grafana/k6:0.57.0

USER root
COPY scenarios /scenarios
RUN mkdir -p /perf-artifacts && chown -R 12345:12345 /perf-artifacts
USER 12345

ENTRYPOINT ["k6"]
CMD ["run", "/scenarios/steady-state.js", "--summary-export", "/perf-artifacts/k6-summary.json"]
