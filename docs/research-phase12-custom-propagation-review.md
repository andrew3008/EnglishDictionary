# Аналитический обзор плана Фазы 12 (Custom Propagation для HTTP и Kafka)

> Документ-ревью к плану `phase_12_custom_propagation_b841cbc2.plan.md`.
> Цель — независимое исследование best practices, профессиональных статей и
> коммерческих исходников с конкретными рекомендациями по улучшению плана
> **перед его исполнением**. Документ не заменяет план, а дополняет его
> приоритизированными правками и трассируемостью к PR-ам.

## 0. Резюме (TL;DR)

План **зрелый, архитектурно корректный и соответствует индустриальному стандарту**
по большинству пунктов: agent-compatible режим без дублирования span'ов и W3C,
secure-by-default DENY на outbound, trusted-host/topic gating, опора на sampled-flag
вместо проброса `X-Trace-On`, span links для async (Kafka), отказ от SDK-side
overengineering. Раздел «3a» уже содержит сильное собственное исследование.

Тем не менее, глубокое исследование выявило **один концептуальный риск и несколько
конкретных усилений** (часть — на уровне уже написанного кода), которые стоит внести
в план **до начала реализации**:

| # | Рекомендация | Приоритет | Затрагивает |
|---|---|---|---|
| R1 | Реконсилировать семантику `X-Request-Id`: план называет её «per-hop, не для сквозного поиска», но фактическое поведение (preserve+forward) и индустрия (Envoy/Istio/Nginx) — это **edge-stable correlation id** | **P0 (концепт)** | PR-0, PR-4, раздел 6 |
| R2 | Усилить `TrustedDestinationMatcher` против allowlist-bypass / SSRF-подобных обходов (port/trailing-dot/IDN/substring-glob) | **P0 (security)** | PR-1 |
| R3 | `validateRequestId`: перейти от «тихой мутации в `_`» к **reject-and-regenerate** + канонизация-до-валидации + audit (CWE-113/174/180) | **P1 (security)** | PR-4, PR-6 |
| R4 | Явно описать регистрацию платформенного пропагатора и **идемпотентность inject** (нет двойной инжекции с Агентом) | **P1 (arch)** | PR-1, PR-7, тесты |
| R5 | Kafka: сослаться на **новые** канонические `OpenTelemetryProducerInterceptor`/`KafkaTelemetrySupplier` (PR #14929), а не только на deprecated `Tracing*` | **P2 (точность)** | PR-5 |
| R6 | Поднять ingress-baggage fail-closed allowlist из «опционально» в **рекомендуемое**, добавить size-caps + audit; сослаться на официальный security-раздел OTel | **P1 (security)** | PR-6, PR-8 |
| R7 | Явная тест-матрица по регистру заголовков (HTTP/2 lowercases, Kafka — case-sensitive/binary) | **P2 (корректность)** | тесты |
| R8 | Наблюдаемость самих решений о propagation (counters: injected/denied/untrusted) | **P2 (SRE)** | PR-8 |

Вердикт: **план можно исполнять**, но R1–R4 желательно зафиксировать в PR-0/PR-1
до написания кода, поскольку они влияют на ADR и контракты.

## 1. Методология и источники исследования

Исследование проводилось по четырём категориям источников:

1. **Стандарты и спецификации**: W3C Trace Context, официальная документация
   OpenTelemetry (context propagation, Java SDK config, Kafka instrumentation).
2. **Коммерческие/индустриальные исходники**: `opentelemetry-java-instrumentation`
   (в т.ч. PR #14929 — новые Kafka-интерсепторы, окт. 2025), Envoy/Istio
   (модель `x-request-id`), IBM `mcp-context-forge` (baggage fail-closed, PR #4008).
3. **Security-базы**: CWE-113 (CRLF/HTTP-splitting), CWE-174/180 (double-decode,
   canonicalization), PortSwigger KB, официальный security-раздел OTel
   (commit `opentelemetry.io#8664`).
4. **Профессиональные статьи 2025–2026**: oneuptime (custom propagators, Kafka,
   baggage), http.dev (X-Request-Id как de-facto стандарт).

Дополнительно рекомендации сверены с **фактическим кодом** репозитория
(`TrustedDestinationMatcher`, `PlatformTraceControl`, `PlatformPropagationDecision`,
`PlatformTraceControlPropagator`), чтобы они были не абстрактными, а применимыми.

## 2. Что best practices подтверждают (сильные стороны плана)

Эти решения плана прямо подтверждаются исследованием — менять не нужно, но стоит
**зафиксировать как соответствие стандарту** в ADR (это усиливает защиту решения на ревью):

- **Agent-first, custom propagator через `TextMapPropagator`** — канонический путь
  (OTel docs, examples/extension). Платформенный пропагатор не создаёт span'ы.
- **secure-by-default DENY + trusted-gating на outbound** — точно совпадает с
  официальной рекомендацией OTel «configure your propagators to not send context to
  external or public-facing endpoints» и с подходом IBM «security-first, disabled by default».
- **`propagateForceTrace=false` по умолчанию** — корректно: W3C sampled-flag в
  `traceparent` уже переносит решение о записи на downstream (parent-based sampler).
  Проброс `X-Trace-On` наружу действительно избыточен в большинстве случаев.
- **Span links вместо parent-child для Kafka** — рекомендация OTel для async/messaging
  (consumer обрабатывает сообщение независимо от lifecycle producer'а; parent-child
  искажает длительность трейса). Совпадает с `KafkaBatchLinksAspect`.
- **Опора на штатный `span-suppression-strategy=semconv`** вместо собственных
  эвристик anti-double-instrumentation — верно.
- **Отказ от `tracestate` как носителя force-trace** — обоснованно (vendor-лимиты 512 B,
  формат ключей; sampled-flag уже несёт решение).
- **Интеграция в существующие модули вместо новых `propagation-http`/`-kafka`** — в
  agent-compatible режиме объём кода невелик, отдельные модули были бы overengineering.

## 3. Ключевые рекомендации

### R1 (P0, концептуальный). Реконсилировать семантику `X-Request-Id`

**Находка.** План в разделе 6 определяет `X-Request-Id` как «**per-hop** идентификатор
конкретной HTTP-попытки … **НЕ используется для сквозного поиска** по микросервисам».
Одновременно PR-4 предписывает «**Не перезатирать** валидное значение от Ingress/edge»
и пробрасывать его downstream (opt-in `propagateRequestId=true` по умолчанию).

Это **внутреннее противоречие**:
- Истинно *per-hop* id **регенерируется на каждом хопе** и downstream получает уже
  *другое* значение — тогда поиск по нему между сервисами невозможен (как заявлено).
- Но «preserve incoming + forward unchanged» — это ровно **edge-stable correlation id**,
  и он **используем** для сквозной лог-корреляции.

**Доказательство из индустрии (де-факто стандарт).** `x-request-id` повсеместно
трактуется как **сквозной**, генерируемый один раз на edge и **передаваемый без
изменений** через все хопы:
- Envoy: «Applications can **forward** the x-request-id header for **unified logging**
  as well as tracing»; «the service should **propagate** the x-request-id to enable
  logging across the invoked services to be **correlated**».
- Istio FAQ: приложения обязаны форвардить `x-request-id`, `traceparent`, `tracestate`
  для сшивки спанов и логов.
- http.dev: «The X-Request-ID header enables **end-to-end request correlation**. …
  Every service and reverse proxy in the request path **preserves and forwards** the
  identifier.»
- Nginx / AWS ALB / HAProxy / Heroku — генерируют UUID при отсутствии и форвардят.

Иными словами, поведение, которое план описывает в PR-4 (preserve + forward), —
правильное и индустриальное. **Неверна формулировка семантики**: называть это
«per-hop, не для сквозного поиска» — значит дезинформировать SRE/QA/потребителей API,
ради которых и пишется внутреннее оповещение.

**Рекомендация.** Выбрать **одну** из двух непротиворечивых моделей и явно её
зафиксировать в `ADR-request-id-hop-id` (PR-0):

- **Вариант A (рекомендуемый, Envoy-модель): `X-Request-Id` = edge-stable correlation id.**
  Генерируется один раз при отсутствии, валидируется и **форвардится без изменений**;
  пригоден для сквозной лог-корреляции **наравне** с trace-id (но это *correlation*, не
  *trace*). Тогда:
  - убрать из плана тезис «НЕ для сквозного поиска» — он противоречит поведению;
  - переименовать концепт в документации: «request/correlation id», не «per-hop»;
  - `propagateRequestId=true` по умолчанию — логично (форвард — суть модели);
  - в MDC `correlation_id` + `platform.request_id` — корректно.
- **Вариант B (истинно per-hop): регенерация на каждом исходящем хопе.**
  Тогда `propagateRequestId` по умолчанию **false** (наружу уходит новый id или ничего),
  сквозная корреляция — **только** trace-id, и тезис «не для сквозного поиска» становится
  верным. Но это **расходится** с Envoy/Ingress (которые ждут форвард) и усложняет
  отладку.

> ⚠️ Ключевой архитектурный выбор. Я рекомендую **Вариант A**: он совпадает с
> Ingress-слоем (Envoy/Nginx уже кладут `x-request-id`), не ломает ожиданий
> инфраструктуры и устраняет противоречие. При этом инвариант «request-id ≠ trace-id»
> и запрет класть trace-id в `X-Request-Id` (главное требование `Traces Requests.txt`)
> **полностью сохраняется** — это ортогонально «per-hop vs edge-stable».

**Что поправить в плане:** раздел 6 и PR-0 (текст ADR), PR-4 (формулировки + дефолт
`propagateRequestId`), PR-8 (внутреннее оповещение — описать как correlation id).

### R2 (P0, security). Усилить `TrustedDestinationMatcher` против обхода allowlist

**Находка.** Trusted-gating — основной механизм безопасности всего outbound (от него
зависит, уйдут ли внутренние заголовки наружу). Текущая реализация компилирует glob
наивно:

```33:36:e:\Platform_Traces\platform-tracing-otel-extension\src\main\java\space\br1440\platform\tracing\otel\extension\propagation\TrustedDestinationMatcher.java
    private static Pattern compileGlob(String glob) {
        String regex = glob.replace(".", "\\.").replace("*", ".*");
        return Pattern.compile("^" + regex + "$", Pattern.CASE_INSENSITIVE);
    }
```

Риски обхода/ложных решений (CWE-аналог allowlist-bypass, ср. SSRF host-matching):
- **`*` → `.*` матчит и точки**: паттерн `*.corp.local` совпадёт с `a.b.corp.local`
  (вероятно, ожидаемо), но и неаккуратный паттерн `api-*` совпадёт с `api-.evil.com`.
  Wildcard, пересекающий границу label'а, — классический источник ошибок allowlist.
- **Порт не отсекается**: `isTrusted(host)` зависит от того, как вызывающий извлёк host.
  `URI.getHost()` порт не включает (ок), но если где-то используется `getAuthority()`
  или host с портом — паттерн `host.corp.local` не совпадёт (false-deny) или совпадёт
  неожиданно. Нужно зафиксировать: на вход всегда **только** hostname (без порта/userinfo).
- **Trailing dot (FQDN)**: `corp.local.` (валидный абсолютный FQDN) не совпадёт с
  `*.corp.local` → false-deny; хуже — может использоваться для обхода в обратную сторону.
- **IDN/punycode и регистр**: регистр учтён (`CASE_INSENSITIVE`), но IDN-хосты
  (`xn--…`) и Unicode-эквиваленты не канонизируются → потенциальный обход.
- **IP-литералы**: `127.0.0.1`, IPv6 `[::1]`, decimal/octal-представления IP не
  обрабатываются — риск SSRF-обхода через альтернативную нотацию.

**Доказательство.** CWE/PortSwigger: «Use an *accept known good* input validation
strategy … Inputs should be **decoded and canonicalized** to the application's internal
representation **before** being validated». Для host-allowlist это означает нормализацию
хоста до сравнения и матчинг по label-границам, а не подстрокой.

**Рекомендация (в PR-1, как часть `OutboundPropagationPolicy`):**
1. Нормализовать host до проверки: lower-case, срезать порт/userinfo/trailing dot,
   привести IDN к ASCII (punycode) единообразно.
2. Матчить по **label-границам**: `*.example.com` должен означать «один или более
   поддоменов example.com», а не «любая строка, оканчивающаяся на …». Экранировать так,
   чтобы `*` не пересекал точку без явного намерения (либо ввести два класса: `*` —
   один label, `**` — много, как в路由-матчерах).
3. **По умолчанию запрещать IP-литералы** в trusted-list (или требовать явного
   разрешения), чтобы исключить SSRF-обход через числовые формы.
4. Покрыть юнит-тестами обходные кейсы: `trusted.com.evil.com`, `eviltrusted.com`,
   `trusted.com:8443`, `trusted.com.`, `TRUSTED.com`, IPv4/IPv6-литералы.

**Что поправить в плане:** PR-1 — добавить пункт «host canonicalization + label-aware
matching + IP-literal policy» и соответствующие негативные тесты в раздел 7.

### R3 (P1, security). `validateRequestId`: reject-and-regenerate вместо тихой мутации

**Находка.** Текущая валидация заменяет недопустимые символы на `_` и молча усекает:

```52:61:e:\Platform_Traces\platform-tracing-api\src\main\java\space\br1440\platform\tracing\api\propagation\control\PlatformTraceControl.java
    private static String validateRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return null;
        }
        String trimmed = requestId.trim();
        if (trimmed.length() > 128) {
            trimmed = trimmed.substring(0, 128);
        }
        return INVALID_REQUEST_ID_CHARS.matcher(trimmed).replaceAll("_");
    }
```

Это **нейтрализует CWE-113** (CRLF/control-chars превращаются в `_`, splitting
невозможен) — хорошо. Но как стратегия для *идентификатора* у неё есть минусы относительно
best practice «allowlist + **reject**, а не transform»:
- **Мутация теряет смысл id**: `"abc\r\nInjected: x"` станет `"abc__Injected__x"` —
  технически безопасно, но это уже **не тот** id, что прислал клиент; для support/
  correlation он бесполезен и при этом выглядит «валидным».
- **Нет audit-сигнала**: подмена молчаливая, отклонённые/искажённые значения не логируются
  (а план в PR-4/PR-6 явно требует audit-лог отклонённых).
- **CWE-174/180**: нет явной канонизации-до-валидации и защиты от double-decode (актуально,
  если значение приходит URL-кодированным от прокси).
- **Тихое усечение 128** также маскирует аномалию (слишком длинный id — признак атаки/бага).

**Доказательство.** CWE-113/PortSwigger: «allow only short alphanumeric strings … any
other input should be **rejected**»; «**reject** input containing CRLF … sanitization is
**less robust** than allowlist validation and should be a **secondary** defense».
CWE-180/174: канонизировать до валидации, не декодировать дважды.

**Рекомендация (PR-4, переиспользуя единый валидатор и в PR-6 для Kafka):**
- Ввести два уровня:
  1. **Строгая валидация формата** (для нового контракта request/correlation id —
     предпочтительно UUIDv4-подобный или `[A-Za-z0-9_-]{1,128}`). При несоответствии —
     **не мутировать**, а: на ingress HTTP — **отклонить и сгенерировать свежий UUIDv4**
     + audit-лог (one-shot/debug, без утечки самого значения); на Kafka ingress —
     отбросить значение (бизнес-обработка не падает).
  2. **Жёсткий barrier на запись** (defense-in-depth): даже сгенерированный/принятый id
     перед `setHeader`/Kafka `add` проходит проверку на отсутствие `< 0x20` и `\r`/`\n`.
- Канонизировать (trim, при необходимости один контролируемый URL-decode) **до** проверки;
  запретить повторное декодирование.
- Длину сверх лимита трактовать как **reject**, а не truncate (для контролируемого формата).

> Совместимо с указанием пользователя «не добавлять `@Deprecated`»: это не ломающее
> изменение API, а ужесточение поведения внутри `validateRequestId`/нового валидатора
> (решение ещё не в проде — golden window).

**Что поправить в плане:** PR-4 (пункт про валидацию request-id), PR-6 (Kafka inbound),
раздел 7 (тесты: reject+regenerate, no-double-decode, write-barrier).

### R4 (P1, архитектура). Регистрация пропагатора и идемпотентность inject

**Находка.** План справедливо переиспользует существующий `PlatformTraceControlPropagator.inject()`
через `PROPAGATION_DECISION`. Но не зафиксирован **жизненный цикл регистрации** самого
пропагатора и взаимодействие с Агентом на outbound. Возможны два пути инжекции:
1. Платформенный пропагатор **в глобальной цепочке** (`OTEL_PROPAGATORS`/
   `PlatformPropagatorFactory`) → Агент вызывает `inject()` **на каждом** исходящем
   автоматически. Без `PROPAGATION_DECISION` это no-op (secure-by-default) — ок.
2. Платформенный **interceptor** (PR-2/PR-3/PR-5) сам ставит decision и вызывает `inject()`.

Если активны **оба**, на доверенный хост возможны два прохода inject (глобальный +
interceptor). Для control-заголовков `setter.set(...)` идемпотентен по значению
(перезапись того же ключа), поэтому дублей заголовков не будет — но это **надо
зафиксировать как инвариант и покрыть тестом**, а не полагаться на случайность.

**Доказательство.** OTel docs (composite propagators), examples/extension: пропагатор
регистрируется через `ConfigurablePropagatorProvider` + `OTEL_PROPAGATORS`; для агента —
extension JAR. Discussion #9489 показывает типичную путаницу с тем, *где* и *как* пропагатор
попадает в цепочку. Это ровно та зона, где стоит избегать двусмысленности.

**Рекомендация (PR-1/PR-7):**
- Явно описать в ADR-outbound-propagation, **где** живёт `PlatformTraceControlPropagator`:
  рекомендуется **только interceptor-путь** для control-заголовков (decision привязан к
  trusted-решению на client-layer), а в глобальной outbound-цепочке Агента control-инжекция
  — no-op без decision. Это устраняет неоднозначность «кто инжектит».
- Зафиксировать инвариант идемпотентности: `inject()` только **добавляет/перезаписывает**
  3 контролируемых ключа и **не трогает** `traceparent`/`tracestate`/`baggage`.
- Тест: на доверенный хост заголовок присутствует **ровно один раз** (нет дублей при
  совместной работе с Агентом); на недоверенный — отсутствует; W3C/baggage не изменены
  платформенным слоем.

**Что поправить в плане:** PR-1 (где регистрируется/вызывается inject), PR-7 (инвариант +
тест на отсутствие дублей), раздел 9 (риск «порядок с Агентом» — уточнить митигацию).

### R5 (P2, точность). Kafka: ссылаться на актуальные канонические интерсепторы

**Находка.** План (раздел 3) берёт за образец `OpenTelemetryProducerInterceptor` и
корректно отвергает «Loongsuite `Tracing*Interceptor` (deprecated)». Однако в самом
`opentelemetry-java-instrumentation` произошёл сдвиг: **PR #14929 (окт. 2025)** ввёл новые
`OpenTelemetryProducerInterceptor`/`OpenTelemetryConsumerInterceptor`, принимающие
`KafkaTelemetry` через `KafkaTelemetrySupplier`, и **пометил `TracingProducerInterceptor`/
`TracingConsumerInterceptor` как deprecated**.

**Рекомендация (PR-5):** уточнить, что эталон формы — **новые** `OpenTelemetry*Interceptor`
+ механизм `KafkaTelemetrySupplier` (а не deprecated `Tracing*`). Это укрепляет тезис плана
о передаче зависимостей в не-Spring интерсептор: новый OTel-код решает ровно ту же задачу
(инстанс передаётся через supplier/config), и наш `OutboundPropagationPolicy` через
producer-config map — прямой аналог. Создание span'ов по-прежнему **не перенимаем** (это Агент).

> Замечание по совместимости: мы НЕ зависим от этих классов в runtime (Агент инжектит W3C
> сам); ссылка нужна только как «образец формы» и для актуальности раздела 3.

### R6 (P1, security). Ingress-baggage fail-closed — из «опционально» в «рекомендуемо»

**Находка.** План фильтрует baggage на **outbound** (`FilteringBaggagePropagator.inject`),
а ingress-allowlist оставляет «опциональным усилением» (PR-6). Между тем индустрия и
**официальный** security-раздел OTel однозначно трактуют **входящий** baggage как untrusted.

**Доказательство.**
- Официальный OTel (commit `opentelemetry.io#8664`, раздел «Security best practices»):
  «**Incoming context**: be cautious … you might want to **ignore or sanitize** incoming
  context from untrusted sources»; «avoid putting sensitive information … in baggage».
- IBM `mcp-context-forge` (PR #4008, коммерческий security-first): **fail-closed для ВСЕГО**
  baggage (и headers, и incoming baggage); **size-limits** (например, max items + общий
  размер в КБ — DoS-предотвращение); **audit-лог** отклонённых; **PII-детект** для
  чувствительных ключей; propagation наружу **disabled by default**.

**Рекомендация (PR-6 + PR-8):**
- Поднять ingress-baggage allowlist (fail-closed) из «опционально» в **рекомендуемое**
  для сервисов на доверенной границе (edge/публичные эндпоинты). Для чисто внутренних
  сервисов можно оставить наследуемые stock-лимиты (#8378), но **зафиксировать решение**
  в ADR явно (а не умолчанием).
- Добавить **size-cap по количеству и суммарному размеру** baggage на ingress + audit-лог
  отклонённых ключей (для регресса DoS-амплификации multi-value, ср. GHSA-mh2q-q3fh-2475).
- В PR-8 сослаться на официальный security-раздел OTel как на источник инварианта (усиливает
  ADR на ревью).

### R7 (P2, корректность). Тест-матрица по регистру/бинарности заголовков

**Находка.** План упоминает case-insensitive `traceparent` в тестах HTTP, но регистр для
**платформенных** заголовков на extract и бинарность Kafka-заголовков стоит зафиксировать
явно. HTTP/2 принудительно lower-case'ит имена; `TextMapGetter` в OTel обычно
case-insensitive, но платформенный extract читает по точным именам:

```46:48:e:\Platform_Traces\platform-tracing-otel-extension\src\main\java\space\br1440\platform\tracing\otel\extension\propagation\PlatformTraceControlPropagator.java
        String forceTraceVal = getter.get(carrier, forceTraceHeader);
        String qaTraceVal = getter.get(carrier, qaTraceHeader);
        String requestIdVal = getter.get(carrier, requestIdHeader);
```

**Доказательство.** Istio/oneuptime: «HTTP/2 **lowercases** all header names. Make sure your
propagation code handles both `X-Request-ID` and `x-request-id`». Kafka headers — **binary /
case-sensitive** (план это уже отмечает для setter'а `remove(key).add(key, bytes)`).

**Рекомендация (раздел 7 тестов):** добавить явные кейсы:
- HTTP extract платформенных заголовков при lower-case именах (HTTP/2) и mixed-case (HTTP/1.1);
- Kafka: точное имя ключа + UTF-8 кодировка значения, отсутствие коллизий при повторной инжекции;
- регресс «extract↔inject round-trip» для `PlatformTraceControlPropagatorTest` (план его уже
  планирует — добавить туда регистр/кодировку).

### R8 (P2, SRE). Наблюдаемость решений о propagation

**Находка.** PR-8 закрывает SRE-видимость через `OtelEnvHints`, но самих **решений** о
propagation (сколько исходящих было заинжектировано / отклонено по trusted-gating) в
наблюдаемости нет. Для аудита «не утекают ли заголовки» это полезный сигнал.

**Рекомендация (PR-8, опционально):** лёгкие counters (через существующий
JMX/actuator-канал Фазы 10): `outbound.injected`, `outbound.denied_untrusted`,
`outbound.denied_policy`, `ingress.requestid_rejected`, `ingress.baggage_rejected`.
Low-cardinality, без значений заголовков. Это даёт SRE прямую метрику «security-gate работает».

## 4. Мелкие замечания (nice-to-have, не блокеры)

- **`X-QA-Trace` на inject пишется как `"1"`** (хардкод) — теряется исходное значение.
  Если QA-маркер несёт смысловую нагрузку (например, id прогона), стоит эхо-ить
  валидированное исходное значение, а не константу.
- **Версионные штампы в ADR** (1.61.0/2.27.0) — doc-drift после bump до 2.28.1; косметика,
  план это уже отметил.
- **`PropagationDefaults` без геттеров outbound** — план это закрывает в PR-0; убедиться,
  что источник истины имён заголовков один (`PlatformHeaders` в `platform-tracing-api`),
  без дрейфа с `PropagationDefaults`.

## 5. Трассируемость рекомендаций к PR плана

| Рекомендация | PR плана | Тип изменения |
|---|---|---|
| R1 (X-Request-Id семантика) | PR-0 (ADR), PR-4, раздел 6, PR-8 | концепт/формулировки/дефолт |
| R2 (TrustedDestinationMatcher hardening) | PR-1 + тесты (раздел 7) | security-код + негативные тесты |
| R3 (reject-and-regenerate request-id) | PR-4, PR-6 + тесты | security-поведение |
| R4 (регистрация + идемпотентность inject) | PR-1, PR-7, раздел 9 | архитектура + тест на дубли |
| R5 (новые Kafka-интерсепторы как образец) | PR-5 (раздел 3) | точность ссылок |
| R6 (ingress baggage fail-closed) | PR-6, PR-8 | security + ADR |
| R7 (регистр/бинарность заголовков) | раздел 7 (тесты) | тест-матрица |
| R8 (counters решений) | PR-8 | наблюдаемость (опц.) |

## 6. Источники

**Стандарты / официальная документация**
- W3C Trace Context — https://www.w3.org/TR/trace-context/
- OTel Context Propagation (+ Security best practices, commit #8664) —
  https://opentelemetry.io/docs/concepts/context-propagation/
- OTel Java SDK Configuration (propagators, `ConfigurablePropagatorProvider`) —
  https://opentelemetry.io/docs/languages/java/configuration/
- OTel Kafka instrumentation blog — https://opentelemetry.io/blog/2022/instrument-kafka-clients/

**Коммерческие / индустриальные исходники**
- `opentelemetry-java-instrumentation` PR #14929 (новые `OpenTelemetry*Interceptor`,
  deprecation `Tracing*`, окт. 2025) —
  https://github.com/open-telemetry/opentelemetry-java-instrumentation/pull/14929
- examples/extension (custom `TextMapPropagator` + SPI) —
  https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/examples/extension/README.md
- Discussion #9489 (PassThroughPropagator в агенте) —
  https://github.com/open-telemetry/opentelemetry-java-instrumentation/discussions/9489
- IBM `mcp-context-forge` PR #4008 / issue #3976 (baggage fail-closed, size-caps, audit) —
  https://github.com/IBM/mcp-context-forge/pull/4008
- Envoy Tracing (x-request-id forward/propagate) —
  https://www.envoyproxy.io/docs/envoy/latest/intro/arch_overview/observability/tracing
- Istio Distributed Tracing FAQ (forward x-request-id/traceparent/tracestate) —
  https://istio.io/latest/about/faq/distributed-tracing/

**Security**
- CWE-113 (CRLF/HTTP splitting) — https://cwe.mitre.org/data/definitions/113.html
- CWE-174 (double decoding), CWE-180 (canonicalize before validate)
- PortSwigger — HTTP response header injection —
  https://portswigger.net/kb/issues/00200200_http-response-header-injection

**Профессиональные статьи (2025–2026)**
- oneuptime: custom propagators / Kafka chains / baggage propagation
- http.dev: X-Request-Id (de-facto correlation header) — https://http.dev/x-request-id

---
*Документ подготовлен как независимое ревью плана; финальные архитектурные решения
(особенно R1: модель `X-Request-Id`) — за владельцами ADR.*
