<img src="https://r2cdn.perplexity.ai/pplx-full-logo-primary-dark%402x.png" style="height:64px;margin-right:32px"/>

## 1. Fact-Check Summary

Подтверждается, что stock `BatchSpanProcessor` в OpenTelemetry Java использует bounded queue и при переполнении отбрасывает **новые** spans, а не старые, так что drop-oldest семантику он не реализует.[^1][^2][^3]
Подтверждается, что `SpanExporter.export()`, `flush()` и `shutdown()` возвращают `CompletableResultCode`, а `BatchSpanProcessor` гарантирует не более одного экспорта одновременно.[^4][^2][^1]
Подтверждается, что `Condition.awaitNanos()` атомарно освобождает lock, может возвращать при spurious wakeup и должен использоваться в цикле с повторной проверкой условия.[^5][^6]
Подтверждается, что `BlockingDeque`/`LinkedBlockingDeque` поддерживают blocking put/take семантику и capacity constraints, поэтому прямой переход на них легко нарушит требование неблокирующего `onEnd`.[^2]
Частично подтверждается, что виртуальные потоки и Disruptor могут быть технически релевантны, но для single-worker, low-latency span processor их польза неочевидна и легко становится over-engineering; это уже не факт контракта, а архитектурная оценка.[^7][^8]

## 2. Claim Validation Table

| claim | status | evidence | source | correction |
| :-- | :-- | :-- | :-- | :-- |
| Stock BSP does not support drop-oldest semantics. | confirmed | Code/comments and issue describe queue-full behavior as dropping the incoming span, not evicting oldest. | [^1][^3][^2] | Keep this claim as-is. |
| BSP can replace the custom processor if configured properly. | incorrect | BSP’s overflow policy is still drop-new. | [^1][^3][^9] | BSP cannot replace a drop-oldest processor without semantic loss. |
| `SpanProcessor` lifecycle requires `forceFlush` and `shutdown` semantics. | confirmed | SDK docs and exporter contracts show `forceFlush`/`shutdown` are part of the lifecycle. | [^2][^4] | Keep, but avoid claiming exact shutdown timing guarantees beyond docs. |
| `SpanExporter.export()` may run concurrently unless processor serializes it. | confirmed | Javadoc states export ops can be simultaneous depending on processor; BSP serializes them. | [^4][^1] | Keep. |
| `CompletableResultCode` timeout handling always means success with timeout info only. | unsupported | No source here establishes that; only that joins and completion semantics exist. | [^4][^2] | Phrase cautiously: timeout handling is processor-specific. |
| `Condition.awaitNanos()` returns remaining time and may spuriously wake. | confirmed | JDK docs state both explicitly. | [^5][^6] | Keep. |
| `BlockingDeque` can be used as a non-blocking bounded queue with drop-oldest by default. | incorrect | JDK docs show `offerLast` is non-blocking, but `put`/`take` block; no default drop-oldest overflow policy. | [^2] | Only say it can be used as a building block, not a semantic replacement. |
| Virtual threads are a useful refactoring target here. | partially confirmed | Java 21 features exist, but docs do not show advantage for one daemon worker and lock-heavy path. | [^7][^10] | Treat as speculative and likely unnecessary. |
| Disruptor is appropriate for this processor. | partially confirmed | Disruptor is real and high-throughput, but suitability for this workload is not established by sources. | [^8][^11] | Mark as over-engineering unless benchmark evidence proves need. |
| `ReentrantLock` + `Condition` must be used in try/finally. | confirmed | JDK docs recommend lock/try/finally and show that `unlock()` must be done by owner thread. | [^5] | Keep. |
| Repository claims about drop-oldest, JMX getters, opt-in policy, and shutdown idempotency are repository-specific facts. | partially confirmed | They are consistent with attached dossier, but cannot be verified here without the repository files in this turn. | repository evidence not directly re-read in this turn | Preserve as working assumptions only if already established in the dossier. |

## 3. OpenTelemetry Contract Validation

The OpenTelemetry Java SDK contract appears consistent with the dossier’s broad assumptions: `SpanExporter` exposes `export`, `flush`, and `shutdown`, all returning `CompletableResultCode`, and `BatchSpanProcessor` serializes export execution while handling queueing internally.[^1][^4][^2]
The stock BSP overflow behavior is the crucial verified point: queue-full handling is drop-new, not drop-oldest, so the custom processor remains necessary if the platform must preserve newest spans under pressure.[^3][^9][^1]
The docs support that exporter operations may otherwise be concurrent depending on processor type, which validates the repository rule that `exporter.export()` must not be called under a producer lock and should stay isolated on the worker side.[^4][^1]
The lifecycle concern around `shutdown()` remains directionally correct, but exact timeout behavior for a custom processor is repository-defined, not standardized by the OpenTelemetry docs cited here.[^2][^4]

