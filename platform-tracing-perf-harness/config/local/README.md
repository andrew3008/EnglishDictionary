# Local smoke tier (PR-9H-D)

## Two-terminal flow (recommended)

```text
# Terminal 1
./gradlew :platform-tracing-perf-harness:bootRun -PperfProfiles=smoke

# Terminal 2 — local k6
./gradlew :platform-tracing-perf-harness:perfSmoke

# Terminal 2 — Docker k6 (local Docker Desktop)
./gradlew :platform-tracing-perf-harness:perfSmoke -PperfSmokeUseDocker=true

# Terminal 2 — remote Docker lab (Gentoo)
./gradlew :platform-tracing-perf-harness:perfSmoke -PperfSmokeUseDocker=true \
  -PDockerHost=tcp://192.168.100.70:2375 \
  -PperfBaseUrl=http://<windows-host-ip-from-gentoo>:8080
```

PowerShell alternative:

```powershell
.\platform-tracing-perf-harness\scripts\run-perf-smoke.ps1 `
  -DockerHost tcp://192.168.100.70:2375 `
  -BaseUrl http://<windows-host-ip-from-gentoo>:8080
```

## OTLP for smoke

Use no-op/logging exporter via standard OTel agent env vars, or a local collector with bounded queue.
SMOKE sink is **non-authoritative**.

## Artifacts

`build/perf-results/smoke/<timestamp>/`:

- `summary.json` — tier-stamped (`evidenceTier: SMOKE`, `w004Eligible: false`)
- `k6-summary.json` — k6 export (when run succeeds)
- `command.txt` — reproducibility metadata

Never commit `build/` output. Never place under `evidence/reference/`.
