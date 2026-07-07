{info}
*Формат:* Confluence Wiki Markup (старый Wiki). Новая Jira его не рендерит — файл предназначен для копирования в Confluence или для ручной конвертации.
*Story type:* Testing
*Компонент:* spring-boot-starter-platform-metrics
*Среда:* Dev-серверы + SRE Dashboard'ы (Prometheus / Grafana)
{info}

h1. [TEST] Регрессионное тестирование spring-boot-starter-platform-metrics на Dev

h2. Краткое описание (Summary)

Провести регрессионное тестирование переписанного Spring Boot-стартара *spring-boot-starter-platform-metrics* на Dev-среде с использованием Demo-микросервиса и верификацией метрик в SRE Dashboard'ах.

h2. Контекст и цель

h3. О чём модуль

*spring-boot-starter-platform-metrics* — платформенный Spring Boot Starter, который автоматически подключает стандартизированные метрики сервисов на базе Micrometer и Spring Boot Actuator.

Стартер *не требует* отдельного подключения spring-boot-starter-actuator — Actuator приходит транзитивно.

Стартер добавляет унифицированные метрики для:

* *HTTP* (Servlet MVC и WebFlux) — счётчик запросов и таймер латентности
* *gRPC* — счётчики received/sent и таймер (только при наличии официального spring-grpc на classpath)
* *Kafka* — consumer lag/wait, processing/batch processing, messages_total, producer lag/total
* *platform.info* — gauge с метаданными сборки (при наличии build-info.json в classpath)

Все метрики стартера имеют префикс *platform_* (в Prometheus: точки Micrometer заменяются на подчёркивания, например {{platform.http.requests}} → {{platform_http_requests_total}}).