## 4. Java Concurrency Validation

`Condition.awaitNanos()` is exactly the kind of primitive where the dossier’s caution is justified: it releases the lock atomically, can wake spuriously, and requires loop-based condition checks on re-entry.[^6][^5]
`ReentrantLock` documentation supports the repository’s concern about lock ownership and `unlock()` correctness; `unlock()` by a non-owner throws `IllegalMonitorStateException`, and the docs explicitly recommend `lock()` followed by `try/finally`.[^5]
`BlockingDeque` and `LinkedBlockingDeque` are valid concurrent collections, but they introduce blocking insertion/removal APIs and do not inherently encode drop-oldest overflow semantics, so they are not a drop-in semantic replacement for the current manual queue.[^2]
The claim that virtual threads or structured concurrency should be part of the refactor is overstated for this class: nothing in the JDK docs makes them a better fit than a single daemon worker for a low-latency span processor, and they do not solve the core semantic requirements.[^10][^12][^7]
Disruptor remains a legitimate high-throughput primitive, but its relevance is workload-driven and cannot be assumed from the docs alone; for this component it should be treated as a speculative option, not a baseline recommendation.[^8][^11]

## 5. Repository Constraint Validation

The repository-specific constraints in the dossier are mostly internally consistent as engineering requirements, but they are not independently re-verified in this turn because the repository files themselves were not re-read here.
The strongest repository assumptions that should be carried forward are: explicit `DROP_OLDEST` opt-in only, `onEnd` must stay fast/non-blocking, `exporter.export()` must stay outside producer locks, `shutdown()` must be idempotent, and `forceFlush()` after shutdown should return success immediately.
The scoring assumptions and variant rankings are not externally verifiable facts; they are judgment calls that depend on the repository’s exact implementation, benchmark data, and the team’s risk appetite.
Any statement implying that a particular refactoring variant is objectively “best” should therefore be treated as a recommendation, not a fact.

## 6. Model Output QA

The earlier research outputs agree on the central factual points: stock BSP is drop-new, `Condition.awaitNanos()` has spurious wakeups and lock reacquisition semantics, `SpanExporter` uses `CompletableResultCode`, and `BlockingDeque` is blocking-capable rather than a semantic drop-in.[^1][^4][^5][^2]
The main area needing correction is overstatement: virtual threads, structured concurrency, and Disruptor are technically real but not proven appropriate for this processor; those should be presented as optional, high-risk alternatives rather than recommended paths.[^11][^7][^8]
Claims about `CompletableResultCode` timeout semantics are the most likely to be overinterpreted if they are presented as universal SDK behavior instead of processor/exporter-specific implementation detail.[^4][^2]
Any architecture recommendation that suggests replacing the custom processor with BSP, or that treats `BlockingDeque`/`ArrayBlockingQueue` as preserving drop-oldest by default, should be corrected immediately.[^3][^1][^2]

## 7. Corrected Assumption Set

- Stock OpenTelemetry Java `BatchSpanProcessor` does not provide drop-oldest overflow semantics; it drops the newly arriving span when full.[^9][^3][^1]
- `SpanExporter.export()`, `flush()`, and `shutdown()` return `CompletableResultCode` and may be asynchronous.[^4][^2]
- `BatchSpanProcessor` serializes export execution, so any custom processor must preserve the “export outside producer lock” invariant if it wants to avoid blocking `onEnd`.[^1][^4]
- `Condition.awaitNanos()` can wake spuriously and must be used in a loop that re-checks the predicate.[^6][^5]
- `ReentrantLock` should be acquired and released with `try/finally`; non-owner unlock is an error.[^5]
- `BlockingDeque`/`LinkedBlockingDeque` are blocking-capable concurrent queues, not a built-in drop-oldest queue abstraction.[^2]
- Virtual threads and Disruptor are optional architectural technologies, not validated necessities for this class.[^7][^8][^11]
- Repository constraints about idempotent shutdown, immediate success after shutdown forceFlush, JMX compatibility, and opt-in policy should be preserved as working requirements unless the repository dossier says otherwise.
- Scoring matrices and architectural rankings are decision aids, not factual findings.
- The safest implementation planning input is the set of verified contracts above plus the repository dossier’s constraints.


