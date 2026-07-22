# Фаза 12. Глубокое исследование (best practices, стандарты, коммерческие/OSS-исходники) — дополнение к плану

| Поле | Значение |
|------|----------|
| Тип документа | Углублённое independent-исследование плана `phase_12_custom_propagation_b841cbc2.plan.md` |
| Дата | 2026-06-09 |
| Отношение к существующим документам | **Дополняет**, не дублирует: [research-phase12-custom-propagation-review.md](../research-phase12-custom-propagation-review.md) и [phase-12-propagation-research-review.md](./phase-12-propagation-research-review.md) |
| Контекст версий | OTel BOM `1.62.0`, OTel instrumentation/agent `2.28.x`, Spring Boot `3.5.5`, Java 21 |
| Статус | Отревьюено и применён безопасный минимум (2026-06-09) — см. раздел 10 «Диспозиция» |

> Назначение: провести **новый** срез исследования по углам, которые прошлые два ревью почти не
> затрагивали — (1) W3C Trace Context **Level 2** (random-trace-id flag, правила версий), (2) практика
> **service mesh** (Istio/Envoy) по x-request-id и forward-листу заголовков, (3) жизненный цикл и контракт
> **Kafka `ProducerInterceptor`** (ordering, immutability, ретраи, namespace). Для каждого блока — вывод,
> применимость к нашему agent-compatible дизайну и привязка к PR.

---

## 1. Executive summary

План Фазы 12 уже зрелый и завершённый (PR-0..PR-8 реализованы, открыты только тесты). Прошлые два ревью
закрыли блокер classloader-границы `TrustedDestinationMatcher`, поправили baggage-advisory и CRLF-миф.
Этот документ добавляет **новые, ранее не зафиксированные** уточнения из первоисточников 2025-2026:

1. **W3C Trace Context Level 2 — `random-trace-id` flag (бит `0x02`).** Новый бит trace-flags, который надо
   **прозрачно переносить** (как и sampled). Это усиливает наш инвариант «не трогаем `traceparent`»:
   платформенный interceptor не должен ни читать, ни перезаписывать trace-flags — это зона Агента.
2. **Правила обработки версий `traceparent`/`tracestate`.** Higher-version parsing, downgrade, «restart trace
   если заголовок короче 55 символов». Это аргумент против любого собственного парсинга W3C на нашей стороне
   (риск рассинхрона с эволюцией стандарта) — фиксируем как explicit anti-recommendation.
3. **Istio/Envoy: x-request-id «живёт» только один хоп; Istio 1.25+ по умолчанию НЕ доверяет входящему
   `x-request-id`.** Полностью подтверждает наш Вариант A (edge-stable correlation id, validate+forward),
   но добавляет операционные нюансы: HTTP/2 lower-casing заголовков и `preserve_external_request_id` на ingress.
4. **Kafka `ProducerInterceptor` (KIP-82/KIP-512): headers становятся read-only после отправки, ретраи
   переиспользуют тот же record, namespace конфигов общий, исключения проглатываются.** Уточняет PR-5:
   идемпотентность inject (`remove(key).add`), порядок «последним», и почему нельзя строить диагностику на
   `onAcknowledgement`.
5. **Опыт платформенных команд (Skyscanner / Honeycomb / Dash0).** Главное практическое наблюдение —
   **Honeycomb «Phantom Spans»**: внешние партнёры слали `traceparent`/`tracestate`/`baggage`, и OTel-middleware
   усыновляла их как **parent** обслуживаемого запроса → поддельные/чужие родительские span'ы. Это вскрывает
   **новый edge-профильный пробел** в PR-6 (сейчас он закрывает только ingress baggage, но не входящий W3C от
   недоверенных). Skyscanner подтверждает agent-first/OTel-native стратегию и предупреждает: «custom-заголовки
   для domain-контекста сложнее, чем кажется» → наш минимальный набор из 3 control-заголовков обоснован.

Ни одно из наблюдений не меняет архитектуру плана — все они **усиливают уже принятые решения** и добавляют
точечные тест-кейсы/оговорки в ADR и PR-5/PR-6/PR-7/PR-8. Единственное содержательное дополнение — edge-стрипинг
недоверенного W3C-контекста (D9), см. раздел 6.