Подробные требования к метрикам: [Эталонные метрики сервисов|https://c.1440.space/pages/viewpage.action?pageId=508642331#id-[WIP]%D0%A2%D1%80%D0%B5%D0%B1%D0%BE%D0%B2%D0%B0%D0%BD%D0%B8%D1%8F%D0%BA%D0%BC%D0%B5%D1%82%D1%80%D0%B8%D0%BA%D0%B0%D0%BC%D1%81%D0%B5%D1%80%D0%B2%D0%B8%D1%81%D0%BE%D0%B2-%D0%AD%D1%82%D0%B0%D0%BB%D0%BE%D0%BD%D0%BD%D1%8B%D0%B5%D0%BC%D0%B5%D1%82%D1%80%D0%B8%D0%BA%D0%B8]

h3. Что изменилось

Ведущий Java-разработчик выполнил полную переработку (rewrite) стартера. Цель тестирования — убедиться, что на Dev:

# Поведение метрик соответствует ADR-контракту (имена, лейблы, SLO-buckets, кардинальность)
# Метрики корректно экспортируются через {{/actuator/prometheus}}
# Метрики видны и интерпретируемы на SRE Dashboard'ах
# Нет регрессий по сравнению с ожидаемым поведением harness-тестов

h3. Demo-микросервис (предпосылка)

*Предполагается, что Demo Spring Boot-микросервис уже разработан и задеплоен на Dev.*

Рекомендуемая основа — перенос сценариев из *platform-metrics-harness* ({{E:\Platform_SpringBoot_Starters\platform-metrics-harness}}):

|| Компонент harness || Назначение в Demo ||
| {{HttpProbeController}} | HTTP-пробы: {{/}}, {{/api/users/\{id\}}}, {{/redirect}}, {{/api/error}}, 404 |
| {{GreeterHarnessService}} | gRPC SayHello: успех, INVALID_ARGUMENT, INTERNAL |
| {{RecordMetricsListener}} / {{BatchMetricsListener}} | Kafka consumer: record/batch, success/error |
| Профили {{grpc}}, {{kafka}}, {{webflux}}, {{combo}} | Включение нужного стека на Dev |

*Зависимость Demo:*

{code:groovy}
implementation 'space.br1440.platform.starters:spring-boot-starter-platform-metrics:<ВЕРСИЯ_ДЛЯ_ТЕСТА>'
{code}

*Минимальная конфигурация Demo (application.yml):*

{code:yaml}
spring:
  application:
    name: platform-metrics-demo

management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,metrics,platform,info
  prometheus:
    metrics:
      export:
        enabled: true

platform:
  metrics:
    http:
      path-mode: normalized   # дефолт стартера
{code}

h2. Scope тестирования

h3. In Scope

* Деплой Demo на Dev с новой версией стартера
* HTTP-метрики (Servlet; WebFlux — если Demo поддерживает профиль {{webflux}})
* gRPC-метрики (если на Dev включён профиль {{grpc}})
* Kafka-метрики (если на Dev доступен Kafka и профиль {{kafka}})
* Prometheus scrape ({{/actuator/prometheus}})
* Верификация на SRE Dashboard'ах
* Конфигурационные сценарии: {{path-mode}}, {{disabled-tags}}

h3. Out of Scope

* Нагрузочное / soak / leak-hunt тестирование (это отдельные задачи harness)
* Prod-среда
* Изменение SRE Dashboard'ов (только проверка отображения)

h2. Тестовое окружение

|| Параметр || Значение ||
| Среда | Dev |
| Demo-сервис | {{platform-metrics-demo}} (имя — уточнить у DevOps) |
| URL HTTP | {{https://<demo-host>:<port>}} |
| Actuator Prometheus | {{https://<demo-host>:<port>/actuator/prometheus}} |
| Actuator Health | {{https://<demo-host>:<port>/actuator/health}} |
| gRPC endpoint | {{<demo-host>:<grpc-port>}} (профиль grpc) |
| Kafka bootstrap | Dev Kafka cluster (профиль kafka) |
| SRE Dashboard | Grafana / Prometheus — ссылку уточнить у SRE |
| Версия стартера | {{space.br1440.platform.starters:spring-boot-starter-platform-metrics:<VERSION>}} |

h2. Справочник: метрики стартера (дефолтные имена)

h3. HTTP

|| Micrometer || Prometheus (пример) || Обязательные лейблы ||
| {{platform.http.requests}} | {{platform_http_requests_total}} | {{method}}, {{path}}, {{status}} |
| {{platform.http.server.requests}} | {{platform_http_server_requests_seconds_*}} | {{method}}, {{path}}, {{status}} |

*Специальные значения {{path}}:*
* {{ROOT}} — корневой путь {{/}}
* {{REDIRECTION}} — HTTP redirect
* {{UNKNOWN}} — 404 / ненайденный маршрут
* {{/api/users/\{id\}}} — normalized path (режим по умолчанию)

h3. gRPC

|| Micrometer || Prometheus || Обязательные лейблы ||
| {{platform.grpc.server.received}} | {{platform_grpc_server_received_total}} | {{rpc_method}}, {{service}} |
| {{platform.grpc.server.sent}} | {{platform_grpc_server_sent_total}} | {{rpc_method}}, {{rpc_status_code}}, {{service}} |
| {{platform.grpc.server}} | {{platform_grpc_server_seconds_*}} | {{rpc_method}}, {{rpc_status_code}}, {{service}} |

h3. Kafka Consumer

|| Micrometer || Prometheus || Ключевые лейблы ||
| {{platform_consumer_processing_seconds}} | {{platform_consumer_processing_seconds_*}} | {{topic}}, {{client_id}}, {{consumer_group}}, {{outcome}}, {{exception_class}} |
| {{platform_consumer_batch_processing_seconds}} | {{platform_consumer_batch_processing_seconds_*}} | те же |
| {{platform_consumer_messages_total}} | {{platform_consumer_messages_total}} | {{topic}}, {{client_id}}, {{consumer_group}}, {{result}} |
| {{platform_consumer_lag_seconds}} | {{platform_consumer_lag_seconds_*}} | {{topic}}, {{client_id}}, {{consumer_group}} |
| {{platform_consumer_wait_seconds}} | {{platform_consumer_wait_seconds_*}} | {{topic}}, {{client_id}}, {{consumer_group}} |

h3. Kafka Producer

|| Micrometer || Prometheus ||
| {{platform_producer_messages_total}} | {{platform_producer_messages_total}} |
| {{platform_producer_lag_seconds}} | {{platform_producer_lag_seconds_*}} |

h3. SLO buckets (дефолт)

{{10ms, 25ms, 50ms, 100ms, 250ms, 500ms, 1s, 2500ms, 5s}} — должны присутствовать в histogram/timer метриках стартера.

h3. Запрещённые high-cardinality лейблы (глобально)

В scrape *не должно* быть: {{user_id}}, {{session_id}} и других пользовательских идентификаторов.

h2. Критерии приёмки Story

# Все Sub-task'и ST-00 … ST-05 закрыты со статусом *Done*; внутри каждого — все TC со статусом *Pass* или *Blocked* с обоснованием
# Для каждого *Fail* — заведён дефект с шагами воспроизведения и фрагментом scrape / скриншотом Dashboard
# Результаты зафиксированы в комментарии к каждому Sub-task (таблица: TC → Pass/Fail/Blocked → evidence)
# ST-05 (SRE Dashboard) выполнен после ST-01 … ST-04 — сквозная верификация на Grafana

h2. Декомпозиция Story на Sub-task'и

|| Sub-task || Тип метрик || TC || Зависимости || Приоритет ||
| [ST-00|#ST-00] | Smoke / инфраструктура | TC-00 | — | Blocker |
| [ST-01|#ST-01] | HTTP | TC-01 … TC-07, TC-16 | ST-00 | Critical |
| [ST-02|#ST-02] | gRPC | TC-09, TC-10 | ST-00 | Critical |
| [ST-03|#ST-03] | Kafka | TC-11 … TC-14 | ST-00 | Critical |
| [ST-04|#ST-04] | Общие (platform.info, ADR, SLO) | TC-05, TC-06, TC-08 | ST-00, ST-01 | Major |
| [ST-05|#ST-05] | SRE Dashboard (сквозная) | TC-15 | ST-01 … ST-04 | Critical |

*Порядок выполнения:* ST-00 → ST-01, ST-02, ST-03 (параллельно) → ST-04 → ST-05

{code:plantuml}
@startuml
skinparam shadowing false
skinparam ActivityBackgroundColor #F8F9FA

title Порядок выполнения Sub-task'ов

start
:ST-00\nSmoke / инфраструктура;
fork
  :ST-01\nHTTP метрики;
fork again
  :ST-02\ngRPC метрики;
fork again
  :ST-03\nKafka метрики;
end fork
:ST-04\nОбщие метрики\n(platform.info, ADR, SLO);
:ST-05\nSRE Dashboard;
stop
@enduml
{code}

{code:plantuml}
@startuml
skinparam componentStyle rectangle

title Архитектура тестирования на Dev

package "Demo: platform-metrics-demo" {
  [HttpProbeController] as HTTP
  [GreeterHarnessService] as GRPC
  [Kafka Listeners] as KAFKA
}

package "spring-boot-starter-platform-metrics" {
  [HttpMetricsFilter] as HF
  [WebfluxMetricsFilter] as WF
  [Grpc Interceptor] as GI
  [Kafka PostProcessor] as KP
  [PlatformMetrics] as PM
  [Micrometer / Actuator] as MA
}

HTTP --> HF
HTTP --> WF
GRPC --> GI
KAFKA --> KP
HF --> PM
WF --> PM
GI --> PM
KP --> PM
PM --> MA

cloud "Dev Prometheus" as PROM
database "Grafana Dashboard" as GRAF

MA --> PROM : /actuator/prometheus
PROM --> GRAF

note right of MA
  Тестировщик проверяет:
  1) scrape напрямую
  2) панели Grafana (ST-05)
end note
@enduml
{code}

----

h1. Sub-task ST-00: Smoke / инфраструктура {anchor:ST-00}

*Jira summary:* {{[TEST][metrics] ST-00 — Smoke: деплой Demo и Actuator}}
*Тип:* Sub-task
*Исполнитель:* мидл Java-разработчик

h2. Контекст

Первый обязательный Sub-task. Без работающего Demo и доступного {{/actuator/prometheus}} остальные проверки невозможны.

Стартер подключает autoconfiguration пакета {{space.br1440.platform.metrics}} и bean {{PlatformMetrics}}. Actuator приходит транзитивно — отдельно подключать {{spring-boot-starter-actuator}} не нужно.

*Цель ST-00:* подтвердить, что Demo с новой версией стартера поднялся на Dev, Actuator отвечает, JVM-метрики не сломаны, цепочка «запрос → фильтр → Micrometer» в принципе работает.

{code:plantuml}
@startuml
skinparam sequenceMessageAlign center

title ST-00: Smoke-проверка цепочки

actor Tester
participant "Demo" as Demo
participant "Actuator" as Act
participant "Micrometer" as MM

Tester -> Demo : GET /actuator/health
Demo --> Tester : 200 UP

Tester -> Demo : GET /actuator/prometheus
Demo -> MM : scrape registry
MM --> Act : jvm_*, platform_* (после трафика)
Act --> Tester : 200 text/plain

note over Tester
  Pass ST-00 = можно
  начинать ST-01…ST-03
end note
@enduml
{code}

h2. Тест-кейсы

h3. TC-00. Smoke: деплой Demo и доступность Actuator

*Приоритет:* Blocker

*Предусловия:*
# Demo {{platform-metrics-demo}} задеплоен на Dev с версией {{spring-boot-starter-platform-metrics:<VERSION>}}
# Известны URL и доступ к Actuator

*Шаги:*
# {{GET /actuator/health}}
# {{GET /actuator/prometheus}}
# Проверить логи старта: нет ошибок autoconfiguration {{space.br1440.platform.metrics}}

*Ожидаемый результат:*
* Health → 200 UP
* Prometheus → 200, формат Prometheus text exposition
* В scrape есть {{jvm_*}} — базовый экспорт не сломан
* После произвольного HTTP-запроса появляются {{platform_http_*}} (bean {{PlatformMetrics}} работает)

*Evidence:* curl health + первые 50 строк prometheus

h2. Критерии завершения тестирования

# TC-00 = *Pass*
# URL Demo и версия стартера зафиксированы в комментарии Sub-task
# Sub-task переведён в *Done* — разблокированы ST-01, ST-02, ST-03

----

h1. Sub-task ST-01: HTTP-метрики {anchor:ST-01}

*Jira summary:* {{[TEST][metrics] ST-01 — HTTP-метрики (Servlet / WebFlux)}}
*Зависит от:* ST-00
*Harness-референс:* {{ServletHttpMetricsHarnessTest}}, {{ServletHttpRawModeHarnessTest}}, {{HttpLabelRulesHarnessTest}}, {{DualStackHarnessTest}}

h2. Контекст

HTTP-метрики — основной и обязательный контур стартера. Реализованы через {{HttpMetricsFilter}} (Servlet MVC) и {{WebfluxMetricsFilter}} (WebFlux). Dual-stack classpath поддерживается, но на Dev активен *только один* фильтр в зависимости от {{spring.main.web-application-type}}.

|| Micrometer || Prometheus || Лейблы ||
| {{platform.http.requests}} | {{platform_http_requests_total}} | {{method}}, {{path}}, {{status}} |
| {{platform.http.server.requests}} | {{platform_http_server_requests_seconds_*}} | {{method}}, {{path}}, {{status}} |

*Режим path (дефолт {{normalized}}):* в лейбл {{path}} попадает шаблон Spring mapping ({{/api/users/\{id\}}}), а не raw URL. Специальные значения: {{ROOT}}, {{REDIRECTION}}, {{UNKNOWN}}.

*Конфигурация Demo:* эндпоинты из {{HttpProbeController}} — {{/}}, {{/api/users/\{id\}}}, {{/redirect}}, {{/api/error}}, 404.

{code:plantuml}
@startuml
skinparam shadowing false

title ST-01: Путь HTTP-запроса в метрику

|Тестировщик|
start
:HTTP-запрос к Demo;

if (path-mode?) then (normalized)
  :Spring mapping\n→ шаблон path;
  note right
    /api/users/42
    → /api/users/{id}
  end note
else (raw)
  :Исходный URI\n→ raw path;
  note right
    /api/users/42
    → /api/users/42
  end note
endif

:HttpMetricsFilter /\nWebfluxMetricsFilter;
:platform.http.requests\n(Counter);
:platform.http.server.requests\n(Timer + SLO buckets);
:/actuator/prometheus;

stop
@enduml
{code}

h2. Тест-кейсы

h3. TC-01. Normalized path (дефолт)

*Шаги:* 3× {{GET /api/users/\{разные id\}}} → scrape

*Ожидаемый результат:*
* Все запросы → 200
* Один path: {{/api/users/\{id\}}} (не raw ID)
* {{platform_http_requests_total}} ≥ 3, {{platform_http_server_requests_seconds_count}} ≥ 3

h3. TC-02. Специальные path: ROOT, REDIRECTION, 404, 5xx

|| Запрос || path || status ||
| {{/}} | {{ROOT}} | {{200}} |
| {{/redirect}} | {{REDIRECTION}} | 3xx |
| {{/does-not-exist}} | {{UNKNOWN}} | {{404}} |
| {{/api/error}} | {{/api/error}} | {{500}} |

h3. TC-03. Raw path mode

*Предусловие:* {{platform.metrics.http.path-mode=raw}}, перезапуск Demo

*Ожидаемый результат:* два distinct path: {{/api/users/42}}, {{/api/users/99}}. После кейса — вернуть {{normalized}}.

h3. TC-04. Disabled-tags (status)

*Конфигурация:* убрать {{status}} у {{platform.http.requests}} и {{platform.http.server.requests}}

*Ожидаемый результат:* в scrape нет {{status=}} у HTTP-метрик; {{method}} и {{path}} на месте

h3. TC-07. Кардинальность path

*Шаги:* 10+ запросов с разными ID + несколько 404 в режиме {{normalized}}

*Ожидаемый результат:* уникальных {{path}} ≤ 10; нет {{/api/users/10758}} в normalized-режиме

h3. TC-16. Dual-stack Servlet vs WebFlux (опционально)

*Предусловие:* два инстанса или переключение профиля {{webflux}}

*Ожидаемый результат:*
* Servlet: активен {{HttpMetricsFilter}}, WebFlux-фильтр отсутствует
* WebFlux: активен {{WebfluxMetricsFilter}}, Servlet-фильтр отсутствует
* Имена и лейблы метрик идентичны ADR

h2. Критерии завершения тестирования

# TC-01, TC-02 = *Pass* (обязательные)
# TC-07 = *Pass* (обязательный)
# TC-03, TC-04 = *Pass* или *Blocked* (если нет возможности переконфигурировать Demo — согласовать с ведущим)
# TC-16 = *Pass*, *Blocked* (нет WebFlux на Dev) или *N/A*
# В комментарии Sub-task — таблица TC + фрагменты scrape
# Нет открытых Bug'ов по HTTP без workaround
# *Done* → разблокирован ST-04 (частично) и ST-05 (HTTP-панели)

----

h1. Sub-task ST-02: gRPC-метрики {anchor:ST-02}

*Jira summary:* {{[TEST][metrics] ST-02 — gRPC server-метрики}}
*Зависит от:* ST-00
*Профиль Demo:* {{grpc}}
*Harness-референс:* {{GrpcMetricsHarnessIT}}, {{GrpcLabelRulesHarnessIT}}

h2. Контекст

gRPC-метрики регистрируются *только* при наличии spring-grpc на classpath ({{GrpcMetricConfiguration}}). Стартер добавляет server interceptor, пишущий в {{PlatformMetrics}}.

|| Micrometer || Prometheus || Лейблы ||
| {{platform.grpc.server.received}} | {{platform_grpc_server_received_total}} | {{rpc_method}}, {{service}} |
| {{platform.grpc.server.sent}} | {{platform_grpc_server_sent_total}} | {{rpc_method}}, {{rpc_status_code}}, {{service}} |
| {{platform.grpc.server}} | {{platform_grpc_server_seconds_*}} | {{rpc_method}}, {{rpc_status_code}}, {{service}} |

*Demo-сценарии* (из {{GreeterHarnessService}}):
* {{SayHello("World")}} → {{OK}}
* {{SayHello("error-user")}} → {{INVALID_ARGUMENT}}
* {{SayHello("throw-business")}} → {{INTERNAL}}

*Если на Dev нет gRPC* — Sub-task закрывается как *Blocked* с обоснованием; HTTP (ST-01) остаётся обязательным.

{code:plantuml}
@startuml
skinparam shadowing false

title ST-02: gRPC-вызов → метрики

actor Tester
participant "gRPC Client" as Client
participant "GreeterHarnessService" as Svc
participant "Grpc Interceptor" as Int
participant "PlatformMetrics" as PM

Tester -> Client : SayHello(request)
Client -> Svc : RPC
activate Svc

Int -> PM : received++
note right of PM : platform.grpc.server.received

alt успех (World)
  Svc --> Client : HelloReply
  Int -> PM : sent++ (OK)\ntimer (OK)
else INVALID_ARGUMENT (error-*)
  Svc --> Client : onError
  Int -> PM : sent++ (INVALID_ARGUMENT)\ntimer
else INTERNAL (throw-*)
  Svc --> Client : exception
  Int -> PM : sent++ (INTERNAL)\ntimer
end

deactivate Svc
Tester -> PM : GET /actuator/prometheus
@enduml
{code}

h2. Тест-кейсы

h3. TC-09. Успешный вызов и коды ошибок

*Предусловия:* Demo с профилем {{grpc}}, доступен {{helloworld.Greeter/SayHello}}

*Шаги:* три вызова (World, error-user, throw-business) → scrape

*Ожидаемый результат:*

|| Вызов || rpc_status_code || sent count || timer count ||
| World | {{OK}} | ≥ 1 | ≥ 1 |
| error-user | {{INVALID_ARGUMENT}} | ≥ 1 | ≥ 1 |
| throw-business | {{INTERNAL}} | ≥ 1 | ≥ 1 |

* Дополнительно: {{platform_grpc_server_received_total}} с {{rpc_method="SayHello"}}, {{service="helloworld.Greeter"}}
* 1 RPC → ровно +1 к sent (без дублирования при half-close)

h3. TC-10. Disabled-tags (rpc_status_code)

*Конфигурация:* убрать {{rpc_status_code}} у {{platform.grpc.server.sent}} и {{platform.grpc.server}}

*Ожидаемый результат:* scrape без {{rpc_status_code=}}; {{rpc_method}} и {{service}} сохранены

h2. Критерии завершения тестирования

# Если gRPC на Dev *доступен:* TC-09 = *Pass* (обязательный), TC-10 = *Pass* или *Blocked*
# Если gRPC *недоступен:* Sub-task = *Blocked*, в комментарии — причина и согласование с ведущим
# Evidence: grpcurl-лог + фрагмент scrape по каждому status code
# *Done* или *Blocked* → учтено в ST-05 (gRPC-панели)

----

h1. Sub-task ST-03: Kafka-метрики {anchor:ST-03}

*Jira summary:* {{[TEST][metrics] ST-03 — Kafka consumer / producer метрики}}
*Зависит от:* ST-00
*Профиль Demo:* {{kafka}}
*Harness-референс:* {{KafkaRecordMetricsIT}}, {{KafkaRecordFailureMetricsIT}}, {{KafkaBatchMetricsIT}}

h2. Контекст

Kafka-метрики делятся на *consumer* и *producer*. Стартер оборачивает producer через {{MetricProducerPostProcessor}}; consumer-метрики пишутся через API {{PlatformMetrics}} в listener'ах Demo.

h3. Consumer

|| Micrometer || Назначение || Ключевые лейблы ||
| {{platform_consumer_processing_seconds}} | Время обработки record | {{topic}}, {{outcome}}, {{exception_class}} |
| {{platform_consumer_batch_processing_seconds}} | Время batch-обработки | те же |
| {{platform_consumer_messages_total}} | Счётчик сообщений | {{result}} = success/failure |
| {{platform_consumer_batch_messages_total}} | Размер batch | {{topic}} |
| {{platform_consumer_lag_seconds}} | Lag записи | {{topic}}, {{consumer_group}} |
| {{platform_consumer_wait_seconds}} | Ожидание в poll | {{topic}}, {{consumer_group}} |

h3. Producer

|| Micrometer || Назначение ||
| {{platform_producer_messages_total}} | Счётчик отправленных |
| {{platform_producer_lag_seconds}} | Задержка публикации |

*Demo-топики (harness):* record-metrics, fail-topic, batch-topic.

{code:plantuml}
@startuml
skinparam shadowing false

title ST-03: Kafka-сценарии Demo

rectangle "Producer (Tester / Demo)" {
  [KafkaTemplate] as KT
}

queue "Dev Kafka" {
  [record-topic] as RT
  [fail-topic] as FT
  [batch-topic] as BT
}

rectangle "Demo Consumer" {
  [RecordMetricsListener] as RL
  [BatchMetricsListener] as BL
}

database "Prometheus scrape" as PROM

KT --> RT : TC-11\n≥3 msg
KT --> FT : TC-12\n1 msg (fail)
KT --> BT : TC-13\n≥5 msg

RT --> RL
FT --> RL
BT --> BL

RL --> PROM : platform_consumer_*\nprocessing, messages_total
BL --> PROM : platform_consumer_batch_*
KT --> PROM : platform_producer_*\nTC-14

@enduml
{code}

h2. Тест-кейсы

h3. TC-11. Record listener — success

*Шаги:* опубликовать ≥ 3 сообщения в record-topic → дождаться обработки → scrape

*Ожидаемый результат:*
* {{platform_consumer_processing_seconds\{outcome="success", exception_class="none"\}}} count ≥ 1
* {{platform_consumer_messages_total\{result="success"\}}} инкрементирован
* Корректные {{topic}}, {{consumer_group}}, {{client_id}}

h3. TC-12. Record listener — failure

*Шаги:* 1 сообщение в fail-topic → scrape

*Ожидаемый результат:*
* {{platform_consumer_processing_seconds\{outcome="error"\}}} count ≥ 1
* {{exception_class}} = FQN исключения (не {{none}})
* {{platform_consumer_messages_total\{result="failure"\}}} ≥ 1

h3. TC-13. Batch listener

*Шаги:* ≥ 5 сообщений в batch-topic → дождаться batch → scrape

*Ожидаемый результат:*
* {{platform_consumer_batch_processing_seconds\{outcome="success"\}}} count ≥ 1
* {{platform_consumer_batch_messages_total}} фиксирует размер batch

h3. TC-14. Producer

*Шаги:* публикация через KafkaTemplate Demo → scrape

*Ожидаемый результат:*
* {{platform_producer_messages_total\{result="success"\}}} ≥ 1
* {{platform_producer_lag_seconds}} присутствует (если применимо)

h2. Критерии завершения тестирования

# Если Kafka на Dev *доступна:* TC-11, TC-12 = *Pass* (обязательные); TC-13, TC-14 = *Pass* или *Blocked*
# Если Kafka *недоступна:* Sub-task = *Blocked* с обоснованием
# Evidence: лог публикации + scrape по каждому топику
# *Done* или *Blocked* → учтено в ST-05 (Kafka-панели)

----

h1. Sub-task ST-04: Общие метрики и Prometheus-контракт {anchor:ST-04}

*Jira summary:* {{[TEST][metrics] ST-04 — platform.info, ADR-контракт, SLO buckets}}
*Зависит от:* ST-00, ST-01 (нужен HTTP-трафик для TC-05, TC-06)
*Harness-референс:* {{PrometheusScrapeHarnessTest}}, {{PrometheusContractHarnessTest}}

h2. Контекст

Помимо транспортных метрик (HTTP/gRPC/Kafka) стартер предоставляет *общий* контур:

* {{platform.info}} — gauge с метаданными сборки из {{build-info.json}} (endpoint {{/actuator/platform}})
* *ADR-контракт* — обязательные metric families, лейблы, запрещённые high-cardinality labels
* *SLO buckets* — дефолт {{10ms … 5s}} во всех timer/histogram метриках стартера

Проверка выполняется на scrape {{/actuator/prometheus}} после генерации HTTP-трафика (ST-01).

{code:plantuml}
@startuml
skinparam rectangle {
  BackgroundColor #F8F9FA
}

title ST-04: ADR-контракт на scrape

rectangle "Обязательные families" {
  card "platform_http_requests_total" as C1
  card "platform_http_server_requests_seconds_*" as C2
  card "platform_info" as C3
}

rectangle "SLO buckets" {
  card "le=0.01 … le=5.0\nle=+Inf" as SLO
}

rectangle "Запреты" {
  card "NO user_id\nNO session_id" as FORB
}

C2 --> SLO : histogram buckets
note bottom of C3
  Только если build-info.json
  в classpath Demo
end note

@enduml
{code}

h2. Тест-кейсы

h3. TC-05. ADR-контракт HTTP + forbidden labels

*Шаги:* {{GET /api/users/7}} → полный scrape → проверить families и лейблы

*Ожидаемый результат:*
* Присутствуют: {{platform_http_requests_total}}, {{platform_http_server_requests_seconds_bucket/count/sum}}
* У counter — лейблы {{method}}, {{path}}, {{status}} (если TC-04 не активен)
* *Нет* {{user_id=}}, {{session_id=}} в scrape

h3. TC-06. SLO histogram buckets

*Шаги:* ≥ 5 HTTP-запросов → найти {{platform_http_server_requests_seconds_bucket}}

*Ожидаемый результат:* buckets {{le="0.01"}}, {{0.025}}, {{0.05}}, {{0.1}}, {{0.25}}, {{0.5}}, {{1.0}}, {{2.5}}, {{5.0}}, {{+Inf}}

h3. TC-08. platform.info

*Предусловие:* Demo собран с {{build-info.json}}

*Шаги:* scrape + {{GET /actuator/platform}}

*Ожидаемый результат:*
* {{platform_info}} в scrape
* Метаданные сборки (версия, commit — по контракту build-info)
* {{/actuator/platform}} → 200

h2. Критерии завершения тестирования

# TC-05, TC-06 = *Pass* (обязательные)
# TC-08 = *Pass* или *Blocked* (если Demo без build-info — согласовать)
# Evidence: полный grep по families + фрагмент buckets + строка {{platform_info}}
# *Done* → разблокирован ST-05

----

h1. Sub-task ST-05: SRE Dashboard — сквозная верификация {anchor:ST-05}

*Jira summary:* {{[TEST][metrics] ST-05 — Верификация метрик на SRE Dashboard}}
*Зависит от:* ST-01 … ST-04
*Тип:* интеграционная проверка

h2. Контекст

Локальный scrape ({{/actuator/prometheus}}) подтверждает корректность экспорта на стороне Demo. Финальный шаг — убедиться, что *Prometheus на Dev* собирает метрики и *Grafana Dashboard* SRE их отображает.

Это проверка сквозной цепочки: Demo → Prometheus scrape target → TSDB → Grafana panel.

*Предусловие:* трафик уже сгенерирован в ST-01 (HTTP), ST-02 (gRPC), ST-03 (Kafka).

{code:plantuml}
@startuml
skinparam shadowing false

title ST-05: Scrape vs Dashboard

participant "Demo" as D
participant "Prometheus\n(scrape 15s–60s)" as P
database "Grafana" as G
actor "SRE / Tester" as T

D -> P : /actuator/prometheus
note over P : time series\nplatform_http_*\nplatform_grpc_*\nplatform_consumer_*

T -> D : прямой scrape\n(эталон)
T -> G : Dashboard\nLast 15 min

T -> T : сверка значений\nдопуск Δt ≤ 1–2 min

note over T
  Pass = панели не пустые,
  тренды совпадают
  с эталонным scrape
end note
@enduml
{code}

h2. Тест-кейсы

h3. TC-15. Сквозная верификация Dashboard

*Предусловия:*
# ST-01 выполнен (HTTP-трафик)
# ST-02, ST-03 — *Done* или *Blocked*
# Известна ссылка на Grafana Dashboard

*Шаги:*
# Открыть Dashboard Dev, фильтр {{platform-metrics-demo}}
# Time range: Last 15 minutes
# Сверить панели с прямым scrape

*Ожидаемый результат:*

|| Панель || Ожидание || Источник TC ||
| HTTP Request Rate | RPS > 0 | ST-01 |
| HTTP Latency p50/p95/p99 | не «No data» | ST-01, ST-04 |
| HTTP Errors 4xx/5xx | 404, 500 видны | TC-02 |
| gRPC Server | RPS / error codes | ST-02 (если не Blocked) |
| Kafka Consumer | processing, lag | ST-03 (если не Blocked) |
| Service Info | версия / platform_info | ST-04 |

* Расхождение scrape ↔ Dashboard только из-за scrape interval (≤ 1–2 min)

h2. Критерии завершения тестирования

# TC-15 = *Pass*
# Скриншоты Dashboard с time range и фильтром по сервису приложены к Sub-task
# При расхождениях — Bug или тикет SRE с target labels и scrape snippet
# *Done* → Story готова к приёмке ведущим разработчиком

----

h1. Сводная матрица: Sub-task → TC → Harness

|| Sub-task || TC || Harness-референс ||
| ST-00 | TC-00 | — |
| ST-01 | TC-01, TC-02, TC-03, TC-04, TC-07, TC-16 | {{ServletHttpMetricsHarnessTest}}, {{ServletHttpRawModeHarnessTest}}, {{HttpLabelRulesHarnessTest}}, {{DualStackHarnessTest}} |
| ST-02 | TC-09, TC-10 | {{GrpcMetricsHarnessIT}}, {{GrpcLabelRulesHarnessIT}} |
| ST-03 | TC-11, TC-12, TC-13, TC-14 | {{KafkaRecordMetricsIT}}, {{KafkaRecordFailureMetricsIT}}, {{KafkaBatchMetricsIT}} |
| ST-04 | TC-05, TC-06, TC-08 | {{PrometheusScrapeHarnessTest}}, {{PrometheusContractHarnessTest}} |
| ST-05 | TC-15 | — (интеграция с Grafana) |

{code:plantuml}
@startuml
skinparam defaultTextAlignment center

title Покрытие видов метрик Sub-task'ами

map "Виды метрик" as M {
  Smoke => [ST-00]
  HTTP => [ST-01]
  gRPC => [ST-02]
  Kafka => [ST-03]
  Общие\n(platform.info,\nADR, SLO) => [ST-04]
  SRE Dashboard => [ST-05]
}
@enduml
{code}

h1. Чеклист для исполнителя (мидл Java-разработчик)

# Получить версию стартера и URL Demo от ведущего разработчика
# Создать Sub-task'и ST-00 … ST-05 в Jira (скопировать Description из соответствующих разделов)
# Выполнять в порядке: ST-00 → ST-01/02/03 (параллельно) → ST-04 → ST-05
# Blocked-кейсы (нет Kafka/gRPC на Dev) — явно пометить в Sub-task
# Все FAIL — Bug с логами Demo и фрагментом scrape
# Закрывать каждый Sub-task только при выполнении *Критериев завершения*

h1. Шаблон отчёта (комментарий к Sub-task)

|| TC || Статус || Evidence ||
| TC-XX | Pass / Fail / Blocked | ссылка |

*Итог Sub-task:* Done / Blocked — *причина*

h1. Шаблон сводки (комментарий к Story)

|| Sub-task || Статус || Blocked TC || Открытые Bug ||
| ST-00 | | | |
| ST-01 | | | |
| ST-02 | | | |
| ST-03 | | | |
| ST-04 | | | |
| ST-05 | | | |

----

h2. APPENDIX: Полные описания TC (детальные шаги)

{panel:title=Обозначения|borderStyle=solid}
Детальные шаги для исполнителя. В Sub-task'ах выше — сжатые версии; здесь — полные curl-команды и таблицы.
{panel}

h3. TC-00. Smoke: деплой Demo и доступность Actuator

*Приоритет:* Blocker
*Тип:* Smoke

h3. Предусловия

# Demo {{platform-metrics-demo}} задеплоен на Dev с новой версией {{spring-boot-starter-platform-metrics}}
# Известны URL сервиса и Actuator endpoints
# У тестировщика есть доступ к {{/actuator/prometheus}} и {{/actuator/health}}

h3. Шаги

# Выполнить: {{GET /actuator/health}}
# Выполнить: {{GET /actuator/prometheus}}
# Убедиться, что приложение в логах стартовало без ошибок autoconfiguration, связанных с {{space.br1440.platform.metrics}}

h3. Ожидаемый результат

* {{/actuator/health}} → HTTP 200, статус UP
* {{/actuator/prometheus}} → HTTP 200, тело в формате Prometheus text exposition
* В scrape присутствуют стандартные JVM-метрики ({{jvm_*}}) — стартер не ломает базовый экспорт
* Bean {{PlatformMetrics}} зарегистрирован (косвенно: после HTTP-запроса появляются {{platform_http_*}})

h3. Evidence

* Вывод curl для health и первых 50 строк prometheus

----

h2. TC-01. HTTP: normalized path (режим по умолчанию)

*Приоритет:* Critical
*Профиль Demo:* default (Servlet)

h3. Предусловия

# {{platform.metrics.http.path-mode=normalized}} (дефолт, явно задавать не обязательно)
# TC-00 пройден

h3. Шаги

# Выполнить 3 запроса с разными ID:
{code:bash}
curl -s -o /dev/null -w "%{http_code}" https://<demo-host>/api/users/42
curl -s -o /dev/null -w "%{http_code}" https://<demo-host>/api/users/10758
curl -s -o /dev/null -w "%{http_code}" https://<demo-host>/api/users/10761
{code}
# Скачать scrape: {{GET /actuator/prometheus}}
# Найти строки {{platform_http_requests_total}} и {{platform_http_server_requests_seconds_count}}

h3. Ожидаемый результат

* Все три запроса → HTTP 200
* В метриках *один* normalized path: {{path="/api/users/\{id\}"}} (не raw ID в path)
* Counter {{platform_http_requests_total}} с лейблами {{method="GET"}}, {{path="/api/users/\{id\}"}}, {{status="200"}} — значение ≥ 3 (или ≥ 1, если серия уже существовала и инкрементируется)
* Timer {{platform_http_server_requests_seconds}} — {{count}} ≥ 3
* Кардинальность path *не растёт* от количества разных ID (3 разных ID → 1 уникальный path)

h3. Проверка Dashboard

* На панели HTTP RPS / latency по сервису {{platform-metrics-demo}} отображается трафик на endpoint {{/api/users/\{id\}}}
* Нет всплеска уникальных значений path при одинаковом шаблоне маршрута

h3. Evidence

* Фрагмент scrape с {{platform_http_requests_total\{.*path="/api/users/\{id\}".*\}}}
* Скриншот Dashboard (если доступен)

----

h2. TC-02. HTTP: специальные path — ROOT, REDIRECTION, 404, 5xx

*Приоритет:* Critical

h3. Шаги

# {{GET /}} → ожидается 200
# {{GET /redirect}} → ожидается 3xx
# {{GET /does-not-exist}} → ожидается 404
# {{GET /api/error}} → ожидается 500
# Скачать scrape

h3. Ожидаемый результат

|| Запрос || path в метрике || status ||
| {{/}} | {{ROOT}} | {{200}} |
| {{/redirect}} | {{REDIRECTION}} | {{302}} или другой 3xx |
| {{/does-not-exist}} | {{UNKNOWN}} | {{404}} |
| {{/api/error}} | шаблон маршрута (например {{/api/error}}) | {{500}} |

* Для каждого сценария существует серия {{platform_http_requests_total}} с корректными лейблами
* Timer {{platform_http_server_requests_seconds}} инкрементируется для каждого обработанного запроса

h3. Проверка Dashboard

* 404 и 5xx видны на панелях error rate / status breakdown

h3. Evidence

* 4 фрагмента scrape (или одна таблица соответствий запрос → лейблы)

----

h2. TC-03. HTTP: raw path mode

*Приоритет:* Major

h3. Предусловия

# Demo перезапущен с конфигурацией:
{code:yaml}
platform:
  metrics:
    http:
      path-mode: raw
{code}

h3. Шаги

# {{GET /api/users/42}}
# {{GET /api/users/99}}
# Скачать scrape

h3. Ожидаемый результат

* В метриках *два разных* raw path: {{/api/users/42}} и {{/api/users/99}} (без нормализации до {{\{id\}}})
* Остальные лейблы ({{method}}, {{status}}) корректны

h3. Примечание

После кейса вернуть Demo в режим {{normalized}} для остальных тестов.

h3. Evidence

* Scrape с двумя distinct path

----

h2. TC-04. HTTP: отключение лейблов (disabled-tags)

*Приоритет:* Major

h3. Предусловия

# Demo перезапущен с:
{code:yaml}
platform:
  metrics:
    labels:
      metrics:
        "[platform.http.requests]":
          disabled-tags:
            - status
        "[platform.http.server.requests]":
          disabled-tags:
            - status
{code}

h3. Шаги

# {{GET /api/users/1}}
# Скачать scrape, найти {{platform_http_requests_total}}

h3. Ожидаемый результат

* Строки {{platform_http_requests_total}} *не содержат* лейбл {{status=}}
* Аналогично для {{platform_http_server_requests_seconds_*}} — без {{status=}}
* Лейблы {{method}} и {{path}} присутствуют

h3. Evidence

* Фрагмент scrape

----

h2. TC-05. Prometheus: ADR-контракт HTTP-метрик

*Приоритет:* Critical

h3. Шаги

# Сгенерировать трафик: {{GET /api/users/7}}
# Скачать полный scrape
# Проверить наличие обязательных families:
#* {{platform_http_requests_total}}
#* {{platform_http_server_requests_seconds_bucket}}
#* {{platform_http_server_requests_seconds_count}}
#* {{platform_http_server_requests_seconds_sum}}
#* {{platform_info}} (если в Demo есть build-info.json)

h3. Ожидаемый результат

* Все перечисленные metric families присутствуют
* У {{platform_http_requests_total}} есть лейблы {{method}}, {{path}}, {{status}} (если TC-04 не активен)
* В scrape *отсутствуют* forbidden labels: {{user_id}}, {{session_id}}

h3. Evidence

* Scrape или результат grep по families

----

h2. TC-06. Prometheus: SLO histogram buckets

*Приоритет:* Critical

h3. Шаги

# Сгенерировать HTTP-трафик (≥ 5 запросов к {{/api/users/\{id\}}})
# В scrape найти {{platform_http_server_requests_seconds_bucket}}
# Проверить наличие {{le=}} для всех дефолтных порогов

h3. Ожидаемый результат

Присутствуют buckets (как минимум):
* {{le="0.01"}} (10ms)
* {{le="0.025"}} (25ms)
* {{le="0.05"}} (50ms)
* {{le="0.1"}} (100ms)
* {{le="0.25"}} (250ms)
* {{le="0.5"}} (500ms)
* {{le="1.0"}} (1s)
* {{le="2.5"}} (2500ms)
* {{le="5.0"}} (5s)
* {{le="+Inf"}}

h3. Проверка Dashboard

* Histogram / percentile панели (p50, p95, p99) для HTTP latency отображают данные, не «No data»

h3. Evidence

* Фрагмент scrape с bucket-линиями

----

h2. TC-07. HTTP: контроль кардинальности path

*Приоритет:* Major

h3. Шаги

# В режиме {{normalized}} выполнить 10+ запросов к {{/api/users/\{i\}}} с разными ID
# Выполнить несколько 404
# Подсчитать уникальные значения лейбла {{path}} в {{platform_http_requests_total}}

h3. Ожидаемый результат

* Число уникальных {{path}} остаётся ограниченным (для harness-контракта ≤ 10)
* Нет серий вида {{path="/api/users/10758"}} в normalized-режиме
* Raw ID не «протекают» в path-лейбл

h3. Evidence

* Список уникальных path из scrape

----

h2. TC-08. platform.info и build metadata

*Приоритет:* Major

h3. Предусловия

# Demo собран с генерацией {{build-info.json}} (стандартный pipeline платформенных стартеров)

h3. Шаги

# {{GET /actuator/prometheus}} — найти {{platform_info}}
# {{GET /actuator/platform}} или {{/actuator/info}} — если endpoint доступен

h3. Ожидаемый результат

* Gauge {{platform_info}} присутствует в scrape
* Значение / лейблы содержат метаданные сборки (версия артефакта, git commit и т.д. — по фактическому контракту build-info)
* Endpoint {{/actuator/platform}} отвечает 200 (при включении в exposure)

h3. Evidence

* Строка {{platform_info}} из scrape

----

h2. TC-09. gRPC: успешный вызов и коды ошибок

*Приоритет:* Critical
*Профиль Demo:* {{grpc}}

h3. Предусловия

# Demo запущен с профилем {{grpc}}, gRPC server доступен
# Известен proto-сервис (harness: {{helloworld.Greeter/SayHello}})

h3. Шаги

# Успешный вызов: {{SayHello(name="World")}}
# Ошибка приложения: {{SayHello(name="error-user")}} → ожидается INVALID_ARGUMENT
# Бизнес-исключение: {{SayHello(name="throw-business")}} → ожидается INTERNAL
# Скачать scrape

h3. Ожидаемый результат

|| Вызов || platform_grpc_server_sent_total {{rpc_status_code}} || platform_grpc_server_seconds_count ||
| World | {{OK}} | ≥ 1 |
| error-user | {{INVALID_ARGUMENT}} | ≥ 1 |
| throw-business | {{INTERNAL}} | ≥ 1 |

* {{platform_grpc_server_received_total}} инкрементируется для {{rpc_method="SayHello"}}, {{service="helloworld.Greeter"}}
* Повторные ошибки не дублируют sent-счётчик на один RPC (1 RPC → +1 к соответствующему status)

h3. Проверка Dashboard

* gRPC RPS и error rate по {{rpc_status_code}} корректны

h3. Evidence

* grpcurl/BloomRPC лог + фрагмент scrape

----

h2. TC-10. gRPC: disabled-tags для rpc_status_code

*Приоритет:* Major
*Профиль:* {{grpc}}

h3. Предусловия

# Demo с конфигурацией:
{code:yaml}
platform:
  metrics:
    labels:
      metrics:
        "[platform.grpc.server.sent]":
          disabled-tags:
            - rpc_status_code
        "[platform.grpc.server]":
          disabled-tags:
            - rpc_status_code
{code}

h3. Шаги

# Выполнить успешный и ошибочный gRPC-вызов
# Проверить scrape

h3. Ожидаемый результат

* {{platform_grpc_server_sent_total}} и {{platform_grpc_server_seconds_*}} *без* лейбла {{rpc_status_code=}}
* {{rpc_method}} и {{service}} сохраняются

h3. Evidence

* Scrape

----

h2. TC-11. Kafka Consumer: record listener (success)

*Приоритет:* Critical
*Профиль Demo:* {{kafka}}

h3. Предусловия

# Dev Kafka доступна, Demo подписан на тестовый topic (harness: topic record-metrics)
# Известны {{topic}}, {{consumer_group}}, {{client_id}}

h3. Шаги

# Опубликовать ≥ 3 сообщения в тестовый topic
# Дождаться обработки consumer'ом
# Скачать scrape

h3. Ожидаемый результат

* {{platform_consumer_processing_seconds_count}} ≥ 1 с {{outcome="success"}}, {{exception_class="none"}}
* {{platform_consumer_messages_total}} с {{result="success"}} инкрементируется
* Метрики содержат корректные {{topic}}, {{consumer_group}}, {{client_id}}

h3. Проверка Dashboard

* Consumer lag / processing time панели показывают активность

h3. Evidence

* Kafka producer log + scrape

----

h2. TC-12. Kafka Consumer: record listener (failure)

*Приоритет:* Critical
*Профиль:* {{kafka}}

h3. Шаги

# Опубликовать сообщение в fail-topic (harness: топик, обработка которого завершается ошибкой)
# Скачать scrape

h3. Ожидаемый результат

* {{platform_consumer_processing_seconds}} с {{outcome="error"}}
* {{exception_class}} содержит FQN исключения (не {{none}})
* {{platform_consumer_messages_total\{result="failure"\}}} инкрементируется

h3. Evidence

* Scrape с error-сериями

----

h2. TC-13. Kafka Consumer: batch listener

*Приоритет:* Major
*Профиль:* {{kafka}}

h3. Шаги

# Опубликовать batch сообщений (≥ 5) в batch-topic
# Дождаться batch-обработки

h3. Ожидаемый результат

* {{platform_consumer_batch_processing_seconds_count}} ≥ 1, {{outcome="success"}}
* {{platform_consumer_batch_messages_total}} фиксирует размер batch

h3. Evidence

* Scrape

----

h2. TC-14. Kafka Producer (если Demo публикует сообщения)

*Приоритет:* Major
*Профиль:* {{kafka}}

h3. Шаги

# Выполнить публикацию через KafkaTemplate / producer API Demo
# Скачать scrape

h3. Ожидаемый результат

* {{platform_producer_messages_total\{result="success"\}}} ≥ 1
* При наличии задержки публикации — {{platform_producer_lag_seconds}} присутствует

h3. Evidence

* Scrape

----

h2. TC-15. SRE Dashboard: сквозная верификация

*Приоритет:* Critical

h3. Предусловия

# TC-01, TC-02, TC-06 выполнены (HTTP-трафик сгенерирован)
# TC-09 выполнен (если есть gRPC)
# TC-11 выполнен (если есть Kafka)
# Известна ссылка на Dashboard платформенных метрик

h3. Шаги

# Открыть SRE Dashboard для Dev
# Выбрать сервис {{platform-metrics-demo}}
# Установить time range: Last 15 minutes
# Сверить панели с результатами локального scrape

h3. Ожидаемый результат

|| Панель (ориентир) || Ожидание ||
| HTTP Request Rate | RPS > 0, серия видна |
| HTTP Latency (p50/p95/p99) | Графики не пустые, порядок величин разумен |
| HTTP Errors (4xx/5xx) | 404 и 500 с TC-02 отражены |
| gRPC Server (если есть) | RPS / error codes по статусам |
| Kafka Consumer (если есть) | Processing time, messages consumed |
| Service Info | {{platform_info}} / версия видна |

* Расхождение scrape ↔ Dashboard отсутствует или объяснимо задержкой scrape interval (≤ 1–2 min)

h3. Evidence

* Скриншоты Dashboard с time range и фильтром по сервису
* При расхождении — тикет SRE с labels target и scrape snippet

----

h2. TC-16. Dual-stack: Servlet vs WebFlux (опционально)

*Приоритет:* Minor

h3. Предусловия

# На Dev есть два инстанса Demo (или один с переключением):
#* Servlet: {{spring.main.web-application-type=servlet}}
#* WebFlux: профиль {{webflux}}

h3. Шаги

# Для Servlet: HTTP-запрос + проверить наличие {{HttpMetricsFilter}}, отсутствие WebFlux-фильтра в actuator/beans (или по логам)
# Для WebFlux: HTTP-запрос + метрики пишутся через {{WebfluxMetricsFilter}}

h3. Ожидаемый результат

* В servlet-режиме активен только Servlet-фильтр; в webflux — только WebFlux-фильтр
* HTTP-метрики в обоих случаях соответствуют ADR (имена и лейблы идентичны)

h3. Evidence

* Scrape из обоих инстансов

h1. Связанные артефакты

* Репозиторий стартера: {{E:\Platform_SpringBoot_Starters\spring-boot-platform-starters-master\spring-boot-starter-platform-metrics}}
* Harness (референс сценариев): {{E:\Platform_SpringBoot_Starters\platform-metrics-harness}}
* README стартера: конфигурация {{path-mode}}, {{disabled-tags}}
* ADR эталонные метрики: [Confluence|https://c.1440.space/pages/viewpage.action?pageId=508642331]

h1. Риски

|| Риск || Митигация ||
| На Dev нет Kafka / gRPC | ST-02, ST-03 = Blocked; ST-01 обязателен |
| Dashboard не фильтрует новый Demo | Согласовать с SRE service label / job name до начала |
| Старая версия стартера в кэше Maven | Явно указать версию в build Demo, проверить {{platform_info}} |

{panel:title=Контакты|borderStyle=solid}
*Ведущий разработчик (автор rewrite):* указать ФИО / Mattermost
*SRE (Dashboard):* указать контакт
*DevOps (деплой Demo):* указать контакт
{panel}
