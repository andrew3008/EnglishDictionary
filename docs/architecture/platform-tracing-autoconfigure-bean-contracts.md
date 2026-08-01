# Намеренные bean-контракты autoconfigure

Документ фиксирует только имена и типы, на которые разрешено опираться соседним модулям или операционным интеграциям. Остальные имена фабричных методов являются деталями реализации и могут меняться.

| Контракт | Причина стабильности | Владение |
|---|---|---|
| `platformTraceResponseHeaderServletFilterRegistration` | Пользовательский back-off по имени | webmvc |
| `platformMvcHttpObservationSuppressor` | Пользовательский back-off по имени | webmvc |
| `platformWebFluxHttpObservationSuppressor` | Пользовательский back-off по имени | webflux |
| `platformHttpOutboundPolicy` | Общий HTTP outbound policy для обоих web-стеков | propagation |
| `platformRequestTraceContextSnapshotSupplier` | Интеграция error-handling использует именованный supplier | integrations.errorhandling |
| `tracingHealthIndicator` | Стандартное имя Spring Boot health contributor | diagnostics.health |
| `configServerPolicyRefreshExecutor` | Отдельный lifecycle и `shutdown` | control.configserver |
| `TracingRuntime`, `TraceOperations` | Публичные capability-контракты, а не имена методов | root composition |
| `AcceptedTracingPolicyReader`, `CompleteTracingPolicyReader`, `TracingPolicySnapshotApplier` | Разделённые read/apply capabilities | control.policy |
| `TracingBootstrapSettings` | Единственный immutable startup snapshot | bootstrap |

Alias-бины удалены. Новые зависимости должны внедряться по наиболее узкому существующему типу; имя bean не является контрактом, если оно отсутствует в таблице.

