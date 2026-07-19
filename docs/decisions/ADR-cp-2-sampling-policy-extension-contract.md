# ADR CP-2: Sampling Policy Extension Contract

## Status

**PROPOSED / CLARIFICATION REQUIRED — production-код не изменён.**

Дата evidence review: 2026-07-19.

## Decision Required

Architecture committee должен уточнить техническую форму уже выбранного intent `sealed internal`:

- **A1 (recommended):** оставить `SamplingPolicyRule` публичным для cross-package компиляции, но сделать его `sealed` с исчерпывающим `permits` для семи package-private platform rules;
- **A2:** сделать `SamplingPolicyRule` package-private и явно разрешить structural move, необходимый для совместного размещения engine и policy implementation;
- **B:** признать контракт поддерживаемым OTel-free SPI и отдельно утвердить versioning, composition и compatibility semantics.

До явного выбора A1/A2/B запрещены изменения visibility, package topology, engine factory и extension wiring.

## HEAD Evidence

Текущий контракт:

```java
public interface SamplingPolicyRule {
    String ruleName();
    SamplingPolicyDecision evaluate(SamplingPolicyRequest request, SamplingPolicySnapshot snapshot);
}
```

Фактические свойства HEAD:

- `SamplingPolicyRule` — public, unsealed и входит в `platform-tracing-core` ABI snapshot;
- все семь platform implementations package-private и находятся в `core.sampling.policy`;
- `SamplingPolicyEngine` находится в соседнем `core.sampling.engine`, поэтому package-private rule type ему недоступен;
- `ProductionSamplingPolicyChain` public исключительно для cross-package assembly;
- production engine создаётся только через `SamplingPolicyEngine.productionEngine()`;
- repository search не обнаружил реализаций `SamplingPolicyRule` вне platform policy package;
- поддерживаемого registration/composition механизма для внешних правил нет;
- `ModuleTaxonomyArchRules.SAMPLING_RULE_IMPLS_ONLY_IN_POLICY` контролирует только анализируемые классы репозитория и не может запретить внешнему consumer реализовать public unsealed interface.

Последний пункт исправляет слишком сильное утверждение действующего `ADR-sampling-package-layering.md`: ArchUnit является repository boundary gate, но не Java-level запретом для external classpath.

## Constraint Conflict

Три текущих требования нельзя выполнить буквально одновременно:

1. `SamplingPolicyRule` package-private;
2. `SamplingPolicyEngine` остаётся в соседнем пакете `core.sampling.engine`;
3. структура `core.sampling.{engine,model,policy,properties}` остаётся без repackaging и обходных public bridge API.

Java package visibility делает пункт 1 несовместимым с пунктами 2 и 3. Обход через public evaluator/function bridge лишь переносит accidental API на другой тип и не является sealing.

## Option A1 — Public Sealed Contract

Предлагаемая точная форма:

```java
public sealed interface SamplingPolicyRule
        permits KillSwitchPolicyRule,
                HardDropPolicyRule,
                ForceHeaderPolicyRule,
                QaTracePolicyRule,
                ParentSampledPolicyRule,
                RouteRatioPolicyRule,
                DefaultRatioPolicyRule {

    String ruleName();

    SamplingPolicyDecision evaluate(SamplingPolicyRequest request, SamplingPolicySnapshot snapshot);
}
```

Свойства:

- package topology и hot path не меняются;
- внешняя реализация запрещается Java compiler/runtime, а не только ArchUnit;
- contract остаётся видимым как implementation boundary, но не становится supported extension SPI;
- существующие platform rule classes уже `final` и удовлетворяют sealed hierarchy;
- public methods, engine factory, rule order и reason codes не меняются;
- breaking change для гипотетических external implementations намеренный и допустим до production.

Необходимые gates после approval A1:

- reflection test: `SamplingPolicyRule.class.isSealed()`;
- exact permitted-subclass set из семи platform rules;
- negative external-consumer compile test на custom `implements SamplingPolicyRule`;
- public ABI snapshot review с единственным intentional modifier delta;
- существующие golden, characterization, contention и OTel adapter tests.

## Option A2 — Package-Private Rule

Этот вариант соответствует буквальной формулировке плана, но требует отдельного разрешения на изменение package boundary или redesign engine/chain assembly. Он противоречит текущему `structure KEEP / no sampling repackage` и поэтому не может быть выбран implementation agent автоматически.

## Option B — Supported Versioned SPI

Вариант B допустим только при подтверждённой продуктовой потребности внешних правил для парка сервисов. До реализации должны быть утверждены:

- exact OTel-free signatures и version contract;
- registration/composition owner без static `ServiceLoader` в API;
- deterministic placement относительно kill-switch, hard-drop и default-ratio;
- duplicate identity, ordering и conflict semantics;
- exception/fail-closed behavior;
- thread-safety, allocation budget и lifecycle;
- rollout/rollback и compatibility policy;
- external-consumer compile/runtime fixture.

Без этих решений public unsealed interface не считается SPI.

## Golden Verification

Команда:

```powershell
.\gradlew.bat :platform-tracing-core:test --tests "*ProductionSamplingPolicyChainTest" --tests "*SamplingPolicyEngineTest" --tests "*SamplingPolicyReasonTest" :platform-tracing-otel-extension:test --tests "*Sampling*CharacterizationTest" --tests "*SamplerRuntimeUpdateConcurrencyTest" --tests "*TraceIdRatioParityTest" --tests "*SamplingPolicyDecisionOtelAdapterTest" --no-daemon
```

Результат: **PASS**.

Подтверждены normative rule order, reason-code mapping, branch precedence, trace-id ratio parity, runtime update contention и OTel adapter behavior.

## Recommendation

Утвердить **A1** как минимальное risk-reducing уточнение intent `sealed internal`. A1 устраняет фактическую внешнюю extensibility, сохраняет утверждённую topology и не вводит неподтверждённый product SPI.

Допустимые ответы committee:

- `CP-2 APPROVED A1 — public sealed, exact permits set as proposed`;
- `CP-2 APPROVED A2 — package-private, structural exception to be designed`;
- `CP-2 APPROVED B — supported SPI; prepare exact ABI decision packet`;
- `CP-2 REJECTED — keep current public unsealed contract intentionally`.
