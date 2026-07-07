# Runbook: production-пайплайн OpenTelemetry Collector (Фаза 16)

| Поле | Значение |
|------|----------|
| Аудитория | SRE / on-call / операторы observability |
| Pin | `otel/opentelemetry-collector-contrib:0.154.0` (Track B+, [ADR-collector-boundary](../decisions/ADR-collector-boundary.md)) |
| Конфиги | модуль `platform-tracing-collector-config` (gateway, agent, ttl-tiers) |
| Дата | 2026-06-10 |

---

## 1. Топология: почему два tier'а

```
Apps (SDK + OTel Agent) ──OTLP──► Agent-tier (DaemonSet)        ──► Gateway-tier (Deployment)     ──► Jaeger
                                  memory_limiter                     memory_limiter
                                  → k8sattributes                    → transform/semconv-backstop
                                  → batch                            → redaction/platform-second-line
                                  → load_balancing                   → tail_sampling (6 политик)
                                    (routing_key: traceID)           → batch
                                                                     → otlp (sending_queue + retry)
```

Tail sampling — **stateful**: решение принимается по трейсу целиком, поэтому все span'ы
одного трейса обязаны попадать на один и тот же gateway-инстанс. Это обеспечивает
`load_balancing` exporter на agent-tier с `routing_key: traceID` (официальный CNCF-паттерн).

**Trace affinity caveat:** изменение состава gateway-инстансов (rollout, rescale)
перемещает ~R/N маршрутов — трейсы «в полёте» могут быть разорваны. Митигации:
`decision_cache` (включён), surge-стратегия rollout'а; при частых rescale рассмотреть
`groupbytrace` (ценой RAM/latency).

## 2. Порядок процессоров (инвариант)

| # | Gateway | Зачем |
|---|---------|-------|
| 1 | `memory_limiter` | первый всегда: защита от OOM до любых аллокаций |
| 2 | `transform/platform-semconv-backstop` | backfill `platform.trace.type`, удаление `url.full` (PII) — ДО политик |
| 3 | `redaction/platform-second-line` | 2-я линия маскирования — ДО tail_sampling (нет утечки в decision-кэш/логи) |
| 4 | `tail_sampling` | решения retention |
| 5 | `batch` | последний: амортизация сети |

Agent: `memory_limiter` → `k8sattributes` → `batch`.
Порядок проверяется машинно (`CollectorPolicyContractTest`) — не меняйте его без правки теста и ADR.

## 3. Feature gate

Gateway запускается с:

```
--feature-gates=+processor.tailsamplingprocessor.recordpolicy
```

Каждый sampled span получает атрибут `tailsampling.policy` — аудит, какая политика
сохранила трейс. Используйте для тюнинга процентов и расследования «почему трейс выжил/пропал».

## 4. Env-vars тюнинга (без редеплоя конфига)

Синтаксис в YAML — `${env:VAR:-default}` (легаси `${VAR:default}` ломает contrib ≥ 0.123).

| Переменная | Default | Назначение |
|------------|---------|------------|
| `TAIL_SAMPLING_DECISION_WAIT` | `10s` | окно ожидания полного трейса |
| `TAIL_SAMPLING_NUM_TRACES` | `100000` | трейсов в памяти одновременно |
| `TAIL_SAMPLING_EXPECTED_NEW_TRACES_PER_SEC` | `1000` | подсказка аллокатору |
| `TAIL_SAMPLING_MAX_TRACE_SIZE_BYTES` | `5000000` | защита от гигантских трейсов |
| `TAIL_SAMPLING_SAMPLED_CACHE_SIZE` / `..._NON_SAMPLED_CACHE_SIZE` | `500000` | decision-кэш (late spans) |
| `TAIL_SAMPLING_SLOW_TRACE_THRESHOLD_MS` | `5000` | порог slow-traces |
| `TAIL_SAMPLING_SUCCESS_BASELINE_PERCENT` | `1` | базовый % успешных трейсов |
| `TAIL_SAMPLING_MEMORY_LIMIT_MIB` / `..._SPIKE_LIMIT_MIB` | `1024` / `256` | memory_limiter gateway |
| `AGENT_MEMORY_LIMIT_MIB` / `..._SPIKE_LIMIT_MIB` | `256` / `64` | memory_limiter agent |
| `GATEWAY_HEADLESS_SVC` | `otelcol-gateway-headless...` | DNS-resolver load_balancing |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `jaeger-collector:4317` | backend gateway |
| `GOMEMLIMIT` | — (задать!) | **80% hard-лимита контейнера** (например `1638MiB` при limit 2GiB) |

Процентные/пороговые значения — зона SRE (GitOps); ключи и значения политик
`platform.sampling.reason`/`platform.trace.priority` — контракт SDK, менять только
синхронно с `PlatformSamplingReasons` (упадёт `CollectorPolicyContractTest`).

## 5. Метрики и алерты (Prometheus :8888)

