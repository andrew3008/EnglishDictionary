# Slice E2: Controlled Agent Distribution Evidence

> Дата: 2026-07-20  
> Ветка: `feature/runtime-control-hardening`, HEAD `56fed4b` до незакоммиченных E2-изменений  
> Исторический E2 evidence. Repository-часть superseded итоговым `CP-E APPROVED` в
> `platform-tracing-slice-e-evidence.md`. Формулировка `E2 PARTIAL` означает только открытый
> внешний release gate `RG-CONTROLLED-AGENT`; она не блокирует Slice F.
> Статус: `CP-E APPROVED / SLICE E CLOSED / SLICE F UNBLOCKED`
> Release gate: `RG-CONTROLLED-AGENT OPEN / PRODUCTION ROLLOUT FORBIDDEN`

## 1. Вердикт

**CP-E APPROVED; SLICE E CLOSED; SLICE F UNBLOCKED; RG-CONTROLLED-AGENT OPEN.** В репозитории реализованы
атомарный дистрибутив, исполняемый pre-JVM verifier и runtime fail-closed proof. Fresh packaged
WebMVC/WebFlux/composition проверки прошли. Kafka runtime через remote Gentoo Docker и IP endpoints
подтвердил producer `send|publish`, delivery/retry/batch/consumer spans/manual links без Spring SDK
bean. До внешнего enforcement launcher/admission/signing pilot и production rollout запрещены,
но внутренняя разработка может перейти к Slice F.

Spring startup отклоняет stock Agent без compatible extension, но это не pre-JVM security boundary:
ранний незащищённый export возможен до Spring fail-fast. Stock Agent остаётся неподдерживаемой и
небезопасной deployment-конфигурацией; защита production требует `RG-CONTROLLED-AGENT` enforcement.

## 2. Pre-flight E1

| E1 claim | Классификация E2 | Evidence |
|---|---|---|
| App/Agent classloader изолированы | CONFIRMED | Fresh `SpringAgentCompositionE2ETest`; app context содержит 0 `OpenTelemetry` bean, current Agent span видим через OTel Context |
| readiness descriptor доказывает полный pipeline | CONFIRMED в пределах callbacks | E1 attestation + E2 callback failure matrix |
| marker/MBean сами по себе достаточны | REFUTED, как в E1 | stock Agent экспортировал sensitive header; endpoint-only readiness не принимается |
| extension failure останавливает JVM | CONTRADICTED upstream | pinned Agent ловит `Throwable` из premain и не пробрасывает его |
| extension failure может закрыть export | CONFIRMED executable | восемь callback failure child-JVM обслужили HTTP, Jaeger не получил spans |
| Kafka parity | CONFIRMED executable | producer/consumer/retry/batch/manual links green |

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

Fresh successful protected WebMVC case на embedded controlled Agent подтвердил реальный servlet
request, Agent export и удаление captured `Authorization` до Jaeger; stock Agent control
экспортировал исходный bearer value. Mandatory-failure матрица использовала embedded Agent и
подтвердила закрытый export при обслуженном servlet request.

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

Packaged `KafkaControlledAgentE2ETest` выполнен через
`DOCKER_HOST=tcp://192.168.100.70:2375` и IP-based mapped endpoints: producer delivery,
single-record retry (`attempts=2`), batch из двух записей, 0 Spring OTel bean, consumer `process`
spans и минимум два links у manual batch span подтверждены. Links доказывают W3C propagation из
producer records. Producer operation проверяется как `send|publish`: pinned Agent использует
актуальное semantic-convention имя `publish`; прежнее ожидание только `send` было дефектом fixture.
Stock `always_on` и controlled `platform` прогоны оба подтвердили producer span.
Kafka arbitrary sensitive-header export не доказан: upstream Kafka instrumentation не публикует
такой header как гарантированный span attribute. Этот security case нельзя объявлять green.

## 10. Operational failure matrix

| Failure | Repository behavior | Status |
|---|---|---|
| Corrupt manifest/JAR, missing extension | verifier rejects before app JVM | GREEN |
| Wrong protocol/profile/version | verifier exit 23 | GREEN |
| Duplicate/external Agent configuration | verifier exit 24 | GREEN where launcher is enforced |
| Mandatory runtime callback failure | application may start; exporter remains closed | GREEN packaged |
| Successful protected WebMVC on embedded Agent | stock leak control + protected sanitization | GREEN packaged |
| Collector unavailable | startup resilience inherited from E1 tests | GREEN prior evidence |
| Stock Agent bypasses launcher | repository cannot prevent external command | EXTERNAL BLOCKER |
| Kafka consumer/retry/batch/manual-links parity | remote packaged test through IP endpoints | GREEN |
| Kafka producer span | `send|publish`, stock и controlled Agent | GREEN |
| Production distribution configuration cache | build/prepare/verifier entry stored и reused | GREEN |
| Existing production `agentExtensionJar` cache debt | pre-existing execution-time `project` | P2 / `TD-SLICE-E-CC-01` |
| New Slice E opt-in failure fixture cache limitation | `testE2FailureAgentJar` execution-time task access | P2 / `TD-SLICE-E-CC-01` |

## 11. Supply chain and rollback

Rollback unit is the complete versioned distribution archive, never Agent without extension.
Checksums are strong integrity metadata, not authenticity. Artifact signing, immutable registry,
SBOM/provenance attestation, Helm/init-container invocation and admission policy are absent from this
repository and remain mandatory external work. Launcher bypass must be prohibited operationally.

## 12. Exact architectural consequence

B1-C remains technically viable without a second SDK: Agent owns SDK/instrumentation/pipeline;
starter owns facade and application adapters. E2 does not approve production rollout. Approval needs
signed immutable distribution and deployment enforcement
proving every service invokes preflight and cannot attach stock Agent.

Release contract: [RG-CONTROLLED-AGENT](../architecture/rg-controlled-agent-release-gate.md).
