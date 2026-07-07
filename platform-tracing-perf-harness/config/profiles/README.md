# Scenario profiles (S0–S6) — PR-9H-D refined

Spring profile names map to `src/main/resources/application-<profile>.yaml`.
Activate with `-PperfProfiles=<profile>` on `bootRun` or `SPRING_PROFILES_ACTIVE`.

| ID | Spring profile | Purpose | Smoke endpoints | PR-9H-D |
|----|----------------|---------|-----------------|---------|
| S0 | `s0-baseline` | Baseline control: tracing disabled | health, fast | Runnable |
| S1 | `s1-tracing` | Tracing on, validation disabled | health, fast, work | Runnable |
| S2 | `s2-validation-lenient-valid` | Lenient validation, valid spans | + validation/valid | Default smoke |
| S3 | `s3-validation-lenient-missing` | Lenient missing-attribute path | + validation/missing | Runnable |
| S4 | `s4-production` | Sampling + scrubbing + validation | all `/perf/*` | Runnable |
| S5 | `s5-strict-diagnostic` | Strict diagnostic (isolated) | validation/* only | Diagnostic-only |
| S6 | `s6-soak` | Long soak window | S4 mix | Future PR-9H-F |

**Default smoke profile:** `smoke` (alias for S2-style lenient validation).

k6 env: `SPRING_PROFILE` tag only — SUT must be started with matching `-PperfProfiles=...`.

Example two-terminal flow:

```text
./gradlew :platform-tracing-perf-harness:bootRun -PperfProfiles=smoke
./gradlew :platform-tracing-perf-harness:perfSmoke -PperfSmokeUseDocker=true -PDockerHost=tcp://192.168.100.70:2375 -PperfBaseUrl=http://<host-from-gentoo>:8080
```

SMOKE artifacts are **non-authoritative** and cannot close W-004.
