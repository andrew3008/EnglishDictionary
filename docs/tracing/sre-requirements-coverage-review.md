# Platform Tracing ↔ SRE: покрытие требований и зоны ответственности

| Поле | Значение |
|------|----------|
| Назначение | Рабочий документ для встречи с SRE: как требования `Traces Requests.txt` покрываются решением, что находится в зоне SRE и какие решения нужны от команды эксплуатации |
| Статус решения | pre-production; функциональный scope закрыт, **release performance gates — FAIL/PENDING** (см. §6) |
| Источник требований | `Platform_Traces_Archive\Traces Requests.txt` (единственный авторитетный документ) |
| Нормативная база | каталог [`docs/decisions`](../decisions) (ADR), [traceability.md](traceability.md), [requirements-traceability.md](requirements-traceability.md) |
| Перф-evidence | [architecture-committee-review.md](perf-results/2026-06-10_official/architecture-committee-review.md) (сессия `2026-06-10_official`) |

---

## 1. Что мы решаем на встрече

Документ закрывает три вопроса:

1. **Покрытие требований** — каждый пункт `Traces Requests.txt` отображён на реализацию или на явный GAP с ADR-обоснованием (§3–§5).
2. **Граница ответственности** SDK (Java-команда) ↔ Collector ↔ SRE — что приходит в поставке, а что эксплуатирует и настраивает SRE (§2, §7).
3. **Решения, нужные от SRE** — перф-SLA не выполнены в нормальном режиме, часть гейтов PENDING, ряд параметров retention/backpressure принципиально вынесен на Collector (§6, §8).

Архитектурный принцип, на котором стоит всё решение — **agent-first**: телеметрия собирается OpenTelemetry Java Agent + платформенным extension'ом, без изменения кода приложений; приоритизация и обязательное удержание трейсов делаются на **tail-уровне Collector'а**, а не в SDK. Это прямо следует из требования заказчика: *«хотелось бы, чтобы сэмплирование было на Otel Collector'е»* (`Traces Requests.txt`, раздел «Параметры для управления»).

---

## 2. Граница ответственности (модель Head/Tail)

Источник: [ADR-collector-boundary.md](../decisions/ADR-collector-boundary.md), [ADR-runtime-sampling-policy.md](../decisions/ADR-runtime-sampling-policy.md).

| Зона | Кто владеет | Обязанности |
|------|-------------|-------------|
| **SDK / Agent extension** (head) | Java-команда (поставка) | Context propagation (W3C + `X-Trace-On`/`X-QA-Trace`), head sampling (ratio/route/drop-paths), span limits, маскирование 1-я линия, resource identity, защита приложения (bounded queue, timeout, no-op fallback), runtime-управление политикой (JMX/actuator) |
| **Collector** (tail) | SRE (эксплуатация) + Java-команда (versioned YAML поставка) | Tail sampling (errors-always / slow / high-priority / probabilistic), retention-приоритизация, durable retry, backpressure (429/503), маскирование 2-я линия, TTL-tiers, k8sattributes |
| **Инфраструктура** | SRE / DevOps | Helm/Skaffold rendering `OTEL_*`, развёртывание Collector + Jaeger storage + TTL, Grafana-дашборды, нагрузочное окружение, capacity planning |

**Ключевое следствие для SRE:** требования «errors всегда сэмплируются», «приоритизация errors > slow > success», «backpressure 429/503», «retry с backoff» **архитектурно закрыты на Collector'е**, а не в Java-SDK. Это осознанное решение (ресурсные бюджеты CPU<3%/Mem<10% не позволяют держать durable-буфер и priority-queue в процессе приложения — [ADR-safe-span-exporter-v1.md](../decisions/ADR-safe-span-exporter-v1.md)). Поставка содержит готовые versioned YAML (`platform-tracing-collector-config`), но **их параметры под реальный трафик настраивает SRE**.

---

## 3. §1–§2 — Генерация span'ов, изоляция, отказоустойчивость

Полная карта: [traceability.md](traceability.md). Здесь — выжимка с акцентом на зону SRE.

