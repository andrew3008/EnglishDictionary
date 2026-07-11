# Аналитический review плана Фазы 12 (Custom Propagation) — best practices + исходники

| Поле | Значение |
|------|----------|
| Тип документа | Independent research review плана `phase_12_custom_propagation_b841cbc2.plan.md` |
| Дата | 2026-06-09 |
| Автор | Анализ по внешним стандартам/статьям + аудит исходников репозитория |
| Статус | Рекомендации к плану (не заменяет план; дополняет разделы 3a/5/9) |
| Контекст версий | OTel BOM `1.62.0`, OTel instrumentation/agent `2.28.x`, Spring Boot `3.5.5`, Java 21 |

> Назначение: проверить и усилить план Фазы 12 независимым исследованием W3C/OpenTelemetry/OWASP и
> коммерческих исходников (Datadog, Elastic, New Relic, OTel instrumentation) + сверкой с фактическим
> кодом репозитория. Документ выделяет (1) что подтверждено, (2) фактические ошибки/неточности плана,
> (3) новые рекомендации с привязкой к PR.

---

## 1. Executive summary

План Фазы 12 архитектурно зрелый: agent-compatible подход, secure-by-default DENY, context-first
сэмплирование и отказ от дублирования W3C/span'ов Агента — всё это соответствует индустриальным
best practices 2025–2026. Большинство тезисов раздела 3a плана подтверждается первоисточниками.

Однако исследование выявило **один блокирующий архитектурный дефект** и несколько фактических
неточностей, которые нужно устранить до старта реализации:

1. **[BLOCKER] Classloader-граница `TrustedDestinationMatcher`.** Класс физически живёт в
   agent-only модуле `platform-tracing-otel-extension`, а план делает на нём Spring-бин
   `OutboundPropagationPolicy` в app-classloader. Класс не виден main-classpath автоконфигурации.
2. **[FACT-FIX] Baggage DoS** — план ссылается на неверный advisory/issue (Go `GHSA-mh2q-q3fh-2475`,
   `#8378`). Реальный Java-фикс — `GHSA-rcgg-9c38-7xpx` / `#8380`, влит в **1.62.0** (текущий BOM),
   лимиты 8192 байта / 64 entries уже принудительно применяются на уровне propagator'а.
3. **[FACT-FIX] CRLF-санитизация** — комментарий плана «`setHeader` санитизирует CRLF» неверен для
   `HttpHeaders.set()` и исходящих клиентов; полагаться на framework нельзя.
4. **[REFINE] `propagateForceTrace=false`** корректен по умолчанию, но обоснование требует явной
   оговорки про parent-based sampling downstream (sampled-flag — best-effort, не гарантия).

Ниже — детально, с источниками и привязкой к PR.

---

## 2. Методология и источники

Сверка велась по первоисточникам и коммерческой практике, затем — аудит кода репозитория.

