# ADR: CP-C2 OTel-free Outbound Propagation Port

| Поле | Значение |
|------|----------|
| Статус | **PROPOSED - AWAITING ARCHITECT APPROVAL** |
| Дата | 2026-07-19 |
| Checkpoint | `CP-C2` перед Slice C2 |
| Контекст | Удаление OTel-типов из public ABI `platform-tracing-api` без изменения outbound policy |
| Связанные ADR | [ADR-outbound-propagation.md](./ADR-outbound-propagation.md), [ADR-context-first-propagation.md](./ADR-context-first-propagation.md) |

## 1. Решение, требующее утверждения

Предлагается сохранить существующие OTel-free контракты:

- `PlatformContextPropagation` - только перенос execution context между потоками;
- `OutboundPropagationPolicy` - trusted-destination policy;
- `OutboundPropagationDecision` - решение по трём platform headers;
- `InboundTraceControl` - нормализованное входящее control-plane состояние.

OTel-зависимый `TraceControlHeaderInjector` заменяется OTel-free application port `PlatformOutboundPropagation`. Port получает уже вычисленное transport policy decision, читает current per-execution state внутри implementation и возвращает narrowly typed immutable header result.

## 2. Exact Proposed Public Signatures

Пакет: `space.br1440.platform.tracing.api.propagation.control`.

```java
public interface PlatformOutboundPropagation {

    @Nonnull
    OutboundPropagationHeaders resolve(@Nullable OutboundPropagationDecision decision);
}
```

```java
public record OutboundPropagationHeaders(
        @Nonnull Optional<Header> forceTrace,
        @Nonnull Optional<Header> qaTrace,
        @Nonnull Optional<Header> requestId) {

    public static final OutboundPropagationHeaders EMPTY =
            new OutboundPropagationHeaders(Optional.empty(), Optional.empty(), Optional.empty());

    public OutboundPropagationHeaders {
        Objects.requireNonNull(forceTrace, "forceTrace");
        Objects.requireNonNull(qaTrace, "qaTrace");
        Objects.requireNonNull(requestId, "requestId");
    }

    public record Header(@Nonnull String name, @Nonnull String value) {
        private static final Pattern HEADER_NAME =
                Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");

        public Header {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
            if (!HEADER_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("invalid header name");
            }
            if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("header value must not contain CR/LF");
            }
        }
    }
}
```

Имена являются частью результата, поскольку они конфигурируемы. Семантические slots не позволяют adapter-ам перепутать force-trace, QA и request-id и сохраняют намеренно ограниченную поверхность из трёх control headers.

## 3. Semantics

| Условие | Результат |
|---------|-----------|
| `decision == null` | `OutboundPropagationHeaders.EMPTY` |
| `decision == DENY_ALL` | `EMPTY`, current context не требуется |
| current control отсутствует | `EMPTY` |
| конкретный `propagate* == false` | соответствующий slot пуст |
| force разрешён и current `forceTrace == true` | header со значением `on` |
| QA разрешён и current `qaTrace == true` | header со значением `1` |
| request-id разрешён и нормализованный id присутствует | request-id header |
| disabled/no-op runtime | `EMPTY` |
| ошибка чтения implementation context | fail-closed `EMPTY`; transport adapters дополнительно сохраняют exception isolation |

Результат immutable. Header name соответствует token-форме, CR/LF в value запрещены. Port thread-safe и stateless; current state читается при каждом `resolve`, поэтому WebFlux вызывает его внутри subscription-time `Mono.defer`.

## 4. Ownership

| Область | Owner |
|---------|-------|
| Destination extraction (`URI.host`, Kafka topic) | transport adapter |
| Trusted destination и per-header policy | существующий `OutboundPropagationPolicy` |
| Чтение current OTel `Context`/`ContextKey` | implementation в `platform-tracing-otel` |
| Формирование трёх typed header slots | implementation port |
| Запись результата в HTTP/Kafka carrier | transport adapter |
| W3C `traceparent`/`tracestate`/baggage | OTel Agent, вне port |