| Требование | Статус | Реализация / зона SRE |
|------------|--------|------------------------|
| §1 API создания/завершения span'ов; обязательные атрибуты | OK | OTel Agent (auto-instrumentation) + `PlatformResourceProvider` + `EnrichingSpanProcessor` |
| §1.2 Типизированные span'ы HTTP/DB/RPC/Exception | OK | Agent instrumentation + typed span API (Фаза 13); Postgres/Kafka — auto |
| §1.3 Передача контекста + флаги сэмплирования + `X-Trace-On` | OK | W3C TraceContext + `CompositeSampler` (`ForceHeaderRule`/`QaTraceRule`) |
| §2.1 Неблокирующее поведение (ошибки только логируются) | OK | `PlatformCompositeSpanProcessor` + `SafeSpanExporter` глотают исключения делегатов |
| §2.2 Асинхронный экспорт | OK | `BatchSpanProcessor` / `PlatformDropOldestExportSpanProcessor` (worker-поток) |
| §2.3 Таймаут операций (~100 мс) | OK (с оговоркой) | `export-timeout` конфигурируем; буквальные 100 мс нормализованы — см. GAP-EXPORT-TIMEOUT-100MS (§5) |
| §2.4 Автоматический fallback (тихий пропуск) | OK | drop-on-overflow + изоляция исключений |
| §2.5 Фиксированная очередь drop-oldest | OK (v1.x default) | `PlatformDropOldestExportSpanProcessor` (bounded `ArrayDeque`, drop-oldest); возврат к stock BSP: `PLATFORM_TRACING_QUEUE_OVERFLOW_POLICY=UPSTREAM` ([ADR-drop-oldest-export-processor-v1.md](../decisions/ADR-drop-oldest-export-processor-v1.md)) |
| §2.6 Принудительный сброс при переполнении | OK | bounded queue + rate-limited warning |
| §2.7 Соответствие semconv | OK | `ValidatingSpanProcessor` (runtime) + semconv-lint |
| §2.8 Валидация обязательных полей (`service.name`) | OK + OK (Collector) | `ValidatingSpanProcessor` (SDK) + `transform/platform-semconv-backstop` (gateway YAML) |
| **CPU < 3% / Memory < 10%** | **FAIL (M5)** | См. §6 — главный вопрос встречи |

---

## 4. §2.1–§3.5 — Контроль объёма данных, экспорт и доставка

| Требование | Default | Статус | Зона |
|------------|---------|--------|------|
| §2.1 Лимит атрибутов | 50 | OK | SDK (`OTEL_SPAN_ATTRIBUTE_COUNT_LIMIT`) |
| §2.2 Лимит длины значения | 1000 | OK | SDK |
| §2.3 Лимит событий | 10 | OK | SDK |
| §2.4 Маскирование PII (логины/пароли/email/токены) | enabled | OK (2 линии) | SDK `ScrubbingSpanProcessor` (1-я) + gateway `redaction/platform-second-line` (2-я, **SRE настраивает blocked_values**) |
| §2.5.1 Span timeout 30s | 30s | OK | SDK `SpanWatchdogProcessor` |
| §2.5.2 Trace timeout 60s | 60s | OK | SDK `SpanWatchdogProcessor` |
| §3.1 Быстрый ответ при отправке | — | OK | async export, бизнес-поток не блокируется |
| §3.2 Локальная буферизация | maxSize=2048 | OK (SDK) / durable → Collector | bounded in-memory; **durable-буфер — зона Collector (SRE)** |
| §3.3 Retry с exponential backoff | enabled | OK (Collector) | OTLP-клиент + Collector `retry_on_failure`; **SDK не дублирует retry** ([ADR-safe-span-exporter-v1.md](../decisions/ADR-safe-span-exporter-v1.md)) |
| §3.4 Backpressure (429/503) | — | OK (Collector) | Collector `sending_queue` + `memory_limiter`; **SDK-side circuit breaker сознательно не реализован** (ресурсные бюджеты) |
| §3.5 Приоритизация errors > slow > success | — | OK (Collector) | SDK проставляет `platform.trace.priority`/`duration_class`; **retention делает tail_sampling — параметры за SRE**; SDK-side priority eviction — backlog (GAP-PRIORITY-EVICTION) |

**Внимание SRE:** §3.3/§3.4/§3.5 — это ваша зона по дизайну. SDK гарантирует только, что приложение не пострадает (изоляция, метрики отказов). Durable retention, реакция на 429/503 и приоритетное удержание ошибок живут в Collector-конвейере, который вы эксплуатируете.

---

## 5. §4 + «Параметры для управления» — конфигурируемость и сэмплирование