- W3C Trace Context (spec + rationale + processing model): [20-http_request_header_format](https://github.com/w3c/trace-context/blob/main/spec/20-http_request_header_format.md), [30-processing-model](https://github.com/w3c/trace-context/blob/main/spec/30-processing-model.md), [Trace Context Level 2 CR](https://www.w3.org/TR/2023/CR-trace-context-2-20230418/).
- OpenTelemetry: [context-propagation security best practices (PR #8664)](https://github.com/open-telemetry/opentelemetry.io/commit/2eff1a357d20c7b9ba6e07f8c5b1ac057ac005c7), [Baggage concept](https://opentelemetry.io/docs/concepts/signals/baggage/), [span suppression / disable](https://opentelemetry.io/docs/zero-code/java/agent/disable/).
- Security advisory (Java baggage): [GHSA-rcgg-9c38-7xpx — Unbounded Memory Allocation in W3C Baggage Propagation](https://github.com/open-telemetry/opentelemetry-java/security/advisories/GHSA-rcgg-9c38-7xpx).
- Kafka: [ProducerInterceptor javadoc 3.7](https://kafka.apache.org/37/javadoc/org/apache/kafka/clients/producer/ProducerInterceptor.html), [Confluent producer configs](https://docs.confluent.io/platform/current/installation/configuration/producer-configs.html), [KIP-512](https://cwiki.apache.org/confluence/display/KAFKA/KIP-512%3A+make+Record+Headers+available+in+onAcknowledgement).
- CWE-113 / header injection: [CWE-113](https://cwe.mitre.org/data/definitions/113.html), [Request Smuggling vs Splitting in Spring Boot](https://dev.to/securitystefan/request-smuggling-vs-request-splitting-in-spring-boot-4fa).
- Коммерческие исходники: `Platform_Traces_Examples\src` (opentelemetry-java-instrumentation, dd-trace-java, apm-agent-java, newrelic-java-agent).
- Аудит кода: `platform-tracing-api`, `platform-tracing-otel-extension`, `platform-tracing-spring-boot-autoconfigure` (`build.gradle`, классы propagation).

---

## 3. [BLOCKER] Classloader-граница: `TrustedDestinationMatcher` и `OutboundPropagationPolicy`

### Что нашлось в коде
Сверка фактических пакетов «готовых outbound-контрактов» из плана:

| Контракт | Модуль | App-classloader видимость |
|----------|--------|---------------------------|
| `OutboundPropagationDecision` | `platform-tracing-api` | да |
| `PlatformTraceContextKeys` | `platform-tracing-api` | да |
| `InboundTraceControl` | `platform-tracing-api` | да |
| `PlatformHeaders` | `platform-tracing-api` | да |
| **`TrustedDestinationMatcher`** | **`platform-tracing-otel-extension`** | **нет (agent-only)** |

`platform-tracing-spring-boot-autoconfigure/build.gradle` подключает `platform-tracing-otel-extension`
**только как `testImplementation`** (строка 67), не как `api`/`implementation`. Значит main-код
автоконфигурации физически не компилируется против `TrustedDestinationMatcher`, а в рантайме под
Агентом класс грузится agent-classloader'ом и недоступен Spring-бинам приложения (ровно проблема
dual-channel из `ADR-dual-channel-properties-v0.1`).

### Почему это блокер для текущего плана
- PR-1 объявляет `OutboundPropagationPolicy` Spring-бином, который использует `TrustedDestinationMatcher`.
- PR-2/PR-3 — HTTP-интерсепторы — Spring-бины в `-webmvc`/`-webflux` (app-classloader).
- Эти модули не видят `TrustedDestinationMatcher` ни на compile, ни на runtime.
- План в §10.1 уже верно вынес `TraceControlHeaderInjector` в `platform-tracing-api` именно из-за
  classloader-границы, **но не распространил тот же вывод на `TrustedDestinationMatcher` и не отметил,
  что matcher застрял в agent-модуле.** §1 плана перечисляет его среди «готовых контрактов» без оговорки.

### Рекомендация (в PR-0/PR-1, до написания интерсепторов)
1. **Перенести `TrustedDestinationMatcher`** (и glob-компиляцию хостов) в `platform-tracing-api`
   (пакет `...api.propagation.control` рядом с `OutboundPropagationDecision`). У класса нет
   OTel-SDK-зависимостей — перенос безопасен.
2. **Разместить `OutboundPropagationPolicy`** в `platform-tracing-api` (чистый POJO) либо в
   автоконфигурации; бин создаётся в app-classloader из `TracingProperties`.
3. Если `InboundTraceControlPropagator.extract()` (agent-side) тоже использует matcher — оставить
   там import уже из `api` (агентный jar включает api-классы, см. `agentExtensionJar`), drift не возникает.
4. Зафиксировать в `ADR-outbound-propagation`: **единственный источник истины outbound-типов —
   `platform-tracing-api`** (app + agent classloader видят один и тот же класс).

> Это не косметика: без переноса PR-1..PR-3 не скомпилируются/упадут с `NoClassDefFoundError` под Агентом.

---

## 4. Фактические исправления (FACT-FIX)

### 4.1. Baggage DoS — неверная ссылка, и фикс УЖЕ в текущем BOM
План (§3a, §6, §9) ссылается на `#8378` и Go-advisory `GHSA-mh2q-q3fh-2475`. Реально:
- Java-уязвимость — **`GHSA-rcgg-9c38-7xpx`**, фикс **`#8380`**, релиз **`1.62.0`**.
- Лимиты, принудительно применяемые на уровне `W3CBaggagePropagator`: **8192 байта суммарно, 64 entries**;
  превышающие заголовки дропаются в точке достижения лимита, уже извлечённые валидные entries сохраняются.
- Текущий `gradle.properties` репозитория: `openTelemetryBomVersion=1.62.0` → **фикс уже включён**.

Следствия для плана:
- Тезис «end-to-end лимиты унаследованы от stock» теперь **фактически верен** (на 1.62.0), но
  обоснование надо переписать на правильный advisory/issue/версию.
- Регресс-тест на multi-value `baggage` (PR-6) остаётся полезен как guard от регрессии при будущих
  bump'ах, но это **не митигация неизвестной дыры**, а защита уже закрытого инварианта — формулировку смягчить.
- Доп. defense-in-depth (из OTel security docs и advisory): **держать server/gateway header-limit ~8 KiB**
  (Tomcat/Netty/Jetty default) — добавить в SUPPORTED.md как операционное требование (PR-8).

### 4.2. CRLF / CWE-113 — `setHeader` НЕ санитизирует
Комментарий плана в §10.3 `req.getHeaders().set(key, value); // setHeader санитизирует CRLF` неверен:
- `org.springframework.http.HttpHeaders.set()` и исходящие клиенты (`RestTemplate`/`RestClient`/`WebClient`)
  **не гарантируют** strip CR/LF; контейнерная защита (Tomcat) касается серверного response, а не
  client-исходящих заголовков. Подтверждено индустрией: «Spring's `HttpHeaders.set()`, `RestTemplate`
  forwarding ... do not always go through the same validation path».
- Корректный подход (OWASP A03/CWE-113): **санитизация в точке возникновения значения**, allowlist-формат,
  strip `\r`/`\n`/`\x00`/control-chars, cap длины — что план и делает в `RequestIdSupport` (§10.6). Это правильно.

Рекомендация:
- Убрать/исправить комментарий «setHeader санитизирует»; явно указать, что безопасность обеспечивается
  тем, что в `X-Request-Id`/Kafka-заголовки попадают **только** уже санитизированные (`RequestIdSupport`)
  или контролируемые литералы (`"on"`, `"1"`) — не сырой пользовательский ввод.
- Вынести strip-утилиту в общий хелпер `platform-tracing-api` и применять её на **всех** путях записи
  платформенных заголовков (HTTP outbound, Kafka outbound, response-фильтры) — единая точка, как
  рекомендует Spring-Boot-security практика («shared utility ... enforce via CI, not only code review»).
- Добавить статическую проверку (Semgrep/ArchUnit) на отсутствие записи несанитизированных значений в заголовки.

### 4.3. W3C: `propagateForceTrace=false` — верно, но с оговоркой
Подтверждено spec'ом: на исходящем запросе vendor **MAY** выставить `sampled=1`, «Setting the flag is no
guarantee that the trace will be recorded but increases the likeliness of end-to-end recorded traces».
То есть sampled-flag — **best-effort**, а не форс.

- Для downstream с **parent-based** sampler (дефолт OTel) проброшенный sampled=1 → запись продолжается.
  Значит дефолт `false` корректен и снижает header-surface.
- **Оговорка, которую нужно явно внести в `ADR-outbound-propagation`:** если downstream использует
  head-ratio sampler, игнорирующий parent (re-sample на edge зоны), sampled-flag не форсирует запись —
  тогда `X-Trace-On` (opt-in `propagateForceTrace=true`) остаётся единственным механизмом форса.
  Сформулировать как явный «escape hatch», а не просто «редкий boundary-кейс».
- W3C-инвариант для custom sampler подтверждён: `tracestate` нельзя терять при неизменном `traceparent`,
  и нельзя удалять чужие ключи. Регресс-assert «`CompositeSampler` сохраняет parent tracestate» (план §7) —
  оставить как обязательный.

---

## 5. Подтверждённые тезисы плана (оставить как есть)

- **Agent-compatible, без дублей span'ов** — подтверждено: дефолтная стратегия Агента
  `otel.instrumentation.experimental.span-suppression-strategy=semconv` подавляет вложенные дубли;
  опора PR-7 на неё корректна. Дополнительно доступен точечный `-Dotel.instrumentation.<name>.enabled=false`
  как governance-рычаг — упомянуть в PR-7/PR-8.
- **Async messaging через links** — подтверждено: `otel.instrumentation.messaging.experimental.receive-telemetry.enabled=true`
  заставляет consumer начинать новый trace со span-link на producer. Согласуется с `KafkaBatchLinksAspect`.
- **Baggage инжектит Агент из глобальной цепочки; outbound-интерсепторы baggage не трогают** — корректно
  и безопасно (меньше точек отказа).
- **Secure-by-default DENY + trusted-gating для external** — прямо рекомендовано OTel security docs
  («configure your propagators to not send context to external or public-facing endpoints»).
- **X-Request-Id = per-hop id, generate-if-absent, validate-and-reuse** — соответствует Envoy/Nginx/ALB
  практике; «золотое окно» чистого cutover без legacy-compat обоснован (решение не в проде).
- **tracestate как носитель force-trace отклонён** — корректно: tracestate vendor-ограничен, и его нельзя
  использовать как управляющий канал без нарушения W3C-семантики.

---

## 6. Новые рекомендации (по приоритету)

### P0 — до реализации
- **R1 (BLOCKER):** перенести `TrustedDestinationMatcher` + `OutboundPropagationPolicy` в `platform-tracing-api`
  (раздел 3). Внести в PR-0 как явный шаг и зафиксировать в `ADR-outbound-propagation`.
- **R2:** переписать обоснование baggage-DoS на корректный advisory `GHSA-rcgg-9c38-7xpx` / `#8380` / `1.62.0`;
  смягчить формулировку multi-value регресса до «guard от регрессии» (раздел 4.1).

### P1 — в соответствующих PR
- **R3 (PR-2/3/4/5):** единая strip-утилита для CR/LF/control/`\x00` в `platform-tracing-api`, применяемая
  на всех путях записи платформенных заголовков; убрать неверный комментарий про `setHeader` (раздел 4.2).
- **R4 (PR-0/ADR):** явно описать `propagateForceTrace=false` + escape-hatch для downstream, не уважающих
  parent sampled-flag (раздел 4.3).
- **R5 (PR-5, Kafka):** учесть контракт `ProducerInterceptor`:
  - исключения из `onSend()` Kafka **сам** перехватывает/логирует/не пробрасывает → платформа уже fail-safe
    на уровне клиента; всё равно обернуть внутренней изоляцией (defense-in-depth), но **не рассчитывать на
    проброс** ошибок наверх;
  - `onSend()` исполняется на **producer-потоке** → inject обязан быть неблокирующим (нет I/O) — у плана так и есть;
  - интерсептор делит namespace producer-config: использовать уникальный неконфликтный ключ
    (`platform.tracing.kafka.outbound-policy`) и учесть, что Kafka **логирует WARN об «unknown config»**
    для кастомных ключей — задокументировать как ожидаемое (или прятать через client-id-keyed реестр, если шум критичен);
  - headers недоступны в `onAcknowledgement` (KIP-512 ещё не везде) — для инъекции это не нужно, но
    зафиксировать, чтобы не строить на этом диагностику.
- **R6 (PR-6):** ingress-валидация Kafka/baggage — fail-closed allowlist + audit отклонённых (one-shot/rate-limited),
  как в OTel security docs и oneuptime; без падения бизнес-обработки.

### P2 — операционное/документация (PR-8)
- **R7:** в SUPPORTED.md зафиксировать операционное требование: server/gateway header-limit ~8 KiB
  (defense-in-depth к baggage/headers), и рекомендуемые agent-флаги (`receive-telemetry`, `span-suppression-strategy`).
- **R8:** добавить в раздел тестов «propagator config mismatch» (W3C vs B3) как известный класс сбоев
  propagation — smoke/док, чтобы SRE быстро диагностировал фрагментированные трейсы.

---

## 7. Привязка к PR плана (дельта)

| PR | Что добавить/исправить |
|----|------------------------|
| PR-0 | R1 (перенос matcher/policy в api + ADR-инвариант «outbound-типы в api»), R2 (правка baggage-обоснования), R4 (форс-escape-hatch в ADR) |
| PR-1 | `OutboundPropagationPolicy` в api/autoconfigure (не в extension); юнит-тесты на classloader-нейтральность |
| PR-2/PR-3 | R3 (strip-утилита; убрать миф про setHeader) |
| PR-4 | R3 (общая strip-утилита вместо локального паттерна в `RequestIdSupport`) |
| PR-5 | R5 (контракт ProducerInterceptor: fail-safe, producer-thread, config-namespace WARN) |
| PR-6 | R6 (fail-closed ingress allowlist + audit); смягчить формулировку baggage-DoS |
| PR-7 | подтвердить опору на `semconv` + перечислить `-Dotel.instrumentation.<name>.enabled=false` как рычаг |
| PR-8 | R7 (header-limit + agent-флаги в SUPPORTED.md), R8 (propagator-mismatch диагностика) |

---

## 8. Что НЕ менять (явные anti-recommendations)
- Не вводить отдельные модули `propagation-http`/`propagation-kafka` — объём кода мал, решение плана верное.
- Не переинжектить W3C/baggage в платформенных интерсепторах — это зона Агента; дубли = деградация.
- Не использовать `tracestate` как управляющий канал force-trace (vendor-ограничения + W3C-семантика).
- Не строить собственный circuit breaker/ретраи на propagation-пути — propagation должен быть O(1) и
  неблокирующим (согласуется с решением Фазы 10/11 по экспортёру).

---

## 9. Итог
План Фазы 12 готов к реализации после устранения **R1 (classloader-граница `TrustedDestinationMatcher`)**
и фактических правок **R2/R3**. Остальные рекомендации (R4–R8) повышают точность безопасности и
эксплуатационную наблюдаемость, но не блокируют старт. Ключевые архитектурные решения плана
(agent-compatible, secure-by-default, X-Request-Id = per-hop id, отказ от tracestate-форса) подтверждены
первоисточниками W3C/OpenTelemetry и коммерческой практикой.
