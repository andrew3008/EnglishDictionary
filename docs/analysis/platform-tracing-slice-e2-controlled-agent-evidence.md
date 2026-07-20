# Slice E2: Controlled Agent Distribution Evidence

> Дата: 2026-07-20  
> Ветка: `feature/runtime-control-hardening`, HEAD `56fed4b` до незакоммиченных E2-изменений  
> Статус: `E2 PARTIAL — EXTERNAL DEPLOYMENT ENFORCEMENT REQUIRED`

## 1. Вердикт

**E2 PARTIAL — EXTERNAL DEPLOYMENT ENFORCEMENT REQUIRED.** В репозитории реализованы
атомарный дистрибутив, исполняемый pre-JVM verifier и runtime fail-closed proof. Fresh packaged
WebFlux/composition проверки прошли; успешный WebMVC fixture после переключения на embedded Agent
не перезапускался. Kafka fixture собран, но по указанию владельца после
обнаружения системной DNS-проблемы Gentoo его runtime-прогон отложен. До Kafka green и внешнего
enforcement launcher/admission/signing rollout приложений небезопасен.

## 2. Pre-flight E1

| E1 claim | Классификация E2 | Evidence |
|---|---|---|
| App/Agent classloader изолированы | CONFIRMED | Fresh `SpringAgentCompositionE2ETest`; app context содержит 0 `OpenTelemetry` bean, current Agent span видим через OTel Context |
| readiness descriptor доказывает полный pipeline | CONFIRMED в пределах callbacks | E1 attestation + E2 callback failure matrix |
| marker/MBean сами по себе достаточны | REFUTED, как в E1 | stock Agent экспортировал sensitive header; endpoint-only readiness не принимается |
| extension failure останавливает JVM | CONTRADICTED upstream | pinned Agent ловит `Throwable` из premain и не пробрасывает его |
| extension failure может закрыть export | CONFIRMED executable | восемь callback failure child-JVM обслужили HTTP, Jaeger не получил spans |
| Kafka parity | E1 UNVERIFIED; E2 COMPILED, NOT EXECUTED | `KafkaControlledAgentE2ETest` |

Fresh E1 regression до E2: extension unit tests и `verifyPlatformAgentDistribution` green;
`AgentExtensionAttestationE2ETest`, `SpringAgentCompositionE2ETest`,
`AgentExtensionFailClosedSecurityE2ETest`, `ReactorContextPropagationAgentE2ETest` выполнены с
`--rerun-tasks` и были green.

## 3. Controlled distribution structure

Owner: `platform-tracing-otel-extension`. Новый модуль не создан: этот модуль уже владеет pinned
Agent dependency, extension JAR, SPI verification и publication lifecycle.

`build/platform-agent-distribution/` содержит:

- `opentelemetry-javaagent.jar` с embedded
  `extensions/platform-tracing-otel-javaagent-extension.jar`;
- standalone `platform-tracing-otel-javaagent-extension.jar` для provenance/inspection;
- `platform-agent-verifier.jar`;
- `manifest.json`, `checksums.sha256`, `VERSION`;
- POSIX и Windows launchers.

Jar/Zip tasks используют reproducible order и отключённые timestamps. Проверенный ZIP публикуется
как Maven artifact с classifier `platform-agent-distribution`; он является атомарной единицей
доставки и rollback. Manifest фиксирует schema,
версии distribution/Agent/extension/protocol, capability profile, Java range, Git commit, Gradle
identity, artifact names и SHA-256.

Double-build с `--rerun-tasks` дал одинаковый SHA-256 ZIP:
`9C5C195D9047867FD24ADDFE1D8D1A9358C415E22D99F4F4CCC6C6685CD2C815`.
`publishMavenJavaPublicationToMavenLocal` опубликовал ZIP рядом с standard/agent/sources/Javadoc
артефактами под classifier `platform-agent-distribution`.

## 4. Official upstream facts