---

## 2. Методология и источники

Первоисточники + коммерческая/OSS-практика, без повторного прогона уже закрытых в прошлых ревью тем
(classloader, baggage GHSA, CRLF-миф, propagateForceTrace — см. предыдущие документы).

- **W3C Trace Context Level 2:** [W3C TR/trace-context-2](https://www.w3.org/TR/trace-context-2/),
  [CR-trace-context-2 (2023-04-18)](https://www.w3.org/TR/2023/CR-trace-context-2-20230418/),
  [spec/20-http_request_header_format.md](https://github.com/w3c/trace-context/blob/main/spec/20-http_request_header_format.md),
  [spec/60-trace-id-format.md](https://github.com/w3c/trace-context/blob/main/spec/60-trace-id-format.md).
- **Service mesh (Istio/Envoy):** [Istio Distributed Tracing Overview](https://istio.io/latest/docs/tasks/observability/distributed-tracing/overview/),
  [Istio Distributed Tracing FAQ](https://istio.io/latest/about/faq/distributed-tracing/),
  [Header Propagation Policies in Istio (oneuptime, 2026-02)](https://oneuptime.com/blog/post/2026-02-24-how-to-configure-header-propagation-policies-in-istio/view).
- **Kafka interceptors:** [ProducerInterceptor javadoc (Kafka 3.7)](https://kafka.apache.org/37/javadoc/org/apache/kafka/clients/producer/ProducerInterceptor.html),
  [Confluent ProducerInterceptor (clients 8.2.1)](https://docs.confluent.io/platform/current/clients/javadocs/javadoc/org/apache/kafka/clients/producer/ProducerInterceptor.html),
  [KIP-82 (Record Headers)](https://cwiki.apache.org/confluence/display/KAFKA/KIP-82+-+Add+Record+Headers),
  [How to Create Kafka Interceptors (oneuptime, 2026-01)](https://oneuptime.com/blog/post/2026-01-30-kafka-interceptors/view).

---

## 3. W3C Trace Context Level 2 — что важно для Фазы 12

### 3.1. Новый `random-trace-id` flag (trace-flags, бит `0x02`)
Level 2 добавляет **второй младший бит** поля trace-flags — `random-trace-id`. Если он установлен, по крайней
мере правые 7 байт trace-id сгенерированы случайно (равномерно на `[0..2^56-1]`), что позволяет vendor'ам
делать sampling-решения прямо по trace-id без дополнительного контекста. **Требование стандарта:** входящее
состояние флага участник ОБЯЗАН переносить во все исходящие `traceparent` без изменений (как и sampled-flag).

**Вывод для нас:** это новое требование «прозрачного переноса» относится к **Агенту** (он владеет
`traceparent`). Для плана это дополнительный аргумент к уже принятому инварианту: платформенный
interceptor **не парсит, не читает и не перезаписывает trace-flags** (ни sampled, ни random). Любая наша
логика «force-trace» работает **только** через отдельный платформенный заголовок `X-Trace-On`, а не через
манипуляцию trace-flags. Это нужно явно записать в `ADR-outbound-propagation` (рядом с уже отклонённым
вариантом «tracestate как канал force-trace»).

### 3.2. Правила версий и парсинга `traceparent`
Стандарт детально описывает обработку будущих версий:
- При higher-version: парсить как `00` (trace-id с 1-го дефиса 32 hex, parent-id 16 hex, 2 символа flags),
  **остальные/неизвестные trace-flags ставить в 0 на исходящих**.
- Если заголовок короче **55 символов** — не парсить, **рестартовать trace**.
- `tracestate` версионируется префиксом `traceparent`; при higher-version — пытаться распарсить «по мере сил»,
  частично-распарсенные пары использовать на усмотрение vendor'а.

**Вывод для нас:** эти правила — сильный довод **никогда не реализовывать собственный парсер/мутатор W3C** на
платформенной стороне (риск разойтись с эволюцией Level 2/Level 3). План это уже соблюдает, но стоит закрепить
**ArchUnit-инвариантом** (PR-7): outbound/inbound-пакеты Фазы 12 не ссылаются на `W3CTraceContextPropagator`,
не пишут `traceparent`/`tracestate`, не разбирают trace-flags. Сейчас ArchUnit-guard в плане формулирует
«не создавать span / не звать W3C-propagator» — добавить явный пункт про **trace-flags/version**.

### 3.3. Сохранение `tracestate` кастомным сэмплером
Level 2 подтверждает: при неизменном `traceparent` нельзя терять `tracestate` и нельзя удалять чужие ключи.
В плане уже есть регресс-assert «`CompositeSampler` сохраняет parent tracestate» (раздел 7) — **оставить как
обязательный**; данное исследование лишь подтверждает его необходимость свежей редакцией стандарта.

---

## 4. Service mesh (Istio/Envoy) — практика x-request-id и forward-листа

### 4.1. Подтверждение модели «edge-stable correlation id» (Вариант A)
Istio/Envoy-практика **дословно** совпадает с нашим решением раздела 6 плана:
- `x-request-id` — Envoy-specific заголовок для **согласованной выборки логов и трейсов** (лог-корреляция),
  это **не** trace identity. Полностью совпадает с нашим инвариантом `request_id ≠ trace_id`.
- Envoy генерирует `x-request-id`, если его нет, и форвардит дальше; но **межсервисный перенос — только
  «первый хоп»**: приложение ОБЯЗАНО само пробрасывать заголовки на исходящих вызовах. Это ровно тот пробел,
  который Фаза 12 закрывает client-интерсепторами (PR-2/PR-3/PR-5).

### 4.2. НОВОЕ операционное: Istio 1.25+ не доверяет внешнему `x-request-id` по умолчанию
В свежих версиях Istio входящий внешний `x-request-id` по умолчанию **не сохраняется** (security hardening);
для сохранения нужен `preserve_external_request_id` на ingress + корректно описанные internal CIDR (иначе риск
трактовать внешний трафик как внутренний).

**Вывод для нас:** это не меняет наш код, но **должно попасть в SUPPORTED.md/эксплуатацию (PR-8)**: поведение
edge-stable correlation id зависит от того, **сохраняет ли ingress входящий `x-request-id`**. Рекомендация
SRE: если фронт/ingress на Istio ≥1.25 — осознанно настроить `preserve_external_request_id`, иначе значение,
сгенерированное клиентом, будет перезаписано на edge (и наш «forward unchanged» начнётся уже от mesh-значения).

### 4.3. Case-sensitivity и HTTP/2
oneuptime/Istio прямо предупреждают: **HTTP/2 принудительно lower-case'ит имена заголовков**, код проброса
обязан корректно работать и с `X-Request-ID`, и с `x-request-id`.

**Вывод для нас:** на чтении (inbound-валидация, PR-6/PR-4) использовать case-insensitive lookup; на записи —
канонические имена из `PlatformHeaders` (один источник истины, уже зафиксировано). Добавить тест-кейс
`inbound_xrequestid_case_insensitive` (например `X-Request-Id` vs `x-request-id` vs `X-REQUEST-ID`).

### 4.4. Полный forward-лист (справочно, не для копирования)
Istio форвардит широкий набор (`x-request-id`, `traceparent`, `tracestate`, `x-b3-*`, `b3`,
`x-cloud-trace-context`, `grpc-trace-bin`, `sw8`, ...). **Мы это НЕ копируем** — W3C/baggage форвардит Агент;
мы добавляем только 3 платформенных управляющих заголовка. Список приведён как ориентир, что наш узкий набор
(`X-Trace-On`/`X-QA-Trace`/`X-Request-Id`) сознательно минимален — это плюс к безопасности (меньше header-surface).

---

## 5. Kafka `ProducerInterceptor` — контракт жизненного цикла (углублённо)

Прошлые ревью отметили «исключения проглатываются» и «producer-thread → неблокирующий». Здесь — детали
KIP-82/KIP-512 и javadoc, важные для корректного и идемпотентного inject в PR-5.

### 5.1. Headers становятся read-only после отправки (KIP-82)
После прохождения send + post-интерсепторов `Headers` записи переводятся в **immutable** (внутренний `close()`),
дальнейший `add` бросает `IllegalStateException`. Наш сеттер `remove(key).add(key, bytes)` в `onSend()` вызывается
**до** перевода в read-only — это корректная точка. Но: **defensive** — обернуть запись в изоляцию (на случай
нестандартных клиентов/повторного использования record) и не пытаться писать в `onAcknowledgement`.

### 5.2. Ретраи переиспользуют тот же record — идемпотентность inject
KIP-82 прямо предупреждает: из-за мутабельности и закрытия headers **нельзя пересылать тот же record вручную**;
полагаться следует на **автоматические ретраи продюсера**. При ретраях `onSend()` повторно не вызывается для
уже отправленного record, но если бизнес-код пересоздаёт record — важно не задвоить заголовки.

**Вывод для нас:** наш `remove(key).add(key, ...)` **идемпотентен по построению** (перезапись, не добавление) —
это правильный выбор (совпадает с `KafkaHeadersSetter` из OTel). Зафиксировать тест-кейс
`setter_is_idempotent_on_reinvocation`: повторный inject в те же `Headers` даёт ровно один заголовок, не два.

### 5.3. Порядок интерсепторов и pipeline mutable-интерсепторов
`onSend()` вызывается в порядке `INTERCEPTOR_CLASSES_CONFIG`; первый получает исходный record, следующий —
результат предыдущего. Javadoc **прямо не рекомендует** строить pipeline mutable-интерсепторов, зависящих от
вывода друг друга (side-effects при сбое одного из них: «следующий получит record от последнего успешного либо
от клиента»).

**Вывод для нас:** наш платформенный inject заголовков **не зависит** от вывода других интерсепторов (только
добавляет свои 3 заголовка по topic-policy) — это безопасный паттерн. Решение плана «добавлять платформенный
интерсептор **последним**» остаётся верным (инжект после бизнес-трансформаций). Уточнение в PR-5: документировать,
что при сбое **другого** интерсептора Kafka продолжит с «последним успешным» record — платформенный inject не
должен предполагать наличие результатов предыдущих (он и не предполагает).

### 5.4. Общий namespace конфигов + проглатывание исключений
- Интерсептор делит producer-config namespace с сериализаторами/другими интерсепторами → **уникальный ключ**
  `platform.tracing.kafka.outbound-policy` (уже выбран); Kafka **залогирует WARN «unknown config»** — ожидаемо,
  задокументировать (уже в плане 3b/PR-5).
- Исключения из методов интерсептора **ловятся, логируются, не пробрасываются** — продюсер не упадёт даже при
  неверных типах. Наша внутренняя изоляция — defense-in-depth, не расчёт на проброс (уже в плане).

### 5.5. Log compaction — не трогать key/partition
Javadoc: модификация в `onSend()` должна быть консистентной по key/value, иначе ломается log compaction. Наш
интерсептор меняет **только headers**, не key/partition — безопасно. Зафиксировать как явный инвариант теста
`interceptor_does_not_modify_key_value_partition`.

---

## 6. Опыт платформенных команд (Skyscanner / Honeycomb / Dash0)

Профессиональные статьи и доклады 2025-2026 от платформенных команд — практический срез поверх стандартов.

### 6.1. Honeycomb — «Phantom Spans» (главный новый вывод, security/correctness)
Источник: [OpenTelemetry Gotchas: Phantom Spans (Honeycomb)](https://www.honeycomb.io/blog/opentelemetry-gotchas-phantom-spans).

Кейс: бизнес-партнёры, интегрирующиеся с API, слали запросы с собственными `traceparent`/`tracestate`/`baggage`;
OTel-middleware принимала этот контекст как **parent** обслуживаемого запроса → в трейсах появлялись «фантомные»
родительские span'ы из чужой инфраструктуры (воспроизводилось только при внешних вызовах). **Фикс Honeycomb:**
на trust boundary (Cloudflare) **удалять** `traceparent`/`tracestate`/`baggage`, пришедшие извне собственной
инфраструктуры — тогда W3C-контекст используется только внутри, а внешние запросы начинают новый trace.

Согласуется с OTel docs («be cautious when accepting context from external sources ... ignore or sanitize
incoming context from untrusted sources») и W3C («restart trace при невалидном/коротком `traceparent`»).

**Что это значит для нас (НОВЫЙ edge-профильный пробел в PR-6):** наш agent-compatible дизайн **намеренно**
делегирует extract W3C Агенту и не строит второй pipeline — это правильно для **внутренних доверенных** сервисов.
Но для **edge/публичных** сервисов (принимающих трафик от недоверенных клиентов/партнёров) это означает: входящий
W3C trace context от недоверенного источника надо **стрипать/рестартовать на границе**, иначе Агент усыновит
поддельного parent. План PR-6 P1 закрывает ingress **baggage** fail-closed, но **не** входящий `traceparent`/
`tracestate`. Рекомендация D9: добавить в edge-профиль политику дропа W3C-trace-context от недоверенных источников.

> Важно: реализация — это **edge-политика** (ingress gateway / API gateway / выделенный edge-фильтр), а не отказ
> от agent-first внутри. Для внутренних сервисов поведение не меняется (доверяем mesh/Агенту).

### 6.2. Skyscanner — agent-first OTel-native + baggage для domain-контекста
Источники: [Skyscanner's journey to effective observability](https://medium.com/@SkyscannerEng/skyscanners-journey-to-effective-observability-655167a49d2f),
[InfoQ: Skyscanner cuts telemetry costs 90%](https://www.infoq.com/news/2025/05/skyscanner-observability/),
[OTel reference impl: Skyscanner](https://opentelemetry.io/docs/guidance/reference-implementations/skyscanner/),
[CNCF 2026: «Unpacking Your OTel Baggage» (Skyscanner × New Relic)](https://colocatedeventseu2026.sched.com/event/2DY2T/unpacking-your-otel-baggage-dan-gomez-blanco-new-relic-john-clark-skyscanner).

- **OTel-native, не APM-агенты** («The future is OTel-native, not APM agents»); 300+ микросервисов мигрированы
  бампом версии библиотеки; платформенные default-инструментации заданы в base image через env-vars, команды их
  наследуют и могут переопределять. → Прямо валидирует нашу **agent-first + тонкий стартер + OtelEnvHints** модель
  (PR-8): платформенные дефолты на уровне образа/Агента, команды наследуют.
- **Baggage как стандартизированный канал domain-контекста.** Skyscanner пробрасывает QoS/user-priority **как
  OTel Baggage**, и Istio на его основе делает intelligent load-shedding. Вывод доклада: «те, кто строил передачу
  domain-информации через **custom-заголовки**, поняли, что это **сложнее, чем кажется**».
  → Для нас это **подтверждение минимализма**: наши 3 заголовка (`X-Trace-On`/`X-QA-Trace`/`X-Request-Id`) — это
  **control-plane сигналы** (force/qa/correlation), а НЕ domain-метаданные. Любые user/tenant/feature-flag данные
  должны идти через **OTel Baggage** (зона Агента + `FilteringBaggagePropagator`), а не плодить новые custom-заголовки.
  Зафиксировать это разграничение в `ADR-outbound-propagation` (D10).
- **Tail/advanced sampling**: хранят ~4% спанов без потери важных (ошибки/медленные). → Контекст к нашему
  `propagateForceTrace`: форс-семантика нужна именно для «трейсов, которые важны»; согласуется с дефолтом.

### 6.3. Dash0 — W3C Level 2 и tracestate-порядок
Источники: [Dash0: W3C Trace Context explained](https://www.dash0.com/knowledge/w3c-trace-context-traceparent-tracestate),
[Dash0: How OpenTelemetry tracing works](https://www.dash0.com/knowledge/opentelemetry-tracing).

- Подтверждает раздел 3: random-trace-id flag Level 2 «усиливает гарантии случайности и уточняет best practices,
  **не переопределяя** механику propagation» — то есть нам менять механику не нужно, инвариант «W3C — зона Агента» в силе.
- **tracestate ordering:** при добавлении/обновлении своей записи vendor ставит её **в начало** списка и
  **сохраняет чужие** записи (most-recently-updated-first). → У нас платформенный слой свою tracestate-запись
  **не пишет** (это Агент), поэтому для нас ключевой инвариант — «не терять/не переупорядочивать чужие». Усилить
  формулировку регресса (D11): `CompositeSampler` не только сохраняет parent tracestate, но и не переупорядочивает чужие ключи.
- **Collector централизует routing/sampling/policy enforcement** → согласуется с нашим решением Фаз 10/11
  делегировать backpressure Коллектору, а не строить его в propagation-пути.

### 6.4. W3C Baggage spec (подтверждение fail-closed)
[W3C Baggage](https://www.w3.org/TR/baggage/): «baggage **не доверенный** при пересечении trust boundary»; платформа
ОБЯЗАНА пробрасывать либо все list-members, либо ни одного (никаких частичных). → Подтверждает наш ingress
fail-closed allowlist (PR-6 P1) и запрет частичного проброса.

## 7. Сводка рекомендаций и привязка к PR (дельта к плану)

| ID | Рекомендация | PR | Приоритет |
|----|--------------|----|-----------|
| D1 | В `ADR-outbound-propagation` зафиксировать: platform-слой **не читает/не пишет trace-flags** (sampled и **random-trace-id** Level 2 — зона Агента); force-trace только через `X-Trace-On` | PR-0 | P1 |
| D2 | Расширить ArchUnit-guard: outbound/inbound-пакеты не разбирают `traceparent` version/trace-flags и не ссылаются на `W3CTraceContextPropagator` | PR-7 | P1 |
| D3 | SUPPORTED.md: операционная заметка про Istio ≥1.25 `preserve_external_request_id` + internal CIDR (иначе edge перезапишет входящий `x-request-id`) | PR-8 | P2 |
| D4 | Inbound-валидация `X-Request-Id` — **case-insensitive** lookup; тест `inbound_xrequestid_case_insensitive` (HTTP/2 lower-casing) | PR-4/PR-6 | P2 |
| D5 | Kafka setter — явный тест идемпотентности `setter_is_idempotent_on_reinvocation` (`remove+add` → один заголовок) | PR-5 | P1 |
| D6 | Kafka — тест-инвариант `interceptor_does_not_modify_key_value_partition` (log compaction safety) | PR-5 | P2 |
| D7 | PR-5 doc: не строить диагностику на `onAcknowledgement` (headers read-only/недоступны, KIP-512); ретраи — через настройки продюсера, не ручной resend | PR-5/PR-8 | P2 |
| D8 | Подтвердить регресс «`CompositeSampler` сохраняет parent tracestate» свежей редакцией W3C Level 2 (оставить обязательным) | tests | P1 |
| D9 | **Honeycomb phantom-spans:** в edge-профиль (PR-6 P1) добавить политику **стрипа/рестарта** входящего `traceparent`/`tracestate`/`baggage` от **недоверенных** источников на границе (ingress/gateway), чтобы Агент не усыновил поддельного parent. Для внутренних доверенных сервисов поведение не меняется | PR-6 (edge) | P1 |
| D10 | **Skyscanner-урок:** в `ADR-outbound-propagation` явно разграничить — наши 3 заголовка суть **control-plane сигналы**; domain-метаданные (user/tenant/QoS/feature-flags) идут через **OTel Baggage**, а не через новые custom-заголовки | PR-0 | P2 |
| D11 | **Dash0/W3C tracestate ordering:** усилить регресс — `CompositeSampler` не переупорядочивает и не удаляет чужие `tracestate`-ключи (own-entry пишет Агент, не платформа) | tests/PR-1 | P2 |

Все рекомендации — **усиление** существующих решений. Архитектурных изменений плана не требуется; единственное
содержательное расширение — **D9** (edge-стрипинг недоверенного W3C-контекста), и оно касается **edge-профиля**,
а не внутренних сервисов в agent-compatible режиме.

---

## 8. Что НЕ менять (anti-recommendations, подтверждено новым исследованием)

- **Не реализовывать собственный парсер/мутатор `traceparent`/`tracestate`.** Правила версий/флагов Level 2
  эволюционируют (random-trace-id flag — пример); это зона Агента/`W3CTraceContextPropagator`.
- **Не использовать trace-flags как канал управления** (force/qa) — только отдельные платформенные заголовки.
- **Не копировать Istio/Envoy forward-лист** (`x-b3-*`, `grpc-trace-bin`, ...) — W3C/baggage форвардит Агент;
  наш минимальный набор из 3 заголовков — преимущество по безопасности.
- **Не строить pipeline зависимых Kafka-интерсепторов** и не полагаться на порядок результатов других
  интерсепторов — платформенный inject независим и идемпотентен.

---

## 9. Итог

Свежий срез по W3C Trace Context **Level 2**, практике **Istio/Envoy**, контракту **Kafka ProducerInterceptor** и
опыту платформенных команд **Skyscanner / Honeycomb / Dash0** не выявил блокеров и **подтвердил** ключевые решения
плана (agent-compatible, OTel-native/agent-first, `request_id ≠ trace_id`, edge-stable correlation id, минимальный
header-surface, idempotent inject, делегирование backpressure Коллектору). Добавлены 11 точечных усилений (D1-D11):
ADR-формулировки, ArchUnit-инварианты, операционные заметки SRE и тест-кейсы.

Единственное содержательное **новое** наблюдение — кейс **Honeycomb «Phantom Spans»** (D9): для **edge/публичных**
сервисов нужно стрипать/рестартовать входящий W3C trace context от недоверенных источников на границе, иначе Агент
усыновит поддельного parent. Это расширяет edge-профиль PR-6 (там сейчас только ingress baggage) и не затрагивает
внутренние доверенные сервисы. Skyscanner-урок (D10) подтверждает наш минимализм control-заголовков (domain-контекст —
через Baggage), Dash0 (D11) уточняет инвариант сохранения чужого `tracestate`.

Документ дополняет два предыдущих ревью и не пересматривает их выводы (classloader-перенос, baggage-advisory, CRLF) —
они остаются в силе.

---

## 10. Диспозиция после критического ревью (что применено / отклонено)

Исследование принято **не слепо**: рекомендации сверены с фактическим кодом. Применён безопасный минимум —
только документация/эксплуатация, без нового кода и без избыточных тестов (pre-production, принцип «не over-engineering»).

| ID | Вердикт | Обоснование |
|----|---------|-------------|
| D1 | ✅ Применено (ADR) | trace-flags вкл. `random-trace-id` (L2) — зона Агента; зафиксировано в `ADR-outbound-propagation` («Что НЕ делаем») |
| D3 | ✅ Применено (SUPPORTED) | Istio ≥1.25 `preserve_external_request_id` — операционная заметка |
| D7 | ✅ Применено (ADR) | Kafka: не строить диагностику на `onAcknowledgement`; ретраи через продюсера |
| D9 | ⚠️ Применено как **операционная заметка**, НЕ код | Стрип W3C от недоверенных — ответственность ingress/gateway, а не библиотеки. Реализация в платформе противоречила бы agent-compatible дизайну (второй W3C-pipeline). Зафиксировано в SUPPORTED + ADR (Edge / trust boundary) |
| D10 | ✅ Применено (ADR) | control-plane сигналы vs domain-метаданные (через Baggage) |
| D4 | ❌ Отклонено | case-insensitive HTTP lookup уже обеспечен servlet-спецификацией и Spring `HttpHeaders` (`LinkedCaseInsensitiveMap`); свой код = дубль. Kafka — фиксированные ключи, проблемы нет |
| D5 | ➖ Не требуется | `PlatformKafkaHeaderSetter` идемпотентен по построению (`remove(key).add`); тест-страховка опциональна |
| D2, D8, D11 | ➖ Низкая ценность | Существующий ArchUnit-guard (нет `W3CTraceContextPropagator`, нет записи `traceparent`/`tracestate`) уже покрывает; `tracestate` платформа не пишет вовсе (зона Агента) |

**Главный вывод критического ревью:** документ на ~90% **подтверждающий**; единственное содержательное новое (D9) корректно
по сути, но его подача как «пробел в PR-6 / код в платформе» противоречит остальному тексту (раздел 8) и agent-compatible
архитектуре. Поэтому D9 принят строго как **операционная политика gateway**, а не как код.
