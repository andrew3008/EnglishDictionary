# Реестр решений по Spring bean и SPI

Лестница решения: необходимость Spring bean -> возможность factory-local создания -> существующий контракт -> число независимых потребителей -> lifecycle/replacement boundary.

| Объект | Решение | Обоснование |
|---|---|---|
| `AttributePolicy` | USE EXISTING PORT | Стабильный policy-контракт используется semantic wiring и допускает осознанную замену. |
| `SpanEnricher` | USE EXISTING PORT | Публичная capability фасада с несколькими потребителями. |
| `ExceptionMessagePolicy` | MAKE FACTORY-LOCAL | Самостоятельной lifecycle/replacement boundary нет; политика создаётся владельцем `ExceptionRecorder`. |
| `ExceptionRecorder` | USE EXISTING PORT | Существующий контракт API внедряется в facade и error-handling. |
| `SemconvMetrics` | USE EXISTING PORT | Существующий контракт otel semconv связывает policy и Micrometer adapter. |
| `PlatformContextPropagation` | KEEP AS INTENTIONAL PORT | Межмодульная capability для async/web адаптеров. |
| `PrivateControlOpenTypeCodec` | KEEP AS INTERNAL CROSS-MODULE CONTRACT | В production создаётся владельцем JMX-клиента; JMH измеряет codec напрямую, а perf fixture использует его для канонического `payloadDigest`. |
| `PrivateControlLocator` | KEEP AS INTERNAL CROSS-MODULE CONTRACT | Нужен production perf fixture для реального process-local транспорта. |
| `TracingStatusAssembler` | MAKE FACTORY-LOCAL BEAN | Один endpoint-потребитель, отдельный публичный SPI не вводится. |

Синтетические интерфейсы с одной реализацией не добавлялись. Back-off сохраняется только для существующих контрактов, реальной замены или отдельного lifecycle.
