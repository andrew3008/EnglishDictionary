# OpenTelemetry Collector — платформенная конфигурация

Эталонная конфигурация Collector'а для платформенной трассировки (Фаза 6; boundary-контракт — Фаза 16, [ADR-collector-boundary](../../../../../docs/decisions/ADR-collector-boundary.md)). Развёртывается командой SRE на каждом окружении и не должна модифицироваться приложениями.

Pin образа: `otel/opentelemetry-collector-contrib:0.154.0` (Track B+). Синтаксис env-переменных в YAML — `${env:VAR:-default}` (легаси-форма `${VAR:default}` на contrib ≥ 0.123 ломает confmap-парсер).

## Файлы

- `otel-collector-agent-loadbalancing.yaml` — конфигурация Tier 1 (Agent / DaemonSet). Сбор телеметрии с подов узла, обогащение `k8s.*` метаданными (`k8sattributes`, pod-IP matching, требует RBAC) и маршрутизация трасс по `trace_id` на Tier 2 (Gateway) для обеспечения Trace Affinity.
- `otel-collector-gateway-tail-sampling.yaml` — конфигурация Tier 2 (Gateway / Deployment). Stateful `tail_sampling` (ошибки и медленные операции — 100%, остальные — по проценту) + `redaction/platform-second-line` (2-я линия маскирования sensitive data). Защищено лимитами памяти и размера трейсов.
- `otel-collector-config-ttl-tiers.yaml` — расширенный вариант с routing **connector** (Фаза 16: deprecated `routing` processor заменён; маршрутизация только по resource-атрибутам — анти-фрагментация) для физического разделения storage tier'ов (long-retention для ошибок, short-retention для успехов).

Машинные гарантии: `CollectorPolicyContractTest` (контракт SDK ↔ YAML: ключи/значения/порядок процессоров) + `./gradlew validateCollectorConfigs -PstrictValidation` (Docker-валидация всех конфигов на пине). Операторский runbook: `docs/runbook/collector-pipeline-production.md`.

## Архитектура и Trace Affinity

Для корректной работы `tail_sampling` необходимо, чтобы все спаны одной трассы обрабатывались одним и тем же инстансом Collector'а. Это достигается за счет двухуровневой топологии (Two-Tier Architecture):

1. **Tier 1 (Agent):** Устанавливается как DaemonSet. Использует `load_balancing` exporter. По умолчанию (или явно через `routing_key: traceID`) маршрутизирует все спаны с одинаковым `trace_id` на один и тот же инстанс Tier 2.
2. **Tier 2 (Gateway):** Развертывается как Deployment. Получает полные трассы, буферизирует их в памяти (`decision_wait`) и применяет политики `tail_sampling`.

## Важно: Feature Gates

В конфигурации используется политика композитного сэмплинга (включающая `drop` и `not`). Для ее корректной работы **обязательно** включение feature gate `processor.tailsamplingprocessor.recordpolicy`.

В современных версиях Collector'а (OTel 0.95+) Feature Gates **не задаются в YAML-файле**. Они должны передаваться через CLI-аргументы при запуске контейнера:
```bash
otelcol-contrib --config=/conf/otel-collector-gateway-tail-sampling.yaml --feature-gates=+processor.tailsamplingprocessor.recordpolicy
```
В Helm-чарте это настраивается через `extraArgs` или `command`.

## Политики tail-sampling (Gateway)

| Имя политики | Тип | Назначение |
|---|---|---|
| `errors-always-sample` | `status_code: ERROR` | Гарантирует сохранение 100% трейсов с ошибками (HTTP 5xx, Exception). |
| `slow-traces` | `latency` | Сохраняет 100% медленных трейсов (по умолчанию > 5000ms). |
| `forced-traces` | `string_attribute` | Сохраняет трейсы, форсированные SDK (атрибут `platform.sampling.reason` в `[force_header, qa_trace]` — синхронизировано с `PlatformSamplingReasons.EXPORTED`, проверяется contract-тестом; Фаза 16 исправила несуществующее `x_trace_on`). |
| `platform-high-priority` | `string_attribute` | Сохраняет трейсы с `platform.trace.priority=high` (бизнес-приоритет SDK). |
| `drop-successful-infra-noise` | `drop` + `not` | Отбрасывает инфраструктурный шум (`/health`, `/metrics`), **ТОЛЬКО** если это успешный запрос (НЕ ошибка). Первичный drop рекомендован на SDK (`drop-paths`, паттерн Head+Tail — см. runbook). |
| `success-baseline` | `probabilistic` | Вероятностный сэмплинг для всех остальных успешных запросов (по умолчанию 1%). |

*Обратите внимание: политика `drop-successful-infra-noise` использует композицию `drop` и `not status_code: ERROR`, чтобы ошибочные health-check запросы по-прежнему попадали в хранилище.*

## Настройка Capacity Tuning (Capacity Planning)

Все лимиты и размеры буферов вынесены в переменные окружения (`${env:VAR:-default}`), чтобы SRE могли адаптировать их под профиль нагрузки без изменения YAML.

**GOMEMLIMIT (обязательно в production):** задавайте env `GOMEMLIMIT` = 80% hard-лимита контейнера (например, `GOMEMLIMIT=1638MiB` при limit 2GiB) в дополнение к `memory_limiter` — Go GC начнёт агрессивнее освобождать память до того, как memory_limiter начнёт отбрасывать данные.

**Формулы для расчета:**
- `num_traces` >= `expected_new_traces_per_sec` × `decision_wait_seconds` × `safety_factor` (например, 1000 RPS × 10s × 5 = 50 000).
- `decision_cache` (sampled_cache_size / non_sampled_cache_size): Должен быть значительно больше `num_traces`, чтобы кешировать решения для поздних (late-arriving) спанов. Рекомендуется `num_traces * 5`.

## Обязательный Мониторинг и Метрики

Для стабильной работы `tail_sampling` в production необходимо настроить алерты по следующим метрикам:

- `otelcol_processor_tail_sampling_sampling_trace_dropped_too_early` — **Критический алерт**. Индикатор того, что Collector не справляется с нагрузкой и вытесняет трассы до истечения `decision_wait`. Решение: увеличить `num_traces`, уменьшить `decision_wait` или масштабировать Gateway (добавить поды).
- `otelcol_processor_tail_sampling_sampling_late_span_age` — Индикатор для тюнинга `decision_wait`. Если много late-spans, возможно `decision_wait` слишком мал.
- `otelcol_processor_tail_sampling_sampling_decision_timer_latency` — Задержка принятия решения.
- `otelcol_processor_tail_sampling_sampling_trace_removal_age` — Реальный возраст удаляемых трасс. Рекомендуемый дашборд: `histogram_quantile(0.01, rate(otelcol_processor_tail_sampling_sampling_trace_removal_age_bucket[5m]))`
- `otelcol_processor_tail_sampling_count_traces_sampled` — Количество засемплированных трейсов. Рекомендуемый дашборд: `sum(rate(otelcol_processor_tail_sampling_count_traces_sampled[5m])) by (policy, decision)`
- `processor_tail_sampling_sampling_policy_evaluation_error` — Ошибки при вычислении политик сэмплинга.
