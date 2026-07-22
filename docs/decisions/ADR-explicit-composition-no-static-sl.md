# ADR: явная композиция без static ServiceLoader

| Поле | Значение |
|---|---|
| Статус | **Accepted** |
| Основание | Spike A, Slices C1-C3 и I |

## Решение

Application-side зависимости создаются composition root и передаются явно. `platform-tracing-api` не выбирает реализацию через static holder, `ServiceLoader`, context classloader или process-wide mutable registry.

Spring composition остаётся в autoconfigure. Direct/test composition создаёт implementation явно. Java Agent SPI descriptors допустимы только внутри agent extension как официальный bootstrap mechanism OTel Agent; они не являются application extension SPI.

Application и Agent являются разными composition planes. Объекты `SpanFactory`, readers, identity storage и Spring beans остаются в application classloader. Agent-side sampler/processors/exporters не инжектируются в приложение.

## Отклонённые альтернативы

- Static `ServiceLoader` holder в API: classpath trap и скрытая глобальная композиция.
- Cross-classloader DI: нарушает class identity и lifecycle ownership.
- Общий mutable singleton context: создаёт leakage между запросами.

## Verification

ArchUnit `API_NO_SERVICE_LOADER`, classloader-isolation E2E, extension SPI packaging checks и Spring context topology tests.