- OpenTelemetry документирует embedded extension в `extensions/` внутри Agent JAR как production
  single-JAR deployment, исключающий забытый external flag:
  [Java Agent extensions](https://opentelemetry.io/docs/zero-code/java/agent/extensions/).
- External extension path задаётся `otel.javaagent.extensions` / `OTEL_JAVAAGENT_EXTENSIONS`:
  [Java Agent configuration](https://opentelemetry.io/docs/zero-code/java/agent/configuration/).
- В pinned `2.28.1` `OpenTelemetryAgent.startAgent()` ловит любой `Throwable` и не пробрасывает его:
  [OpenTelemetryAgent.java v2.28.1](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/v2.28.1/javaagent-bootstrap/src/main/java/io/opentelemetry/javaagent/OpenTelemetryAgent.java).

Следствие: JVM abort нельзя считать свойством SPI. Допустимый outcome E2 — незавершённая Agent SDK
autoconfiguration и доказанно закрытый exporter.

## 5. Pre-JVM enforcement

`PlatformAgentDistributionVerifier` зависит только от JDK и запускается отдельной JVM до JVM
приложения. Коды: `0` success, `10` usage, `20` manifest, `21` missing artifact, `22` checksum,
`23` compatibility, `24` launch conflict, `30` I/O.

Проверяются schema/profile/protocol/Java range, JAR manifest versions, SHA-256 обоих JAR,
наличие и byte identity embedded extension, external extension env и duplicate `-javaagent`.
Полные command lines и значения env не логируются.

Негативные unit cases: corrupt manifest, modified Agent, modified extension, missing extension,
wrong protocol, external extension parameter и duplicate Agent argument.

## 6. Runtime initialization and fail-closed proof

Production secure profile теперь отвергает `platform.tracing.scrubbing.enabled=false` исключением
во время properties callback. Test-only extension структурно изолирован отдельным source set и
моделирует failures стадий: extension initialization, configuration, sanitizer, sampler,
SpanProcessor, propagation, exporter и protected export path.

Для каждой стадии child Spring JVM дошла до HTTP request и завершилась с кодом 0, upstream Agent
сообщил bootstrap exception, а Jaeger в устойчивом окне не зарегистрировал service/span. Это
подтверждает outcome B: application может работать, но Agent export path остаётся закрыт.

## 7. WebMVC parity

До финального переключения fixture E1/E2 подтвердил реальный servlet request, Agent export и
удаление captured `Authorization` до Jaeger при external extension loading; stock Agent control
экспортировал исходный bearer value. Mandatory-failure матрица уже использовала embedded Agent и
подтвердила закрытый export при обслуженном servlet request. Успешный protected WebMVC case после
переключения на embedded controlled Agent **не перезапускался** и остаётся pending.

Fresh composition fixture на embedded Agent подтвердил 0 app-side OTel bean, отсутствие cross-CL
object injection, видимость current Agent span и сохранение Agent instrumentation при facade
`DISABLED`.

Полная новая exact-count матрица server/client/manual spans в E2 не добавлена; существующие
duplicate HTTP и composition проверки остаются источником регрессии.

## 8. WebFlux parity

Fresh `ReactorContextPropagationAgentE2ETest` на embedded controlled Agent прошёл: traceId и
platform remote-service context сохранились после `publishOn` на parallel thread, HTTP span дошёл
до Jaeger, Spring OTel SDK bean не создавался. Concurrent request leakage и exact duplicate count
отдельно в E2 не измерялись.

## 9. Kafka parity

Подтверждён production defect: `KafkaBatchLinksAspect` требовал Spring `OpenTelemetry` bean и был
недоступен в Agent mode. Aspect переведён на OTel-free W3C `traceparent`/`tracestate` extraction в
`RemoteSpanLink`; conditional Spring SDK bean удалён. Unit tests green.

Добавлен packaged `KafkaControlledAgentE2ETest`: remote Kafka container, producer send,
single-record retry/redelivery, batch listener, 0 Spring OTel bean, Agent propagation и минимум два
links у batch span. Fixture компилируется, но runtime **не выполнен** после запрета Gentoo-прогона.
Kafka arbitrary sensitive-header export не доказан: upstream Kafka instrumentation не публикует
такой header как гарантированный span attribute. Этот security case нельзя объявлять green.

## 10. Operational failure matrix

| Failure | Repository behavior | Status |
|---|---|---|
| Corrupt manifest/JAR, missing extension | verifier rejects before app JVM | GREEN |
| Wrong protocol/profile/version | verifier exit 23 | GREEN |
| Duplicate/external Agent configuration | verifier exit 24 | GREEN where launcher is enforced |
| Mandatory runtime callback failure | application may start; exporter remains closed | GREEN packaged |
| Successful protected WebMVC on embedded Agent | code migrated; fresh runtime rerun absent | PENDING |
| Collector unavailable | startup resilience inherited from E1 tests | GREEN prior evidence |
| Stock Agent bypasses launcher | repository cannot prevent external command | EXTERNAL BLOCKER |
| Kafka runtime parity | fixture compiled only | PENDING |
| Gradle configuration cache | fat-JAR/prepare tasks используют execution-time project/task state | NOT SUPPORTED |

## 11. Supply chain and rollback

Rollback unit is the complete versioned distribution archive, never Agent without extension.
Checksums are strong integrity metadata, not authenticity. Artifact signing, immutable registry,
SBOM/provenance attestation, Helm/init-container invocation and admission policy are absent from this
repository and remain mandatory external work. Launcher bypass must be prohibited operationally.

## 12. Exact architectural consequence

B1-C remains technically viable without a second SDK: Agent owns SDK/instrumentation/pipeline;
starter owns facade and application adapters. E2 does not approve production rollout. Approval needs
fresh Kafka and protected WebMVC green, remaining exact Web parity gaps disposition, signed immutable distribution and
deployment enforcement proving every service invokes preflight and cannot attach stock Agent.
