# RG-CONTROLLED-AGENT: Production Release Gate

> Status: `RG-CONTROLLED-AGENT OPEN`
> Blocks pilot: yes
> Blocks production: yes
> Blocks Slice F: no
> `PRODUCTION ROLLOUT FORBIDDEN`

## Purpose

Этот документ является единым repository source of truth для внешнего release gate Controlled
Platform Agent Distribution. `CP-E APPROVED`, `SLICE E CLOSED`, `SLICE F UNBLOCKED`, но approval
repository-архитектуры не разрешает pilot или production rollout.

Spring startup отклоняет stock Agent без compatible platform extension. Это application-side
fail-fast, а не pre-JVM security boundary: он не способен предотвратить ранний незащищённый Agent
export. Изолированный evidence-run подтвердил export captured `Authorization` stock Agent; Controlled
Agent удалил значение до export. Поэтому stock Agent остаётся unsupported and unsafe.

## Required work

- artifact signing и проверка authenticity;
- SBOM;
- build provenance и attestation;
- immutable artifact или container registry;
- mandatory pre-JVM verifier invocation;
- Helm или init-container integration;
- admission policy;
- rejection произвольного stock Agent;
- rejection external extension override;
- защита от `JAVA_TOOL_OPTIONS` и обхода Agent launcher;
- version compatibility Controlled Distribution;
- fleet census;
- pilot evidence;
- rollback drill;
- rollout/rollback proof для примерно 80 сервисов.

## Required owner roles

Формальные персональные владельцы не подтверждены. Для закрытия gate требуются роли:

- Platform Tracing;
- Platform Build/CI;
- DevSecOps/Supply Chain;
- Kubernetes Platform;
- SRE/Observability;
- service rollout owners.

## Acceptance criteria

Gate закрывается только исполняемым или операционным evidence, подтверждающим одновременно:

1. каждый production service получает утверждённый Controlled Agent Distribution;
2. verifier выполняется до application JVM, а его failure предотвращает startup;
3. stock Agent нельзя подменить через поддерживаемые deployment paths;
4. external extension override не может обойти embedded extension;
5. rollout и rollback используют полный distribution как одну атомарную единицу;
6. unsigned, tampered или incompatible artifacts отклоняются;
7. fleet census, pilot evidence и rollback drill подтверждены для rollout-модели примерно 80 сервисов.

До выполнения всех критериев статус остаётся неизменным:

```text
RG-CONTROLLED-AGENT OPEN
PRODUCTION ROLLOUT FORBIDDEN
```

Внешний issue/epic должен ссылаться на этот документ. В репозитории не найден подтверждённый
идентификатор внешнего трекера; создавать вымышленный идентификатор запрещено.
