# Week 15 — Failure, security, AOT, compatibility

## Контекст

Extension code недоверенно. Failure policy, trust boundary, AOT/native и SemVer analysis Spring behavior — обязательные части Extension API.

## Failure policy

Возможные policies: fail-fast startup; skip + warning; isolate and continue; fallback to platform default; degrade; retry; quarantine.

| Роль | Типично |
|---|---|
| Security policy | fail-fast |
| Optional telemetry observer | isolate |
| Customizer → invalid client | **не** silent skip (effective config неопределённа) |

Diagnostic: extension id, bean name, phase, operation, preserved cause; secrets redacted.

## Thread safety / reentrancy / idempotency

Singleton может вызываться concurrently. Context refresh / tests / retry factory → повторные вызовы.

Определить: concurrency; reentrancy; invocation count; idempotency; state retention; happens-before; blocking.

Construction customizer часто single-threaded — **не** обещать без documentation. Runtime strategy → thread-safe или per-operation factory. Event recursion → guard или prohibition.

## Observability extension chain

```text
capability: audit-export
selection: ordered-chain
extensions:
  - id=security-mask bean=corpMask order=-100
  - id=platform-tags bean=platformTags order=0
  - id=app-tags bean=ordersTags order=100
```

Наблюдать: candidates; excluded qualifiers; primary/replacement; conflicts; callback duration; exceptions; lifecycle; source context.

Sanitization + controlled exposure. Extension id как unbounded metric tag — запрещён для динамических ids. Detailed inventory → diagnostic endpoint / startup report.

## Security / trust boundary

Extension в процессе приложения — не sandbox. Least authority:

- callback получает только нужный editor;
- secret не в observer;
- ApplicationContext не раскрывается;
- raw credentials не без необходимости;
- security invariants **после** user customization;
- classpath discovery не грузит произвольные packages.

Непреодолимая policy → structural separation (user options ≠ platform-enforced adapter), не надежда на order. Кто может поставлять extension artifact и как versioned — platform decision.

## AOT / native

Build-time analysis: fixed classpath; conditions may resolve at build; runtime singleton registration не преобразуется; reflection/resources/proxies → hints.

AOT-friendly seam:

- register definitions via `BeanDefinitionRegistry`;
- precise types;
- no late classpath scanning;
- `RuntimeHints`;
- JDK proxies/reflection учтены;
- avoid unreproducible instance suppliers;
- test conditional extensions in native mode.

`RuntimeHintsRegistrar` — build-time SPI ≠ runtime customizer. Не объединять одним interface из-за слова «extension».

## Совместимость Extension API

Breaking без смены Java signature:

| Изменение | Риск |
|---|---|
| Order layer | другой effective result |
| Новый platform customizer | конфликт с user write |
| Callback 2× | side effects / duplicate resources |
| Sync event → async | error/tx/context |
| Bean name | lookup break |
| Return type `@Bean` | conditions/resolution |
| Default scope | identity/lifecycle |
| Back-off target | ownership graph |
| Unknown extension ignored | silent typo |
| Mandatory SPI method | binary/source break |

SemVer analysis = Spring behavior matrix + japicmp, не только signatures.

Pre-production: лучше закрыть accidental seams сейчас, чем deprecation вокруг плохой архитектуры.

## Ключевые тезисы

- Failure policy зависит от роли; invalid customize ≠ silent skip.
- Observability chain + redaction обязательны.
- Security через structure, не через order.
- AOT hints и runtime customizers — разные SPI.
- Behavioral SemVer для extension protocol.
