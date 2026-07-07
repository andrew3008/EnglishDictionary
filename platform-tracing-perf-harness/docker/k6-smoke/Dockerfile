FROM grafana/k6:0.57.0

USER root
COPY scenarios /scenarios
RUN mkdir -p /artifacts && chown -R 12345:12345 /artifacts
USER 12345

ENTRYPOINT ["k6"]