## 8. Final QA Verdict

Да — предыдущие research outputs можно использовать как вход для implementation planning, **но только после исправления overstatements** around virtual threads, Disruptor, and any implied ability to replace the custom processor with stock BSP.[^5][^1][^4][^2]
The core contract facts are confirmed, and the main architectural guardrails remain valid; however, any ADR or refactoring plan should explicitly mark the repository-specific constraints as source-of-truth from the dossier, not as externally verified OpenTelemetry guarantees.
<span style="display:none">[^13][^14][^15][^16][^17][^18][^19][^20][^21][^22][^23][^24][^25][^26][^27][^28][^29][^30][^31]</span>

<div align="center">⁂</div>

[^1]: https://github.com/open-telemetry/opentelemetry-java/blob/main/sdk/trace/src/main/java/io/opentelemetry/sdk/trace/export/BatchSpanProcessor.java

[^2]: https://opentelemetry.io/docs/languages/java/sdk/

[^3]: https://github.com/open-telemetry/opentelemetry-java/issues/7103

[^4]: https://javadoc.io/static/io.opentelemetry/opentelemetry-sdk-trace/1.0.1/io/opentelemetry/sdk/trace/export/SpanExporter.html

[^5]: https://docs.oracle.com/cd/E17802_01/j2se/j2se/1.5.0/jcp/beta1/apidiffs/java/util/concurrent/locks/ReentrantLock.ConditionObject.html

[^6]: https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/locks/Condition.html

[^7]: https://java.elitedev.in/java/java-21-virtual-threads-and-structured-concurrency-complete-performance-guide-with-spring-boot-inte-5245cb93/

[^8]: https://lmax-exchange.github.io/disruptor/user-guide/index.html

[^9]: https://oneuptime.com/blog/post/2026-02-06-fix-nodejs-batch-processor-queue-full/view

[^10]: https://developers.redhat.com/articles/2023/09/21/whats-new-developers-jdk-21

[^11]: https://lmax-exchange.github.io/disruptor/javadoc/com.lmax.disruptor/com/lmax/disruptor/class-use/RingBuffer.html

[^12]: https://developers.redhat.com/articles/2023/10/03/beyond-loom-weaving-new-concurrency-patterns

[^13]: https://github.com/perplexityai/api-cookbook/blob/main/docs/examples/fact-checker-cli/fact_checker.py

[^14]: https://docs.perplexity.ai/docs/sonar/models/sonar-reasoning-pro

[^15]: https://docs.perplexity.ai/docs/cookbook/examples/fact-checker-cli/README

[^16]: https://perplexity.mintlify.app/getting-started/models/models/sonar-reasoning-pro

[^17]: https://docs.perplexity.ai/docs/sonar/models

[^18]: https://www.youtube.com/watch?v=9kgasuV0WfM

[^19]: https://docs.perplexity.ai/docs/sonar/pro-search/quickstart

[^20]: https://docs.perplexity.ai/docs/cookbook/showcase/truth-tracer

[^21]: https://community.perplexity.ai/t/sonar-reasoning-pro-returning-500-internal-server-error-in-the-beginning-of-this-week/827

[^22]: https://docs.perplexity.ai/docs/sonar/quickstart

[^23]: https://github.com/open-telemetry/opentelemetry-java/issues/6160

[^24]: https://javadoc.io/static/io.opentelemetry/opentelemetry-exporter-jaeger-thrift/1.10.0-rc.2/io/opentelemetry/exporter/jaeger/thrift/JaegerThriftSpanExporter.html

[^25]: https://www.javalld.com/learn/condition-await

[^26]: https://github.com/open-telemetry/opentelemetry-swift/blob/main/Sources/OpenTelemetrySdk/Trace/SpanProcessors/BatchSpanProcessor.swift

[^27]: https://github.com/open-telemetry/opentelemetry-java/issues/3521

[^28]: https://www.byteslounge.com/tutorials/lock-conditions-in-java

[^29]: https://wdk-docs.github.io/opentelemetry-docs/docs/instrumentation/java/manual/

[^30]: https://docs.rs/opentelemetry-otlp/latest/opentelemetry_otlp/struct.SpanExporter.html

[^31]: https://javadoc.io/static/io.opentelemetry/opentelemetry-exporters-logging/0.9.1/io/opentelemetry/exporters/logging/LoggingSpanExporter.html

