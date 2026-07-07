# Local reference sink image (PR-9H-E2L)

Test-only OTLP HTTP sink for local lab pipeline wiring. Uses `debug` exporter — **not** authoritative for W-004 backpressure (`backpressureEvidenceValid=false` until SRE sink).

Build on Gentoo:

```bash
docker build -t local-reference-sink:lab docker/local-reference-sink
kind load docker-image local-reference-sink:lab --name platform-tracing-reference-lab
```

Record `missingMetrics: collector.backpressureEvidence` if queue evidence is not validated.
