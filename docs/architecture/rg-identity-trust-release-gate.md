# RG-IDENTITY-TRUST: Identity Trust Release Gate

> Status: `RG-IDENTITY-TRUST OPEN`
> Blocks pilot: yes (trusted inbound business correlation)
> Blocks production: yes (trusted inbound business correlation)
> Blocks Slice M: no
> `PRODUCTION ROLLOUT FORBIDDEN`

## Purpose

Единый repository source of truth для отдельного release-hardening gate,
разрешающего **trusted inbound business correlation** между сервисами.

CP-1 утверждён в редакции R2 (`CP-1 APPROVED (R2)`), включая F0 fail-closed
(CP-1(f)). F0 — корректный secure baseline: весь недоказанный ingress остаётся
untrusted, а `correlationId`/baggage вырезается до создания server/consumer span.
Approval CP-1 разрешает старт Slice M, но **не** разрешает доверять входящей
межсервисной бизнес-корреляции и **не** разрешает pilot/production rollout.

Slice M реализует только F0-путь (programmatic assignment + fail-closed ingress).
Функциональная распределённая входящая бизнес-корреляция между сервисами
включается лишь после закрытия этого gate.

## Scope

- Входит: trusted inbound business `correlationId` по HTTP и Kafka.
- Не входит: programmatic application assignment (поддержано под F0 в Slice M),
  requestId propagation (CP-C2), Controlled Agent distribution (`RG-CONTROLLED-AGENT`).

## Required work (per transport)

Для каждого транспорта (HTTP, Kafka) требуется отдельное принятое решение,
называющее:

1. canonical authenticated signal (например, verified mTLS workload identity или
   signed token — не сырой заголовок, не hostname/IP, не topic name);
2. verifier implementation и owning module/team;
3. источник конфигурации, ротация ключей/доверия и fail-behavior;
4. точную точку enforcement до создания Agent-span / до consumer-обработки;
5. негативные тесты: spoof, missing verifier, verifier exception, ambiguous
   credentials, duplicate/oversized/invalid ingress.

Запрещено: универсальный `Object`/`Map`-carrier, доверие на основе source IP,
hostname, произвольных заголовков, topic name или конфигурации в одиночку.

## Acceptance criteria

Gate закрывается только исполняемым evidence, подтверждающим одновременно:

1. inbound business `correlationId` принимается только при успешной проверке
   canonical authenticated signal;
2. spoofed/duplicate/invalid/oversized ingress вырезается до server/consumer span
   (fail-closed сохраняется);
3. отсутствие или сбой verifier не приводит к принятию untrusted значения;
4. enforcement выполняется до auto-instrumented span (HTTP) и до listener-обработки
   (Kafka);
5. negative-тесты (spoof, missing verifier, verifier exception, ambiguous
   credentials) присутствуют и зелёные;
6. egress-классификация не выносит business correlation на external/untrusted
   destination.

До выполнения всех критериев статус остаётся неизменным:

```text
RG-IDENTITY-TRUST OPEN
PRODUCTION ROLLOUT FORBIDDEN
```

## Relationship to other gates

- `RG-CONTROLLED-AGENT` — отдельный gate (Controlled Agent Distribution); не
  смешивать. Оба остаются OPEN независимо.
- Production rollout запрещён до закрытия всех применимых gate.

Внешний issue/epic должен ссылаться на этот документ. В репозитории не найден
подтверждённый идентификатор внешнего трекера; создавать вымышленный
идентификатор запрещено.