WebMVC, WebFlux и Kafka main-код зависят только от `PlatformOutboundPropagation`, `OutboundPropagationPolicy`, `OutboundPropagationDecision` и `OutboundPropagationHeaders`. Они не импортируют `Context`, `ContextKey`, `TextMapSetter` или implementation classes.

Agent-side internal propagator может использовать внутренний OTel adapter, но не передаёт objects через application/agent classloader boundary. Spring+Agent создаёт application-side port implementation в application classloader.

## 5. Intentional ABI Delta Slice C2

Удалить из `platform-tracing-api`:

- `TraceControlHeaderInjector`;
- `PlatformTraceContextKeys`;
- `OtelTraceparentReader`.

Добавить в `platform-tracing-api`:

- `PlatformOutboundPropagation`;
- `OutboundPropagationHeaders`;
- nested `OutboundPropagationHeaders.Header`.

Сохранить без изменения:

- `PlatformContextPropagation`;
- `OutboundPropagationPolicy`;
- `OutboundPropagationDecision`;
- `InboundTraceControl`;
- `TrustedDestinationMatcher`.

Любой иной public type или signature считается незапланированным ABI drift.

## 6. Почему не другие формы

- `PlatformContextPropagation` не расширяется: async capture/restore и outbound header projection имеют разные причины изменения.
- `Object carrier`, raw/generic `Map` и универсальный setter callback запрещены: они стирают transport boundary или копируют `TextMapSetter`.
- Port не принимает destination: policy уже владеет destination normalization/trust, а HTTP и Kafka используют разные policy instances.
- Port не возвращает `InboundTraceControl`: inbound source model содержит sampling metadata и raw input, которые нельзя повторно экспонировать outbound adapter-ам.
- Port не записывает carrier: Servlet, WebFlux и Kafka carrier types должны оставаться в своих adapter modules.

## 7. Multi-Transport Evidence

Текущие consumers:

- Servlet: `PlatformOutboundHttpInterceptor` извлекает `request.getURI().getHost()`;
- WebFlux: `PlatformOutboundExchangeFilterFunction` извлекает `request.url().getHost()` на subscription;
- Kafka: `PlatformKafkaProducerInterceptor` использует `record.topic()` в `onSend()`;
- все три сначала вызывают `OutboundPropagationPolicy.decide(destination)`;
- все три сейчас создают OTel `Context` с `PROPAGATION_DECISION` и вызывают OTel-зависимый injector.

Предлагаемая сигнатура заменяет только последнюю пару операций на `port.resolve(decision)` и запись трёх typed slots. Trusted policy, destination semantics, header values и exception isolation не меняются.

## 8. Verification после Approval

```powershell
.\gradlew.bat :platform-tracing-api:test --no-daemon
.\gradlew.bat :platform-tracing-core:test --no-daemon
.\gradlew.bat :platform-tracing-autoconfigure-webmvc:test --no-daemon
.\gradlew.bat :platform-tracing-autoconfigure-webflux:test --no-daemon
.\gradlew.bat :platform-tracing-spring-boot-autoconfigure:test --no-daemon
.\gradlew.bat pr4ArchitectureFitnessVerify pr1ModuleTaxonomyVerify --no-daemon
.\gradlew.bat build --no-daemon
```

Обязательные negative gates:

- API main imports `io.opentelemetry.*` = 0;
- webmvc/webflux main imports `io.opentelemetry.*` = 0 для outbound adapters;
- API не содержит `Object` carrier, generic untyped `Map` или setter callback;
- public `SpanFactory` не ссылается на `OtelTraceparentReader`;
- external-consumer compile fixture подтверждает published POM/Gradle metadata;
- Slice 0 packaged-agent/classloader baseline остаётся зелёным.

## 9. Approval Record

До явного решения архитекторов:

- статус CP-C2 остаётся **OPEN**;
- Slice C2 production implementation остаётся **NO-GO**;
- кодовый агент не меняет предложенные signatures и не начинает move типов.

Для утверждения требуется зафиксировать одно из решений:

- `APPROVED AS PROPOSED`;
- `APPROVED WITH EXACT SIGNATURE CHANGES` с полным replacement-блоком;
- `REJECTED` с выбранной альтернативой.
