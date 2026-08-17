# Agent runtime class closure (Slice 0.1)

Машинная форма: [`agent-runtime-class-closure.json`](agent-runtime-class-closure.json).

## Метод

1. Прямые `import space.br1440.*` production-кода `platform-tracing-otel-javaagent-extension`.
2. Транзитивное замыкание импортированных типов по source imports (не bytecode constant pool — generator gate добавляется в Slice 0.4).
3. Consumer dimension: AGENT / APPLICATION / BOTH.
4. Классификация: `SHARED_AGENT_APPLICATION` | `APPLICATION_ONLY` | `ACCIDENTAL` | `KEEP`.
5. Dynamic ledger — отдельный YAML.

Hard assertions на approved Agent closure из tracing-otel:

```text
0 org.slf4j
0 org.slf4j.MDC
0 org.springframework
0 ch.qos.logback
0 reactor
0 jakarta.servlet
```

## Итог классификации

| Origin | Classes | Agent-needed | Placement |
|---|---:|---:|---|
| tracing-api | 61 | subset (attributes, span, SPI, propagation control API) | KEEP in api |
| common-util | 5 | StringUtils, MapUtils, ArrayUtils | KEEP in common-util |
| tracing-otel sampling | 22 | all (engine/model/policy/properties) | MOVE → runtime-policy |
| tracing-otel versioned | 2 | VersionedState (+ holder as companion) | MOVE → runtime-policy |
| tracing-otel remote mirror | 2 | RemoteServiceTraceContext + Mirror | MOVE → runtime-policy |
| tracing-otel propagation.control + RequestIdSupport | 8 | yes | MOVE → runtime-policy |
| tracing-otel remainder | 53 | 0 | APPLICATION_ONLY, stay in otel |

`platform-tracing-agent-runtime` не создаётся: AGENT_ONLY pure types отсутствуют.

## Residual logging surface

```yaml
loggingClosure:
  directSlf4jCallSites: 0
  immutableResidualSlf4jCallSites: 0
  thirdPartySlf4jCallSites: 0
```

Typed path only: `PlatformAgentLogger` → `AgentLoggingRuntime` → stderr sink.
Third-party SLF4J в approved closure отсутствует. Оси `JUL_DIRECT` / residual adapter удалены.