| Требование | Статус | Реализация |
|------------|--------|------------|
| §4 Гибкие настройки без правок кода | OK | `TracingProperties` (`platform.tracing.*`) + extension SPI дефолты; `GET /actuator/tracing` показывает факт (`otelEffective`) и маппинг Spring→`OTEL_*` |
| §4 Динамическое переключение режимов «на лету» | OK | runtime policy через JMX/actuator (§7); topology (endpoint/limits) — startup-only ([ADR-runtime-config-policy-vs-topology.md](../decisions/ADR-runtime-config-policy-vs-topology.md)) |
| % сэмплирования трейса (head) | OK | `CompositeSampler` + `SamplerStateHolder` (default ratio 0.1) |
| Errors **всегда** сэмплируются | OK (Collector) | `tail_sampling.errors-always-sample` (status_code=ERROR) — **по явному пожеланию заказчика на Collector'е** |
| Общие запросы — без сэмплирования / по % | OK | head ratio + route-ratio |
| `X-Trace-On` → 100% запись | OK | `ForceHeaderRule` → `platform.sampling.reason=force_header`; политика `forced-traces` + contract-тест |
| QA-ручка возвращает Request ID | OK | `X-QA-Trace` → `QaTraceRule`; response `X-Request-Id` ([ADR-request-id-correlation-id.md](../decisions/ADR-request-id-correlation-id.md)) |

### Осознанные GAP (нормализованные требования)

| GAP | Суть | Обоснование |
|-----|------|-------------|
| GAP-EXPORT-TIMEOUT-100MS | Буквальный export-timeout 100 мс не применяется (default 5000 мс) | Требование отзывчивости закрыто async-экспортом; 100 мс на сетевой OTLP-экспорт привело бы к ложным таймаутам ([ADR-performance-model.md](../decisions/ADR-performance-model.md), Решение 2) |
| GAP-PRIORITY-EVICTION | SDK-side приоритетное вытеснение из очереди (drop-oldest без учёта приоритета) | Retention закрыт на Collector tail_sampling; SDK-side priority-queue создаёт риск orphaned spans; вынесено в backlog (board) |

Оба GAP — кандидаты на подтверждение/оспаривание со стороны SRE на встрече.

---

## 6. Производительность — главный блокер (требует решения SRE)

Источник: [architecture-committee-review.md](perf-results/2026-06-10_official/architecture-committee-review.md), сессия `2026-06-10_official` на референсной лабе Gentoo (8 vCPU / 32 GB).

### 6.1. Факт против бюджета

| Бюджет | Сценарий | Норматив | Факт (медиана) | Статус |
|--------|----------|----------|----------------|--------|
| `m5-cpu` | M5 (FULL, ratio 0.1) | Δ CPU < 3% | **+48,1%** | **FAIL** |
| `m5-rss` | M5 | Δ RSS < 10% | **+25,4%** | **FAIL** |
| `degraded-p99` | M6/M8* | Δ p99 < 50 мс | 0,2–0,34 мс | **PASS** |
| `degraded-no-oom` | M6/M8* | exit 0, без OOM | выполнено | **PASS** |
| `soak-rss-slope` | M9 (60 мин) | ≤ 1 MB/мин | **0,30 MB/мин** | **PASS** |
| `queue-bounded` | queue-sat | queue ≤ capacity | требует JMX/JFR | **PENDING** |
| `dynconf-no-stall` | M10 | spike ≤ 50 мс | требует reload-анализ | **PENDING** |
| `app-thread-no-export-wait` | M6/M8a | ThreadPark = 0 | требует JFR | **PENDING** |

### 6.2. Что это значит

- **Устойчивость подтверждена**: деградация Collector (unreachable / slow / 429-503 / flaky) не роняет приложение и почти не влияет на HTTP p99 (< 1 мс при бюджете 50 мс). Инварианты неблокирующего поведения и fallback выполнены на макро-уровне.
- **Утечек нет**: soak 60 мин — RSS slope 0,30 MB/мин, overhead стабильный (плато), не нарастающий.
- **Но нормальный режим не вписывается в SLA**: +48% CPU и +25% RSS при бюджетах 3% и 10%. Это системное отклонение, не погрешность.
- **Среда измерений пограничная**: калибровка M0/M0 дала дрейф CPU 1,77% при пороге 1,0% — для жёсткого 3%-гейта нужна более изолированная среда (это **прямой вопрос к SRE**).

### 6.3. Решения, требуемые от SRE/комитета

| # | Вопрос | Варианты |
|---|--------|----------|
| **D1** | Принять ли M5 при +48% CPU / +25% RSS | (A) отклонить, optimization program; (B) пересмотр бюджетов + waiver; (C) ограничить scope обязательного tracing (профили/sampling) |
| **D2** | Квалификация измерительной среды | (A) усилить изоляцию (cpuset pinning) и повторить; (B) принять пограничную с N=5; (C) перенести на production-class стенд |
| **D3** | Официальный load profile | зафиксировать 300 rps как canonical или масштабировать стенд под 500 (на 500 — OOM генератора) |
| **D4–D6** | M5w worst-case budget; S1 startup budget (M0 ~5,2 s → M5 ~10,4 s); закрытие JFR-гейтов | — |