| Метрика | Алерт | Действие |
|---------|-------|----------|
| `otelcol_processor_tail_sampling_sampling_trace_dropped_too_early` | > 0 устойчиво | трейсы вытесняются до решения → поднять `NUM_TRACES` / память |
| `otelcol_processor_tail_sampling_sampling_late_span_age` (histogram) | рост хвоста | span'ы приходят после решения → поднять `DECISION_WAIT` или decision-кэши |
| `otelcol_processor_tail_sampling_count_traces_sampled{policy=...}` | аномалия по policy | аудит политик: какой policy дропает/сохраняет; сверить с recordpolicy |
| `otelcol_processor_tail_sampling_sampling_decision_latency` | p99 ↑ | gateway перегружен → scale out |
| `otelcol_receiver_refused_spans` | > 0 | memory_limiter отбивает приём (429) → память/scale |
| `otelcol_exporter_send_failed_spans` | > 0 устойчиво | backend недоступен/перегружен → retry истощается |
| `otelcol_exporter_queue_size` vs `queue_capacity` | > 80% | давление на экспорт → backend/scale |
| `otelcol_process_memory_rss` vs GOMEMLIMIT | > 90% | риск деградации GC → память/scale |

## 6. Health-check suppression: паттерн Head + Tail (риск R3)

Tail-drop health-трейсов (`drop-successful-infra-noise`) держит их в RAM gateway'я до
`decision_wait`. При высоком QPS probe'ов (большой кластер × частые liveness) это
заметная доля `num_traces`.

**Рекомендация:**
1. **Head (первичный):** на SDK задать `platform.tracing.sampling.drop-paths=/actuator/health,/health,/ready,/live,/metrics`
   — health-span'ы не покидают приложение вообще (`HardDropRule`, reason `drop_path`).
2. **Tail (backstop):** политика `drop-successful-infra-noise` остаётся для сервисов
   без SDK-настройки. ERROR health-check'и сохраняются всегда (not-error sub-policy).
3. Алерт на `sampling_trace_dropped_too_early` — индикатор RAM-pressure.

## 7. Redaction (2-я линия защиты)

`redaction/platform-second-line`: только `blocked_values` (JWT/Bearer/e-mail/PAN),
`allow_all_keys: true`, `summary: info` (аудит срабатываний в атрибутах `redaction.*`).
Первая линия — SDK `ScrubbingSpanProcessor`. **Не включайте** `allowed_keys`-режим:
blast radius на весь трафик кластера; allowlist — зона SDK (`CategoryContracts`).
Рост счётчиков redaction = сигнал, что какой-то сервис льёт sensitive data мимо SDK-маскирования — эскалировать владельцу сервиса.

## 8. k8sattributes (agent-tier)

RBAC-требования (ServiceAccount агента):

```yaml
rules:
  - apiGroups: [""]
    resources: [pods, namespaces]
    verbs: [get, list, watch]
```

Только agent-tier: матчинг по IP входящего соединения; на gateway IP отправителя — это IP
агента, и обогащение молча не сработает. Если `k8s.*` атрибуты пропали — проверить RBAC
и что приложения шлют в DaemonSet напрямую (hostPort/downward API), а не через сервис.

## 9. TTL-tiers (опциональный конфиг)

`otel-collector-config-ttl-tiers.yaml`: failure-трейсы → `jaeger-long`, остальные →
`jaeger-short`. Маршрутизация — routing **connector** по **resource**-атрибуту
(анти-фрагментация, Spike S1; см. ADR). Не переписывать на `context: span` — трейсы
начнут расщепляться между backend'ами. Конкретные TTL retention — зона SRE.

## 10. Изменение политик (GitOps-процесс)

1. Правка YAML в `platform-tracing-collector-config` через PR.
2. CI: `CollectorPolicyContractTest` (контракт ключей/значений/порядка) +
   `./gradlew validateCollectorConfigs -PstrictValidation` (Docker, pin 0.154.0).
3. E2e (gated): `./gradlew :platform-tracing-e2e-tests:test -PrunE2e` —
   `CollectorProductionPolicyE2ETest` гоняет production YAML.
4. Rollout: сначала staging, наблюдать `count_traces_sampled{policy}` сутки, затем prod.

Срочный тюнинг процентов/порогов — через env-vars (раздел 4) без PR, с последующей фиксацией в Helm.

## 11. Чек-лист on-call

- [ ] Трейс не появился в Jaeger: проверить `tailsampling.policy` соседних трейсов сервиса; форс-проверка через `X-Trace-On: on` (политика `forced-traces`).
- [ ] Рост памяти gateway: `num_traces`/`decision_wait`/`max_trace_size`; `GOMEMLIMIT` задан?
- [ ] `refused_spans` > 0: memory_limiter душит приём — память или scale out.
- [ ] Разорванные трейсы: совпал ли rollout gateway по времени; `late_span_age`; decision-кэши.
- [ ] Полная недоступность Collector'а: приложения НЕ деградируют (bounded queue + SafeSpanExporter, проверено `CollectorUnavailableResilienceTest`) — теряется только телеметрия.
- [ ] После бампа образа Collector: `validateCollectorConfigs -PstrictValidation` зелёный? Изменения в changelog'е contrib по tail_sampling/redaction/routing?
