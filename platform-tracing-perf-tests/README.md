# platform-tracing-perf-tests

Macro перф-стенд Фазы 17 (PR-3/PR-4): доказательство бюджетов
[performance-budgets.yaml](../docs/tracing/performance-budgets.yaml) по сценариям
[performance-test-matrix.md](../docs/tracing/performance-test-matrix.md).
Определения SLA и статпротокол — [ADR-performance-model.md](../docs/decisions/ADR-performance-model.md).

## Состав

```
src/main/java          SUT: Spring Boot + platform starter (идентичен во всех Mx)
docker/sut             Dockerfile SUT (bootJar + javaagent + extension через COPY)
docker/collector       Collector contrib 0.154.0 + perf-конфиг (OTLP -> nop)
docker/k6              k6 0.57.0 + open-model сценарий (constant-arrival-rate)
docker-compose.perf.yml  топология стенда (cpuset на компонент)
scenarios/*.env        конфигурации матрицы (m0, m4, m5, m5w, ...)
scripts/               раннеры прогонов (PowerShell, remote Docker)
```

## Запуск

```powershell
# Reference-лаборатория (Gentoo). Полный M5-прогон: warmup 2м + steady 10м @ 500 rps.
.\scripts\run-perf-scenario.ps1 -Scenario m5 -DockerHost tcp://192.168.100.70:2375

# Пара M0/M0 для калибровки шума хоста (обязательна перед official-прогонами):
.\scripts\run-perf-scenario.ps1 -Scenario m0 -DockerHost tcp://192.168.100.70:2375
.\scripts\run-perf-scenario.ps1 -Scenario m0 -DockerHost tcp://192.168.100.70:2375

# С JFR-профилированием (обязателен для прогонов с дельтой CPU > 2%):
.\scripts\run-perf-scenario.ps1 -Scenario m5 -Jfr -DockerHost tcp://192.168.100.70:2375

# Быстрая проверка стенда (НЕ для sign-off):
.\scripts\run-perf-scenario.ps1 -Scenario m5 -Rate 100 -WarmupMin 1 -SteadyMin 2
```

Артефакты прогона — `docs/tracing/perf-results/<stamp>/<scenario>/`:
`summary.json`, `k6-summary.json` (перцентили по фазам), `rss-cpu-samples.csv`,
`gc.log`, `run.jfr` (при `-Jfr`).

## Деградационные сценарии (PR-4)

```powershell
# M6 collector down / M8a slow / M8b 429-503 / M8c flaky:
.\scripts\run-perf-scenario.ps1 -Scenario m6  -DockerHost tcp://192.168.100.70:2375
.\scripts\run-perf-scenario.ps1 -Scenario m8a -DockerHost tcp://192.168.100.70:2375
.\scripts\run-perf-scenario.ps1 -Scenario m8b -DockerHost tcp://192.168.100.70:2375
.\scripts\run-perf-scenario.ps1 -Scenario m8c -DockerHost tcp://192.168.100.70:2375

# Queue saturation (обе ветки overflow-политики):
.\scripts\run-perf-scenario.ps1 -Scenario queue-saturation          -SteadyMin 5
.\scripts\run-perf-scenario.ps1 -Scenario queue-saturation-upstream -SteadyMin 5

# M9 soak 60 мин (leak detection, JFR OldObjectSample):
.\scripts\run-perf-scenario.ps1 -Scenario m9 -SteadyMin 60 -Jfr

# M10 reload под нагрузкой (раннер сам выполняет reload-серию в середине steady):
.\scripts\run-perf-scenario.ps1 -Scenario m10
```

Анализ и вычисление гейтов (RSS slope, Δ CPU/RSS/p99 vs baseline):

```powershell
.\scripts\analyze-perf-run.ps1 -RunDir ..\..\docs\tracing\perf-results\<stamp>\m5 `
                               -BaselineDir ..\..\docs\tracing\perf-results\<stamp>\m0
```

Инфраструктура degraded-сценариев: toxiproxy (M8a latency-toxic 200ms; M8c — фоновый
цикл timeout-toxic 10с/10с), nginx-backpressure (M8b — 60% 429 / 40% 503).
Perf-admin endpoint SUT (`/perf/admin/*`, мост к `PlatformTracingControl` MBean)
используется для M10 reload и сэмплирования export-метрик (queue size, drops по reason).

## Validity gates

Прогон **невалиден** (не идёт в sign-off), если:

- k6 завершился ненулевым кодом (`dropped_iterations > 0` — генератор не выдержал rate);
- SUT не достиг health 200 (S1 timeout);
- отсутствуют GC-логи / RSS-сэмплы;
- steady state короче заявленного.

`summary.json.runValid` агрегирует машинно-проверяемую часть гейтов.

## Ограничения remote Docker (Gentoo)

Volume mounts хостовых Windows-путей на удалённый daemon **не работают** — всё содержимое
образов попадает через build context (`COPY`), артефакты забираются `docker cp`.
Поэтому изменение k6-сценария или Collector-конфига требует пересборки образа
(раннер делает `compose build` на каждом прогоне — кэш слоёв делает это дёшево).

## Что измеряется и что нет

- Δ CPU / Δ RSS SUT vs M0 — основной результат (бюджеты m5-cpu / m5-rss).
- Collector экспортирует в `nop`: измеряется overhead SDK/agent, не throughput backend'а.
- Логирование SUT приглушено (`root: warn`): logging-стек — не предмет перф-модели трассировки.