> Без решения D1 и закрытия PENDING-гейтов `:platform-tracing-bench:performanceReleaseGate` остаётся **FAIL** и блокирует production sign-off.

---

## 7. Runtime-управление (операции SRE)

Полный runbook: [runtime-sampling-control.md](runtime-sampling-control.md). Нормативные решения: [ADR-runtime-sampling-policy.md](../decisions/ADR-runtime-sampling-policy.md).

Два транспорта управления политикой **без рестарта** приложения:

| Транспорт | Когда | Точка входа |
|-----------|-------|-------------|
| JMX (канонический) | доступ к JVM | MBean `space.br1440.platform.tracing:type=Control,name=PlatformTracingControl` |
| Actuator (HTTP-зеркало) | k8s без JMX | `POST /actuator/tracing/{property}/{value}` |

Типовые операции SRE: `setSamplingRatio`, `setRouteRatios`, kill-switch `setSamplerEnabled false`, export-gate `setExportEnabled false`, drop-paths. Гарантии: атомарная публикация снимка (CAS), last-known-good при невалидном апдейте, версионирование + audit trail (`getConfigAuditTrail`). Перф-инвариант reload под нагрузкой — сценарии M10/M10c/M10d.

**Что НЕ меняется в runtime** (startup-only topology): OTLP endpoint, processor chain, span limits, BSP queue size — смена через rolling update / Collector reload ([ADR-runtime-config-policy-vs-topology.md](../decisions/ADR-runtime-config-policy-vs-topology.md)).

---

## 8. Чек-лист зоны SRE (явно НЕ задача Java-команды)

Из [traceability.md](traceability.md) (раздел «Out of Java scope»):

| Зона | Почему SRE |
|------|------------|
| Параметры tail_sampling (`decision_wait`, `num_traces`, latency thresholds) | зависят от p99 и RPS реального окружения |
| Helm/Skaffold rendering `OTEL_*` env vars | хранится в deploy-репозиториях |
| Развёртывание Jaeger storage + TTL-tiers | `otel-collector-config-ttl-tiers.yaml` — лишь пример |
| Performance baselines (CPU/Mem окружение) | нагрузочные стенды, изоляция CPU |
| Grafana-дашборды и алерты Collector'а | observability infrastructure |
| RBAC для `k8sattributes` (pod IP matching) | кластерные права |

Поставка даёт versioned YAML, контракт значений `platform.sampling.reason` (охраняется `CollectorPolicyContractTest`) и операторский runbook [collector-pipeline-production.md](../runbook/collector-pipeline-production.md). Тюнинг под трафик — за SRE.

---

## 9. Предлагаемая повестка встречи

1. **Подтверждение границы Head/Tail** (§2): согласны ли SRE, что retention/приоритизация/backpressure живут на Collector'е, который они эксплуатируют.
2. **Перф-блокер** (§6): решения D1–D3 — судьба SLA M5, квалификация среды, load profile. Главный пункт.
3. **PENDING-гейты** (§6.1): кто и когда закрывает `queue-bounded` / `dynconf-no-stall` / `app-thread-no-export-wait` (JFR-анализ).
4. **Осознанные GAP** (§5): подтвердить или оспорить GAP-EXPORT-TIMEOUT-100MS и GAP-PRIORITY-EVICTION.
5. **Runtime-операции** (§7): согласовать runbook и набор ручек, которыми SRE будет пользоваться в инциденте.
6. **Tail-параметры под трафик** (§8): передача владения параметрами tail_sampling и retention.

---

## 10. Ссылки

- Функциональная карта: [traceability.md](traceability.md)
- Перф-карта «требование → evidence → gate»: [requirements-traceability.md](requirements-traceability.md)
- Перф-отчёт: [architecture-committee-review.md](perf-results/2026-06-10_official/architecture-committee-review.md)
- Перф-модель и SLA: [ADR-performance-model.md](../decisions/ADR-performance-model.md)
- Граница SDK↔Collector: [ADR-collector-boundary.md](../decisions/ADR-collector-boundary.md)
- Runtime sampling: [ADR-runtime-sampling-policy.md](../decisions/ADR-runtime-sampling-policy.md) + [runtime-sampling-control.md](runtime-sampling-control.md)
- Export-safety: [ADR-safe-span-exporter-v1.md](../decisions/ADR-safe-span-exporter-v1.md)
- Очередь drop-oldest: [ADR-drop-oldest-export-processor-v1.md](../decisions/ADR-drop-oldest-export-processor-v1.md)
- Policy vs topology: [ADR-runtime-config-policy-vs-topology.md](../decisions/ADR-runtime-config-policy-vs-topology.md)
